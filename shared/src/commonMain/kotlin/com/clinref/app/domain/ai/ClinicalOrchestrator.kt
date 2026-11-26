package com.clinref.app.domain.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrame
import com.clinref.app.data.DEFAULT_SEARCH_RESULTS
import com.clinref.app.data.GraphicContentResponse
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.RERANK_SEARCH_POOL_SIZE
import com.clinref.app.data.TopicSectionsResponse
import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.shared.platform.PlatformLogger as Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Procedural two-stage clinical inquiry orchestrator.
 *
 * Implements an explicit two-stage pipeline:
 * - Stage 0: Small-talk classifier (immediate deterministic canned reply; zero LLM calls).
 * - Stage 1: Ephemeral evidence retrieval while-loop (tools bound, parallel execution, max 10 rounds).
 * - Stage 2: Closed-book clinical synthesis streaming (tools stripped, live word-by-word streaming).
 */
class ClinicalOrchestrator(
    private val medicalDatabaseTools: MedicalDatabaseTools,
    private val secureLogger: SecureLogger? = null
) {
    companion object {
        private const val TAG = "ClinicalOrchestrator"
        const val MAX_RETRIEVAL_ROUNDS = 10
        const val MAX_RERANK_RETRIEVAL_ROUNDS = 7
        const val MAX_CHAT_HISTORY_TURNS = 100
        private val TOPIC_ID_NUM_REGEX = Regex("""\b\d+\b""")
    }

    data class ExecutionResult(
        val content: String,
        val topicRefs: List<TopicRef> = emptyList(),
        val graphicRefs: List<GraphicRef> = emptyList(),
        val tokenUsage: StreamingManager.TokenUsage = StreamingManager.TokenUsage()
    )

    private val json = AiJsonUtils.json

    suspend fun runTurn(
        question: String,
        patientProfile: PatientProfile,
        chatHistory: List<MessageEntity>,
        executor: PromptExecutor,
        model: LLModel,
        providerParams: LLMParams?,
        streamingManager: StreamingManager,
        isAnthropic: Boolean = false,
        decisionClient: DecisionClient? = null,
        decisionConfig: DecisionConfig? = null,
        turnId: String? = null
    ): ExecutionResult {
        val trimmedQuestion = question.trim()

        // ════════════════════════════════════════════════════════════════════
        // STAGE 0: Small Talk Gating
        // ════════════════════════════════════════════════════════════════════
        if (QuestionRouter.smallTalk(trimmedQuestion)) {
            Log.d(TAG, "Small talk detected: returning canned reply with zero LLM calls")
            val canned = QuestionRouter.CANNED_FALLBACK_REPLY
            streamingManager.onStreamingTextDelta(canned, turnId = turnId)
            val usage = StreamingManager.TokenUsage()
            streamingManager.onCompleted(canned, emptyList(), emptyList(), usage, turnId = turnId)
            return ExecutionResult(canned, emptyList(), emptyList(), usage)
        }

        val accumulator = TurnContextAccumulator(
            topicTitleResolver = { tid -> medicalDatabaseTools.getTopicTitle(tid) },
            sectionTitleResolver = { tid, sid -> medicalDatabaseTools.getSectionTitle(tid, sid) }
        )
        accumulator.setPatientProfile(patientProfile)
        accumulator.setUserQuestion(trimmedQuestion)

        var totalPromptTokens = 0
        var totalCompletionTokens = 0
        var decisionPromptTokens = 0
        val tokenLock = Any()
        val recordDecisionTokens: (Int) -> Unit = { tokens ->
            if (tokens > 0) {
                synchronized(tokenLock) {
                    decisionPromptTokens += tokens
                }
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 1: Ephemeral Evidence Retrieval Loop
        // ════════════════════════════════════════════════════════════════════
        val rerankConfig = if (decisionClient != null && decisionConfig?.enableRetrievalRerank == true) decisionConfig else null
        val rerankActive = rerankConfig != null
        val maxRetrievalRounds = if (rerankActive) MAX_RERANK_RETRIEVAL_ROUNDS else MAX_RETRIEVAL_ROUNDS
        val prefetchBudget = rerankConfig?.let { TurnPrefetchBudget(it) }
        val toolDescriptors = medicalDatabaseTools.asToolList().map { it.descriptor }
        var currentRetrievalPrompt = buildInitialRetrievalPrompt(
            question = trimmedQuestion,
            patientProfile = patientProfile,
            chatHistory = chatHistory,
            providerParams = providerParams,
            isAnthropic = isAnthropic,
            rerankActive = rerankActive
        )
        val turnToolCache = TurnToolCache()

        var round = 0
        var emptyEvidenceRetries = 0
        val maxEmptyEvidenceRetries = 1
        var pendingDrugMonograph = false
        val pendingRelatedTopicIds = mutableSetOf<String>()
        val inspectedTopicIdsThisTurn = mutableSetOf<String>()

        while (round < maxRetrievalRounds) {
            round++
            Log.d(TAG, "Stage 1 Retrieval Round $round/$maxRetrievalRounds")

            val assistantMsg = try {
                executor.execute(currentRetrievalPrompt, model, toolDescriptors)
            } catch (e: Exception) {
                Log.e(TAG, "Retrieval LLM execution failed: ${e.message}")
                throw e
            }

            assistantMsg.metaInfo.let { meta ->
                val p = meta.inputTokensCount ?: 0
                val c = meta.outputTokensCount ?: 0
                totalPromptTokens += p
                totalCompletionTokens += c
                streamingManager.onLlmCallCompleted(p, c, p + c, turnId = turnId)
            }

            val toolCalls = assistantMsg.parts.filterIsInstance<MessagePart.Tool.Call>()
            if (toolCalls.isEmpty()) {
                if (!accumulator.hasEvidence && emptyEvidenceRetries < maxEmptyEvidenceRetries) {
                    emptyEvidenceRetries++
                    Log.w(TAG, "Stage 1 paused with zero evidence; issuing retrieval retry $emptyEvidenceRetries/$maxEmptyEvidenceRetries")
                    val pauseNotice = SystemPrompt.buildEvidenceGatePausePrompt(
                        accumulator.buildDiscoveredInventorySummary()
                    )
                    currentRetrievalPrompt = currentRetrievalPrompt.withMessages { oldMessages ->
                        oldMessages + assistantMsg + Message.User(content = pauseNotice, metaInfo = RequestMetaInfo.Empty)
                    }
                    continue
                }
                Log.d(TAG, "Stage 1 complete: Model requested zero further tool calls")
                break
            }

            // Execute all requested tool calls in parallel
            val executedResults = coroutineScope {
                toolCalls.mapIndexed { idx, tc ->
                    async {
                        val callId = tc.id?.takeIf { it.isNotBlank() }
                            ?: "call_${idx}_${kotlin.random.Random.nextLong().toString(16)}"
                        val outcome = executeSingleTool(
                            toolCall = tc,
                            callId = callId,
                            accumulator = accumulator,
                            streamingManager = streamingManager,
                            decisionClient = decisionClient,
                            decisionConfig = decisionConfig,
                            onDecisionTokens = recordDecisionTokens,
                            turnId = turnId,
                            cache = turnToolCache,
                            prefetchBudget = prefetchBudget
                        )
                        Triple(tc, callId, outcome)
                    }
                }.awaitAll()
            }

            // Synchronize callId across both assistant tool calls and user tool results
            val executedIterator = executedResults.iterator()
            val normalizedAssistantParts = assistantMsg.parts.map { part ->
                if (part is MessagePart.Tool.Call && executedIterator.hasNext()) {
                    val (_, assignedId, _) = executedIterator.next()
                    if (assignedId != part.id) part.copy(id = assignedId) else part
                } else {
                    part
                }
            }
            val normalizedAssistantMsg = assistantMsg.copy(parts = normalizedAssistantParts)

            // Append assistant tool calls and user tool results into ephemeral retrieval prompt
            val toolResultParts = executedResults.map { (tc, callId, outcome) ->
                MessagePart.Tool.Result(
                    id = callId,
                    tool = tc.tool,
                    output = outcome.resultJson
                )
            }
            val userToolMessage = Message.User(
                parts = toolResultParts,
                metaInfo = RequestMetaInfo.Empty
            )

            currentRetrievalPrompt = currentRetrievalPrompt.withMessages { oldMessages ->
                oldMessages + normalizedAssistantMsg + userToolMessage
            }

            // Update turn-level pending obligations across rounds so Round 2+ never short-circuits prematurely
            if (executedResults.any { it.third.satisfiedDrugMonograph }) {
                pendingDrugMonograph = false
            } else if (executedResults.any { it.third.needsDrugMonograph }) {
                pendingDrugMonograph = true
            }
            executedResults.forEach { (_, _, outcome) ->
                inspectedTopicIdsThisTurn.addAll(outcome.inspectedTopicIds)
                pendingRelatedTopicIds.removeAll(outcome.inspectedTopicIds)
            }
            executedResults.forEach { (_, _, outcome) ->
                val relId = outcome.dominantRelatedTopicId
                if (outcome.needsRelatedTopic && !relId.isNullOrBlank() && relId !in inspectedTopicIdsThisTurn) {
                    pendingRelatedTopicIds.add(relId)
                }
            }

            // Short-circuit retrieval if every tool call resolved auto-prefetched/calc evidence with zero pending obligations
            val allPrefetched = executedResults.all { it.third.didPrefetch }
            val allSucceeded = executedResults.all { !it.third.hasError && !it.third.isEmptyResult }
            val noMonographNeeded = !pendingDrugMonograph && executedResults.none { it.third.needsDrugMonograph }
            val noRelatedTopicNeeded = pendingRelatedTopicIds.isEmpty() && executedResults.none { it.third.needsRelatedTopic }
            val canShortCircuit = decisionClient != null && decisionConfig?.autoPrefetch == true

            if (canShortCircuit && executedResults.isNotEmpty() && allPrefetched && allSucceeded && noMonographNeeded && noRelatedTopicNeeded && accumulator.hasEvidence) {
                Log.d(TAG, "Stage 1 all tool calls resolved prefetched evidence with zero monograph/related-topic requirements; short-circuiting directly to Stage 2")
                break
            }
        }

        // If no evidence exists after retrieval rounds and retry attempts, cleanly abstain
        if (!accumulator.hasEvidence) {
            Log.w(TAG, "Zero evidence retrieved after retrieval loop; returning canonical abstention")
            val abstention = SystemPrompt.CANONICAL_ABSTENTION
            streamingManager.onStreamingTextDelta(abstention, turnId = turnId)
            val decTokens = synchronized(tokenLock) { decisionPromptTokens }
            val usage = StreamingManager.TokenUsage(
                promptTokens = totalPromptTokens,
                completionTokens = totalCompletionTokens,
                totalTokens = totalPromptTokens + totalCompletionTokens + decTokens,
                toolTokens = decTokens
            )
            streamingManager.onCompleted(abstention, emptyList(), emptyList(), usage, turnId = turnId)
            return ExecutionResult(abstention, emptyList(), emptyList(), usage)
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 2: Closed-Book Clinical Synthesis (Live Token Streaming)
        // ════════════════════════════════════════════════════════════════════
        streamingManager.onSynthesisStarting(turnId = turnId)

        val synthesisDirective = SystemPrompt.formatSynthesisPrompt(
            userQuestion = trimmedQuestion,
            turnContext = accumulator.buildTurnContext("")
        )

        val synthesisPrompt = buildSynthesisPrompt(
            synthesisDirective = synthesisDirective,
            chatHistory = chatHistory,
            providerParams = providerParams,
            isAnthropic = isAnthropic
        )

        var (draftText, stage2Usage) = streamSynthesisCall(synthesisPrompt, model, executor, streamingManager, turnId = turnId)
        totalPromptTokens += stage2Usage.promptTokens
        totalCompletionTokens += stage2Usage.completionTokens

        // If the model produced blank text (e.g. thinking budget exhaustion), fall back safely
        if (draftText.isBlank()) {
            Log.w(TAG, "Stage 2 produced blank synthesis (e.g. thinking budget exhaustion); falling back to canonical abstention")
            draftText = SystemPrompt.CANONICAL_ABSTENTION
            streamingManager.onStreamingTextDelta(draftText, turnId = turnId)
        }

        val finalContext = accumulator.buildTurnContext(draftText)
        val decTokens = synchronized(tokenLock) { decisionPromptTokens }
        val totalUsage = StreamingManager.TokenUsage(
            promptTokens = totalPromptTokens,
            completionTokens = totalCompletionTokens,
            totalTokens = totalPromptTokens + totalCompletionTokens + decTokens,
            toolTokens = decTokens
        )

        streamingManager.onCompleted(
            result = draftText,
            topicRefs = finalContext.topicRefs,
            graphicRefs = finalContext.graphicRefs,
            usage = totalUsage,
            turnId = turnId
        )
        return ExecutionResult(
            content = draftText,
            topicRefs = finalContext.topicRefs,
            graphicRefs = finalContext.graphicRefs,
            tokenUsage = totalUsage
        )
    }

    private suspend fun streamSynthesisCall(
        prompt: Prompt,
        model: LLModel,
        executor: PromptExecutor,
        streamingManager: StreamingManager,
        turnId: String? = null
    ): Pair<String, StreamingManager.TokenUsage> {
        val textAccumulator = StringBuilder()
        var usage = StreamingManager.TokenUsage()
        var streamCompleted = false

        try {
            val streamFlow: Flow<StreamFrame> = executor.executeStreaming(prompt, model, emptyList())
            streamFlow.collect { frame ->
                when (frame) {
                    is StreamFrame.TextDelta -> {
                        textAccumulator.append(frame.text)
                        streamingManager.onStreamingTextDelta(frame.text, turnId = turnId)
                    }
                    is StreamFrame.ReasoningDelta -> {
                        frame.text?.let { streamingManager.onStreamingReasoningDelta(it, turnId = turnId) }
                    }
                    is StreamFrame.End -> {
                        streamCompleted = true
                        streamingManager.onStreamingEnd(turnId = turnId)
                        frame.metaInfo.let { meta ->
                            val p = meta.inputTokensCount ?: 0
                            val c = meta.outputTokensCount ?: 0
                            usage = StreamingManager.TokenUsage(p, c, p + c)
                            streamingManager.onLlmCallCompleted(p, c, p + c, turnId = turnId)
                        }
                    }
                    else -> {}
                }
            }
        } catch (e: IncompleteStreamException) {
            Log.w(TAG, "Stream completed without End frame: ${e.message}. Executing non-streaming synthesis fallback.")
            return fallbackNonStreamingSynthesis(
                prompt = prompt,
                model = model,
                executor = executor,
                streamingManager = streamingManager,
                turnId = turnId
            )
        }

        val cleaned = ReasoningTokenSanitizer.stripReasoning(textAccumulator.toString())
        if (!streamCompleted && cleaned.isBlank()) {
            Log.w(TAG, "Stream ended without completion frame and produced blank clinical text. Executing non-streaming synthesis fallback.")
            return fallbackNonStreamingSynthesis(
                prompt = prompt,
                model = model,
                executor = executor,
                streamingManager = streamingManager,
                turnId = turnId
            )
        }

        if (!streamCompleted) {
            streamingManager.onStreamingEnd(turnId = turnId)
        }

        return cleaned to usage
    }

    private suspend fun fallbackNonStreamingSynthesis(
        prompt: Prompt,
        model: LLModel,
        executor: PromptExecutor,
        streamingManager: StreamingManager,
        turnId: String? = null
    ): Pair<String, StreamingManager.TokenUsage> {
        streamingManager.onSynthesisStarting(turnId = turnId)
        val assistantResponse: Message.Assistant = executor.execute(prompt, model, emptyList())
        val rawContent = assistantResponse.textContent()
        val meta = assistantResponse.metaInfo
        val p = meta.inputTokensCount ?: 0
        val c = meta.outputTokensCount ?: 0
        val fallbackUsage = StreamingManager.TokenUsage(p, c, p + c)

        if (rawContent.isNotBlank()) {
            streamingManager.onStreamingTextDelta(rawContent, turnId = turnId)
        }
        streamingManager.onStreamingEnd(turnId = turnId)
        streamingManager.onLlmCallCompleted(p, c, p + c, turnId = turnId)

        val cleaned = ReasoningTokenSanitizer.stripReasoning(rawContent)
        return cleaned to fallbackUsage
    }

    private data class ToolExecutionOutcome(
        val resultJson: String,
        val canonicalTool: String,
        val didPrefetch: Boolean = false,
        val needsDrugMonograph: Boolean = false,
        val satisfiedDrugMonograph: Boolean = false,
        val needsRelatedTopic: Boolean = false,
        val dominantRelatedTopicId: String? = null,
        val inspectedTopicIds: Set<String> = emptySet(),
        val hasError: Boolean = false,
        val isEmptyResult: Boolean = false,
        val isEvidenceProducer: Boolean = false
    )

    private data class ChainedCandidateResult(
        val candidateId: String,
        val isTable: Boolean,
        val isDrug: Boolean,
        val isCalc: Boolean,
        val deferredCalls: List<Triple<String, String, String>>,
        val didPrefetch: Boolean,
        val isEvidenceProducer: Boolean,
        val needsDrug: Boolean,
        val needsRelatedTopic: Boolean = false,
        val dominantRelatedTopic: RankedItem? = null,
        val previewSectionsMarkdown: String? = null,
        val previewTablesMarkdown: String? = null
    )

    private fun buildPrefetchedPreview(
        sectionsMarkdown: String?,
        tablesMarkdown: String?,
        needsDrugMonograph: Boolean
    ): String? = if (needsDrugMonograph) {
        (sectionsMarkdown?.takeIf { it.isNotBlank() } ?: tablesMarkdown)?.take(2500)?.trim()?.ifBlank { null }
    } else {
        DecisionGates.buildPrefetchedDigest(sectionsMarkdown, tablesMarkdown)
    }

    private class TurnPrefetchBudget(private val config: DecisionConfig) {
        private val lock = Any()
        private val prefetchedIds = mutableSetOf<String>()

        fun tryAllocate(id: String): Boolean = synchronized(lock) {
            if (id in prefetchedIds) return true
            if (prefetchedIds.size >= config.maxPrefetchedTopicsPerTurn) return false
            prefetchedIds.add(id)
            true
        }

        fun release(id: String) = synchronized(lock) {
            prefetchedIds.remove(id)
        }
    }

    private class TurnToolCache {
        val searchCache = java.util.concurrent.ConcurrentHashMap<String, ToolExecutionOutcome>()
        val overviewCache = java.util.concurrent.ConcurrentHashMap<String, ToolExecutionOutcome>()
        val graphicCache = java.util.concurrent.ConcurrentHashMap<String, ToolExecutionOutcome>()
        val sectionCache = java.util.concurrent.ConcurrentHashMap<String, ToolExecutionOutcome>()
        val drugTopicIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        val tableDataTopicIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    private suspend fun executeSingleTool(
        toolCall: MessagePart.Tool.Call,
        callId: String,
        accumulator: TurnContextAccumulator,
        streamingManager: StreamingManager,
        decisionClient: DecisionClient? = null,
        decisionConfig: DecisionConfig? = null,
        onDecisionTokens: (Int) -> Unit = {},
        turnId: String? = null,
        cache: TurnToolCache = TurnToolCache(),
        prefetchBudget: TurnPrefetchBudget? = null
    ): ToolExecutionOutcome {
        val canonicalName = AiJsonUtils.normalizeToolName(toolCall.tool)
        val argsStr = toolCall.args.ifBlank { toolCall.argsJson.toString() }
        val cleanArgs = AiJsonUtils.extractJsonString(argsStr).ifBlank { argsStr }

        accumulator.onToolCallStarting(callId, cleanArgs)
        streamingManager.onToolCallStarting(canonicalName, cleanArgs, callId, turnId = turnId)

        var deferredPrefetchCalls = emptyList<Triple<String, String, String>>() // toolName, argsJson, resultJson
        var didPrefetch = false
        var needsDrugMonograph = false
        var satisfiedDrugMonograph = false
        var needsRelatedTopic = false
        var dominantRelatedTopicId: String? = null
        var inspectedTopicIds = emptySet<String>()
        var hasError = false
        var isEmptyResult = false
        var isEvidenceProducer = false

        val resultJson = try {
            when (canonicalName) {
                "searchTopics" -> {
                    val query = extractQueryArg(cleanArgs)
                    val cached = cache.searchCache[query]
                    if (cached != null) {
                        hasError = cached.hasError
                        isEmptyResult = cached.isEmptyResult
                        didPrefetch = cached.didPrefetch
                        isEvidenceProducer = cached.isEvidenceProducer
                        needsDrugMonograph = cached.needsDrugMonograph
                        satisfiedDrugMonograph = cached.satisfiedDrugMonograph
                        needsRelatedTopic = cached.needsRelatedTopic
                        dominantRelatedTopicId = cached.dominantRelatedTopicId
                        inspectedTopicIds = cached.inspectedTopicIds
                        cached.resultJson
                    } else {
                        val rerankSearchActive = decisionClient != null && decisionConfig?.enableRetrievalRerank == true
                        val resp = if (rerankSearchActive) {
                            medicalDatabaseTools.searchTopics(query, RERANK_SEARCH_POOL_SIZE).let { pooled ->
                                if (pooled.results.isEmpty() && pooled.error.isNullOrBlank() && pooled.message.isBlank()) {
                                    medicalDatabaseTools.searchTopics(query)
                                } else {
                                    pooled
                                }
                            }
                        } else {
                            medicalDatabaseTools.searchTopics(query)
                        }
                        hasError = !resp.error.isNullOrBlank()
                        isEmptyResult = resp.results.isEmpty()

                        val finalJson = if (resp.error.isNullOrBlank() &&
                            rerankSearchActive &&
                            resp.results.isNotEmpty()
                        ) {
                            val rerank = DecisionGates.rankSearchCandidates(
                                client = decisionClient,
                                question = accumulator.question,
                                patientProfileText = accumulator.getPatientProfile()?.toSystemBlock().orEmpty(),
                                query = query,
                                candidates = resp.results,
                                config = decisionConfig
                            )
                            onDecisionTokens(rerank.usage.totalInputTokens)
                            if (rerank.requiresTableData) {
                                resp.results.forEach { cache.tableDataTopicIds.add(it.id) }
                            }

                            val rerankedResults = if (rerank.ok && rerank.rankedTopics.isNotEmpty()) {
                                val byId = resp.results.associateBy { it.id }
                                val ordered = rerank.rankedTopics.mapNotNull { byId[it.id] }
                                val orderedIds = ordered.mapTo(HashSet()) { it.id }
                                val remainder = resp.results.filter { it.id !in orderedIds }
                                (ordered + remainder).take(DEFAULT_SEARCH_RESULTS)
                            } else {
                                resp.results.take(DEFAULT_SEARCH_RESULTS)
                            }
                            val rawJson = json.encodeToString(resp.copy(results = rerankedResults))

                            // Check for high-confidence Round 1 chaining using 3-tier rule
                            val top1 = rerank.topTopics.getOrNull(0)
                            val top2 = rerank.topTopics.getOrNull(1)
                            val top3 = rerank.topTopics.getOrNull(2)

                            val score1 = top1?.score ?: 0.0
                            val score2 = top2?.score ?: 0.0
                            val score3 = top3?.score ?: 0.0

                            // Tier 1: Clear single winner
                            val isTier1SingleWinner = top1 != null && score1 >= 2.5 && (
                                top2 == null || score2 < 2.2 || (score1 - score2) >= 0.5
                            )

                            // Tier 2: 2 complementary co-winners (e.g. Diagnosis + Treatment or Medical + Drug)
                            val isTier2CoWinners = !isTier1SingleWinner && top1 != null && top2 != null &&
                                score1 >= 2.5 && score2 >= 2.3 && (
                                    top3 == null || score3 < 2.2 || (score2 - score3) >= 0.3
                                )

                            val candidatesToChain = when {
                                isTier1SingleWinner -> listOfNotNull(top1)
                                isTier2CoWinners -> listOfNotNull(top1, top2)
                                else -> emptyList()
                            }

                            val alreadyChainedIds = candidatesToChain.map { it.id }.toSet()

                            if (candidatesToChain.isNotEmpty() && decisionConfig.autoPrefetch) {
                                val chainResults = coroutineScope {
                                    candidatesToChain.map { cand ->
                                        async {
                                            chainSingleCandidate(
                                                cand = cand,
                                                rerank = rerank,
                                                accumulator = accumulator,
                                                cache = cache,
                                                decisionClient = decisionClient,
                                                decisionConfig = decisionConfig,
                                                onDecisionTokens = onDecisionTokens,
                                                alreadyChainedIds = alreadyChainedIds,
                                                prefetchBudget = prefetchBudget
                                            )
                                        }
                                    }.awaitAll()
                                }

                                val allPrefetched = chainResults.isNotEmpty() && chainResults.all { it.didPrefetch }
                                val anyDrugSatisfied = chainResults.any { it.isDrug && it.didPrefetch }
                                val anyNeedsDrug = chainResults.any { it.needsDrug }
                                val effectiveDrugNeeded = if (anyDrugSatisfied) false else anyNeedsDrug
                                val anyNeedsRelatedTopic = chainResults.any { it.needsRelatedTopic }
                                val prefetchedIds = chainResults.filter { it.didPrefetch }.map { it.candidateId }
                                val dominantRelated = chainResults.firstOrNull { it.needsRelatedTopic }?.dominantRelatedTopic
                                val promisingRelatedSummary = dominantRelated?.let {
                                    "${it.id} (${it.title})"
                                }

                                deferredPrefetchCalls = chainResults.flatMap { it.deferredCalls }
                                didPrefetch = allPrefetched
                                isEvidenceProducer = chainResults.any { it.isEvidenceProducer }
                                needsDrugMonograph = effectiveDrugNeeded
                                satisfiedDrugMonograph = anyDrugSatisfied
                                needsRelatedTopic = anyNeedsRelatedTopic
                                dominantRelatedTopicId = dominantRelated?.id
                                inspectedTopicIds = chainResults.filter { !it.isTable }.map { it.candidateId }.toSet()

                                val combinedPreview = if (prefetchedIds.isNotEmpty()) {
                                    val allSecMd = chainResults.mapNotNull { it.previewSectionsMarkdown?.trim() }
                                        .filter { it.isNotBlank() }
                                        .joinToString("\n\n")
                                        .ifBlank { null }
                                    val allTblMd = chainResults.mapNotNull { it.previewTablesMarkdown?.trim() }
                                        .filter { it.isNotBlank() }
                                        .joinToString("\n\n")
                                        .ifBlank { null }
                                    buildPrefetchedPreview(allSecMd, allTblMd, effectiveDrugNeeded)
                                } else null

                                DecisionGates.injectSearchRerankIntoJson(
                                    toolJson = rawJson,
                                    rerank = rerank,
                                    json = json,
                                    autoChainedTopicId = candidatesToChain.first().id,
                                    autoChainedTopicIds = candidatesToChain.map { it.id },
                                    autoPrefetchedTopicIds = prefetchedIds,
                                    autoPrefetched = allPrefetched,
                                    needsDrugMonograph = effectiveDrugNeeded,
                                    needsRelatedTopic = anyNeedsRelatedTopic,
                                    promisingRelatedSummary = promisingRelatedSummary,
                                    prefetchedPreview = combinedPreview
                                )
                            } else {
                                needsDrugMonograph = rerank.requiresDrugMonograph
                                DecisionGates.injectSearchRerankIntoJson(rawJson, rerank, json)
                            }
                        } else {
                            json.encodeToString(
                                if (resp.results.size > DEFAULT_SEARCH_RESULTS) {
                                    resp.copy(results = resp.results.take(DEFAULT_SEARCH_RESULTS))
                                } else {
                                    resp
                                }
                            )
                        }
                        if (resp.error.isNullOrBlank()) {
                            cache.searchCache[query] = ToolExecutionOutcome(
                                resultJson = finalJson,
                                canonicalTool = "searchTopics",
                                didPrefetch = didPrefetch,
                                needsDrugMonograph = needsDrugMonograph,
                                satisfiedDrugMonograph = satisfiedDrugMonograph,
                                needsRelatedTopic = needsRelatedTopic,
                                dominantRelatedTopicId = dominantRelatedTopicId,
                                inspectedTopicIds = inspectedTopicIds,
                                hasError = hasError,
                                isEmptyResult = isEmptyResult,
                                isEvidenceProducer = isEvidenceProducer
                            )
                        }
                        finalJson
                    }
                }
                "getTopicOverview" -> {
                    val topicId = extractTopicIdArg(cleanArgs)
                    val cached = cache.overviewCache[topicId]
                    if (cached != null) {
                        hasError = cached.hasError
                        isEmptyResult = cached.isEmptyResult
                        didPrefetch = cached.didPrefetch
                        isEvidenceProducer = cached.isEvidenceProducer
                        needsDrugMonograph = cached.needsDrugMonograph
                        satisfiedDrugMonograph = cached.satisfiedDrugMonograph
                        needsRelatedTopic = cached.needsRelatedTopic
                        dominantRelatedTopicId = cached.dominantRelatedTopicId
                        inspectedTopicIds = cached.inspectedTopicIds
                        cached.resultJson
                    } else {
                        val resp = medicalDatabaseTools.getTopicOverview(topicId)
                        val rawJson = json.encodeToString(resp)
                        hasError = !resp.error.isNullOrBlank()
                        if (!hasError) {
                            inspectedTopicIds = setOf(topicId)
                            if (resp.topicType.equals("drug", ignoreCase = true)) {
                                cache.drugTopicIds.add(topicId)
                            }
                        }

                        val finalJson = if (resp.error.isNullOrBlank() &&
                            decisionClient != null &&
                            decisionConfig?.enableRetrievalRerank == true
                        ) {
                            val processed = rerankAndPrefetchOverview(
                                topicId = topicId,
                                resp = resp,
                                rawJson = rawJson,
                                decisionClient = decisionClient,
                                decisionConfig = decisionConfig,
                                accumulator = accumulator,
                                cache = cache,
                                onDecisionTokens = onDecisionTokens,
                                prefetchBudget = prefetchBudget,
                                searchRequiresTableData = topicId in cache.tableDataTopicIds
                            )
                            deferredPrefetchCalls = processed.prefetch?.recordedCalls.orEmpty()
                            didPrefetch = processed.outcome.didPrefetch
                            isEvidenceProducer = processed.outcome.isEvidenceProducer
                            needsDrugMonograph = processed.outcome.needsDrugMonograph
                            satisfiedDrugMonograph = processed.outcome.satisfiedDrugMonograph
                            needsRelatedTopic = processed.outcome.needsRelatedTopic
                            dominantRelatedTopicId = processed.outcome.dominantRelatedTopicId
                            cache.overviewCache[topicId] = processed.outcome
                            processed.outcome.resultJson
                        } else {
                            val isCalc = resp.topicType.equals("calc", ignoreCase = true)
                            if (isCalc && decisionClient != null && decisionConfig?.autoPrefetch == true) {
                                didPrefetch = true
                                isEvidenceProducer = true
                            }
                            if (resp.error.isNullOrBlank()) {
                                cache.overviewCache[topicId] = ToolExecutionOutcome(
                                    resultJson = rawJson,
                                    canonicalTool = "getTopicOverview",
                                    didPrefetch = didPrefetch,
                                    needsDrugMonograph = needsDrugMonograph,
                                    satisfiedDrugMonograph = satisfiedDrugMonograph,
                                    needsRelatedTopic = needsRelatedTopic,
                                    dominantRelatedTopicId = dominantRelatedTopicId,
                                    inspectedTopicIds = inspectedTopicIds,
                                    hasError = hasError,
                                    isEmptyResult = isEmptyResult,
                                    isEvidenceProducer = isEvidenceProducer
                                )
                            }
                            rawJson
                        }
                        finalJson
                    }
                }
                "getTopicSectionsText" -> {
                    val (topicId, sectionIds) = extractSectionsArg(cleanArgs)
                    val cacheKey = "$topicId:${sectionIds.sorted().joinToString(",")}"
                    val cached = cache.sectionCache[cacheKey]
                    if (cached != null) {
                        hasError = cached.hasError
                        isEmptyResult = cached.isEmptyResult
                        isEvidenceProducer = cached.isEvidenceProducer
                        cached.resultJson
                    } else if (sectionIds.isNotEmpty() && sectionIds.all { accumulator.hasFetchedSection(topicId, it) }) {
                        val fetchedForTopic = accumulator.getFetchedSections().filter { it.topicId == topicId }
                        val matchingSections = sectionIds.mapNotNull { sid ->
                            fetchedForTopic.firstOrNull { it.sectionId == sid || it.sectionId.equals("FULL", ignoreCase = true) }
                        }
                        if (matchingSections.size == sectionIds.size) {
                            val md = matchingSections.joinToString("\n\n") { "=== Section: ${it.sectionId} ===\n${it.contentSnippet}" }
                            val titles = matchingSections.associate { it.sectionId to it.sectionTitle }
                            val resp = TopicSectionsResponse(
                                topicId = topicId,
                                topicTitle = matchingSections.firstOrNull()?.topicTitle.orEmpty(),
                                sectionTitles = titles,
                                markdown = md
                            )
                            val encoded = json.encodeToString(resp)
                            hasError = false
                            isEmptyResult = md.isBlank()
                            isEvidenceProducer = md.isNotBlank()
                            cache.sectionCache[cacheKey] = ToolExecutionOutcome(
                                resultJson = encoded,
                                canonicalTool = "getTopicSectionsText",
                                didPrefetch = false,
                                hasError = false,
                                isEmptyResult = isEmptyResult,
                                isEvidenceProducer = isEvidenceProducer
                            )
                            encoded
                        } else {
                            val resp = medicalDatabaseTools.getTopicSectionsText(topicId, sectionIds)
                            val encoded = json.encodeToString(resp)
                            hasError = !resp.error.isNullOrBlank()
                            isEmptyResult = resp.markdown.isBlank()
                            isEvidenceProducer = resp.markdown.isNotBlank()
                            if (resp.error.isNullOrBlank()) {
                                cache.sectionCache[cacheKey] = ToolExecutionOutcome(
                                    resultJson = encoded,
                                    canonicalTool = "getTopicSectionsText",
                                    didPrefetch = false,
                                    hasError = hasError,
                                    isEmptyResult = isEmptyResult,
                                    isEvidenceProducer = isEvidenceProducer
                                )
                            }
                            encoded
                        }
                    } else {
                        val resp = medicalDatabaseTools.getTopicSectionsText(topicId, sectionIds)
                        val encoded = json.encodeToString(resp)
                        hasError = !resp.error.isNullOrBlank()
                        isEmptyResult = resp.markdown.isBlank()
                        isEvidenceProducer = resp.markdown.isNotBlank()
                        if (resp.error.isNullOrBlank()) {
                            cache.sectionCache[cacheKey] = ToolExecutionOutcome(
                                resultJson = encoded,
                                canonicalTool = "getTopicSectionsText",
                                didPrefetch = false,
                                hasError = hasError,
                                isEmptyResult = isEmptyResult,
                                isEvidenceProducer = isEvidenceProducer
                            )
                        }
                        encoded
                    }
                }
                "getGraphicContent" -> {
                    val graphicId = extractGraphicIdArg(cleanArgs)
                    val cleanGid = AiJsonUtils.cleanGraphicId(graphicId)
                    val cached = cache.graphicCache[cleanGid]
                    if (cached != null) {
                        hasError = cached.hasError
                        isEmptyResult = cached.isEmptyResult
                        isEvidenceProducer = cached.isEvidenceProducer
                        cached.resultJson
                    } else if (accumulator.hasFetchedGraphic(cleanGid)) {
                        val matchingGraphic = accumulator.getFetchedGraphics().firstOrNull { it.graphicId == cleanGid }
                        if (matchingGraphic != null && matchingGraphic.markdown.isNotBlank()) {
                            val resp = GraphicContentResponse(
                                graphicId = cleanGid,
                                title = matchingGraphic.title,
                                markdown = matchingGraphic.markdown
                            )
                            val encoded = json.encodeToString(resp)
                            hasError = false
                            isEmptyResult = false
                            isEvidenceProducer = true
                            cache.graphicCache[cleanGid] = ToolExecutionOutcome(
                                resultJson = encoded,
                                canonicalTool = "getGraphicContent",
                                didPrefetch = false,
                                hasError = false,
                                isEmptyResult = false,
                                isEvidenceProducer = true
                            )
                            encoded
                        } else {
                            val resp = medicalDatabaseTools.getGraphicContent(graphicId)
                            val encoded = json.encodeToString(resp)
                            hasError = !resp.error.isNullOrBlank()
                            isEmptyResult = resp.markdown.isBlank()
                            isEvidenceProducer = resp.markdown.isNotBlank()
                            if (resp.error.isNullOrBlank()) {
                                cache.graphicCache[cleanGid] = ToolExecutionOutcome(
                                    resultJson = encoded,
                                    canonicalTool = "getGraphicContent",
                                    didPrefetch = false,
                                    hasError = hasError,
                                    isEmptyResult = isEmptyResult,
                                    isEvidenceProducer = isEvidenceProducer
                                )
                            }
                            encoded
                        }
                    } else {
                        val resp = medicalDatabaseTools.getGraphicContent(graphicId)
                        val encoded = json.encodeToString(resp)
                        hasError = !resp.error.isNullOrBlank()
                        isEmptyResult = resp.markdown.isBlank()
                        isEvidenceProducer = resp.markdown.isNotBlank()
                        if (resp.error.isNullOrBlank()) {
                            cache.graphicCache[cleanGid] = ToolExecutionOutcome(
                                resultJson = encoded,
                                canonicalTool = "getGraphicContent",
                                didPrefetch = false,
                                hasError = hasError,
                                isEmptyResult = isEmptyResult,
                                isEvidenceProducer = isEvidenceProducer
                            )
                        }
                        encoded
                    }
                }
                else -> {
                    hasError = true
                    """{"error":"Unknown tool: ${toolCall.tool}"}"""
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error for ${toolCall.tool}: ${e.message}")
            hasError = true
            """{"error":"Execution failed: ${e.message}"}"""
        }

        accumulator.onToolCallCompleted(callId, canonicalName, resultJson, success = !hasError, toolArgs = cleanArgs)
        streamingManager.onToolCallCompleted(canonicalName, resultJson, callId, turnId = turnId)

        // Record prefetched sections/tables AFTER getTopicOverview so topic/section/graphic metadata is indexed first.
        deferredPrefetchCalls.forEachIndexed { idx, (prefetchTool, prefetchArgs, prefetchResult) ->
            val prefetchCallId = if (callId.isNotBlank()) {
                "${callId}_prefetch_$idx"
            } else {
                "prefetch_${kotlin.random.Random.nextLong().toString(16)}_$idx"
            }
            accumulator.onToolCallStarting(prefetchCallId, prefetchArgs)
            streamingManager.onToolCallStarting(prefetchTool, prefetchArgs, prefetchCallId, turnId = turnId)
            accumulator.onToolCallCompleted(
                toolCallId = prefetchCallId,
                toolName = prefetchTool,
                result = prefetchResult,
                success = true,
                toolArgs = prefetchArgs
            )
            streamingManager.onToolCallCompleted(prefetchTool, prefetchResult, prefetchCallId, turnId = turnId)
        }

        streamingManager.onWaitingForLlm(turnId = turnId)

        if (canonicalName == "getTopicSectionsText" && !hasError && isEvidenceProducer) {
            val tid = extractSectionsArg(cleanArgs).first
            if (tid in cache.drugTopicIds) {
                satisfiedDrugMonograph = true
            }
        }

        return ToolExecutionOutcome(
            resultJson = resultJson,
            canonicalTool = canonicalName,
            didPrefetch = didPrefetch,
            needsDrugMonograph = needsDrugMonograph,
            satisfiedDrugMonograph = satisfiedDrugMonograph,
            needsRelatedTopic = needsRelatedTopic,
            dominantRelatedTopicId = dominantRelatedTopicId,
            inspectedTopicIds = inspectedTopicIds,
            hasError = hasError,
            isEmptyResult = isEmptyResult,
            isEvidenceProducer = isEvidenceProducer
        )
    }

    private suspend fun chainSingleCandidate(
        cand: RankedItem,
        rerank: SearchRerankResult,
        accumulator: TurnContextAccumulator,
        cache: TurnToolCache,
        decisionClient: DecisionClient,
        decisionConfig: DecisionConfig,
        onDecisionTokens: (Int) -> Unit,
        alreadyChainedIds: Set<String> = emptySet(),
        prefetchBudget: TurnPrefetchBudget? = null
    ): ChainedCandidateResult {
        val candId = cand.id
        val isTable = cand.kind.equals("table", ignoreCase = true)

        if (isTable) {
            val canPrefetchTable = prefetchBudget?.tryAllocate(candId) ?: true
            if (!canPrefetchTable) {
                return ChainedCandidateResult(
                    candidateId = candId,
                    isTable = true,
                    isDrug = false,
                    isCalc = false,
                    deferredCalls = emptyList(),
                    didPrefetch = false,
                    isEvidenceProducer = false,
                    needsDrug = rerank.requiresDrugMonograph
                )
            }
            val gfxResp = medicalDatabaseTools.getGraphicContent(candId)
            val encoded = json.encodeToString(gfxResp)
            return if (gfxResp.error.isNullOrBlank() && gfxResp.markdown.isNotBlank()) {
                val cleanGid = AiJsonUtils.cleanGraphicId(candId)
                cache.graphicCache[cleanGid] = ToolExecutionOutcome(
                    resultJson = encoded,
                    canonicalTool = "getGraphicContent",
                    didPrefetch = false,
                    hasError = false,
                    isEmptyResult = false,
                    isEvidenceProducer = true
                )
                accumulator.recordGraphicScore(candId, cand.score)
                val gfxArgs = json.encodeToString(buildJsonObject { put("graphicId", candId) })
                ChainedCandidateResult(
                    candidateId = candId,
                    isTable = true,
                    isDrug = false,
                    isCalc = false,
                    deferredCalls = listOf(Triple("getGraphicContent", gfxArgs, encoded)),
                    didPrefetch = true,
                    isEvidenceProducer = true,
                    needsDrug = rerank.requiresDrugMonograph,
                    previewTablesMarkdown = gfxResp.markdown
                )
            } else {
                prefetchBudget?.release(candId)
                ChainedCandidateResult(
                    candidateId = candId,
                    isTable = true,
                    isDrug = false,
                    isCalc = false,
                    deferredCalls = emptyList(),
                    didPrefetch = false,
                    isEvidenceProducer = false,
                    needsDrug = rerank.requiresDrugMonograph
                )
            }
        } else {
            val overviewResp = medicalDatabaseTools.getTopicOverview(candId)
            val rawOverviewJson = json.encodeToString(overviewResp)
            return if (overviewResp.error.isNullOrBlank()) {
                val processed = rerankAndPrefetchOverview(
                    topicId = candId,
                    resp = overviewResp,
                    rawJson = rawOverviewJson,
                    decisionClient = decisionClient,
                    decisionConfig = decisionConfig,
                    accumulator = accumulator,
                    cache = cache,
                    onDecisionTokens = onDecisionTokens,
                    prefetchBudget = prefetchBudget,
                    searchRequiresTableData = rerank.requiresTableData,
                    alreadyChainedIds = alreadyChainedIds,
                    calcScoreOverride = cand.score.takeIf { it > 0.0 },
                    fallbackDrugNeeded = rerank.requiresDrugMonograph
                )
                cache.overviewCache[candId] = processed.outcome

                val chainedCalls = mutableListOf<Triple<String, String, String>>()
                val overviewArgs = json.encodeToString(
                    buildJsonObject { put("topicId", candId) }
                )
                chainedCalls.add(Triple("getTopicOverview", overviewArgs, processed.outcome.resultJson))
                if (processed.prefetch != null) {
                    chainedCalls.addAll(processed.prefetch.recordedCalls)
                }

                ChainedCandidateResult(
                    candidateId = candId,
                    isTable = false,
                    isDrug = processed.isDrug,
                    isCalc = processed.isCalc,
                    deferredCalls = chainedCalls,
                    didPrefetch = processed.outcome.didPrefetch,
                    isEvidenceProducer = processed.outcome.isEvidenceProducer,
                    needsDrug = processed.outcome.needsDrugMonograph,
                    needsRelatedTopic = processed.outcome.needsRelatedTopic,
                    dominantRelatedTopic = processed.dominantRelatedTopic,
                    previewSectionsMarkdown = processed.prefetch?.sectionsMarkdown,
                    previewTablesMarkdown = processed.prefetch?.tablesMarkdown
                )
            } else {
                ChainedCandidateResult(
                    candidateId = candId,
                    isTable = false,
                    isDrug = false,
                    isCalc = false,
                    deferredCalls = emptyList(),
                    didPrefetch = false,
                    isEvidenceProducer = false,
                    needsDrug = rerank.requiresDrugMonograph
                )
            }
        }
    }

    private data class OverviewProcessingResult(
        val outcome: ToolExecutionOutcome,
        val prefetch: AutoPrefetchOutcome?,
        val dominantRelatedTopic: RankedItem?,
        val isDrug: Boolean,
        val isCalc: Boolean
    )

    private suspend fun rerankAndPrefetchOverview(
        topicId: String,
        resp: com.clinref.app.data.TopicOverviewResponse,
        rawJson: String,
        decisionClient: DecisionClient,
        decisionConfig: DecisionConfig,
        accumulator: TurnContextAccumulator,
        cache: TurnToolCache,
        onDecisionTokens: (Int) -> Unit,
        prefetchBudget: TurnPrefetchBudget?,
        searchRequiresTableData: Boolean,
        alreadyChainedIds: Set<String> = emptySet(),
        calcScoreOverride: Double? = null,
        fallbackDrugNeeded: Boolean = false
    ): OverviewProcessingResult {
        val isCalc = resp.topicType.equals("calc", ignoreCase = true)
        val isDrug = resp.topicType.equals("drug", ignoreCase = true)
        if (isDrug) {
            cache.drugTopicIds.add(topicId)
        }
        val rerank = DecisionGates.rankOverviewItems(
            client = decisionClient,
            question = accumulator.question,
            patientProfileText = accumulator.getPatientProfile()?.toSystemBlock().orEmpty(),
            overview = resp,
            config = decisionConfig,
            searchRequiresTableData = searchRequiresTableData
        )
        onDecisionTokens(rerank.usage.totalInputTokens)
        rerank.rankedSections.forEach { sec ->
            val finalScore = if (isCalc && sec.id == "FULL" && calcScoreOverride != null) calcScoreOverride else sec.score
            accumulator.recordSectionScore(topicId, sec.id, finalScore)
        }
        rerank.rankedGraphics.forEach { gfx ->
            accumulator.recordGraphicScore(gfx.id, gfx.score)
        }
        val uninspectedRelated = rerank.recommendedRelatedTopics.firstOrNull { rel ->
            rel.id != topicId &&
            rel.id !in alreadyChainedIds &&
            !accumulator.hasInspectedTopic(rel.id) &&
            !cache.overviewCache.containsKey(rel.id)
        }
        val bestSectionScore = rerank.rankedSections.firstOrNull()?.score ?: 0.0
        val effectiveRelatedNeeded = uninspectedRelated != null &&
            uninspectedRelated.score >= 2.6 &&
            (uninspectedRelated.score > bestSectionScore || uninspectedRelated.score >= 2.8)
        if (effectiveRelatedNeeded && searchRequiresTableData) {
            cache.tableDataTopicIds.add(uninspectedRelated.id)
        }

        val allowAutoPrefetch = rerank.ok && decisionConfig.autoPrefetch && !isCalc &&
            (prefetchBudget?.tryAllocate(topicId) ?: true)
        val prefetch = if (allowAutoPrefetch) {
            executeAutoPrefetch(
                topicId = topicId,
                rerank = rerank,
                accumulator = accumulator,
                cache = cache
            ).also { outcome ->
                if (!outcome.didPrefetch) {
                    prefetchBudget?.release(topicId)
                }
            }
        } else null

        val actuallyPrefetched = (prefetch?.didPrefetch == true) || isCalc
        val effectiveDrugNeeded = when {
            isDrug -> false
            rerank.ok -> rerank.needsDrugMonograph
            else -> fallbackDrugNeeded
        }
        val previewExcerpt = if (actuallyPrefetched) {
            buildPrefetchedPreview(prefetch?.sectionsMarkdown, prefetch?.tablesMarkdown, effectiveDrugNeeded)
        } else null

        val enrichedJson = DecisionGates.injectOverviewRerankIntoJson(
            toolJson = rawJson,
            rerank = rerank,
            autoPrefetched = actuallyPrefetched,
            prefetchedSectionsMarkdown = null,
            prefetchedTablesMarkdown = null,
            prefetchedSectionsChars = prefetch?.sectionsMarkdown?.length ?: 0,
            prefetchedTablesChars = prefetch?.tablesMarkdown?.length ?: 0,
            prefetchedPreview = previewExcerpt,
            needsRelatedTopic = effectiveRelatedNeeded,
            json = json
        )
        val dominantRelated = if (effectiveRelatedNeeded) uninspectedRelated else null
        val outcome = ToolExecutionOutcome(
            resultJson = enrichedJson,
            canonicalTool = "getTopicOverview",
            didPrefetch = actuallyPrefetched,
            needsDrugMonograph = effectiveDrugNeeded,
            satisfiedDrugMonograph = isDrug && actuallyPrefetched,
            needsRelatedTopic = effectiveRelatedNeeded,
            dominantRelatedTopicId = dominantRelated?.id,
            inspectedTopicIds = setOf(topicId),
            hasError = false,
            isEmptyResult = false,
            isEvidenceProducer = actuallyPrefetched
        )
        return OverviewProcessingResult(
            outcome = outcome,
            prefetch = prefetch,
            dominantRelatedTopic = dominantRelated,
            isDrug = isDrug,
            isCalc = isCalc
        )
    }

    private data class AutoPrefetchOutcome(
        val didPrefetch: Boolean,
        val sectionsMarkdown: String? = null,
        val tablesMarkdown: String? = null,
        val recordedCalls: List<Triple<String, String, String>> = emptyList()
    )

    private suspend fun executeAutoPrefetch(
        topicId: String,
        rerank: OverviewRerankResult,
        accumulator: TurnContextAccumulator,
        cache: TurnToolCache? = null
    ): AutoPrefetchOutcome {
        val recSecIds = rerank.recommendedSectionIds.filter { sid ->
            sid.isNotBlank() && !accumulator.hasFetchedSection(topicId, sid)
        }
        val recGfxIds = rerank.recommendedGraphicIds.filter { gid ->
            gid.isNotBlank() && !accumulator.hasFetchedGraphic(gid)
        }
        if (recSecIds.isEmpty() && recGfxIds.isEmpty()) {
            return AutoPrefetchOutcome(didPrefetch = false)
        }

        val recorded = mutableListOf<Triple<String, String, String>>()
        var sectionsMd: String? = null
        var tablesMd: String? = null

        coroutineScope {
            val secDeferred = if (recSecIds.isNotEmpty()) {
                async {
                    val args = buildPrefetchSectionsArgs(topicId, recSecIds)
                    val resp = medicalDatabaseTools.getTopicSectionsText(topicId, recSecIds)
                    val encoded = json.encodeToString(resp)
                    args to (resp to encoded)
                }
            } else null

            val gfxDeferreds = recGfxIds.map { gid ->
                async {
                    val args = buildPrefetchGraphicArgs(gid)
                    val resp = medicalDatabaseTools.getGraphicContent(gid)
                    val encoded = json.encodeToString(resp)
                    args to (resp to encoded)
                }
            }

            secDeferred?.await()?.let { (args, pair) ->
                val (resp, encoded) = pair
                recorded.add(Triple("getTopicSectionsText", args, encoded))
                if (resp.error.isNullOrBlank() && resp.markdown.isNotBlank()) {
                    sectionsMd = resp.markdown
                    if (cache != null) {
                        val cacheKey = "$topicId:${recSecIds.sorted().joinToString(",")}"
                        cache.sectionCache[cacheKey] = ToolExecutionOutcome(
                            resultJson = encoded,
                            canonicalTool = "getTopicSectionsText",
                            didPrefetch = false,
                            hasError = false,
                            isEmptyResult = false,
                            isEvidenceProducer = true
                        )
                    }
                }
            }

            val gfxResults = gfxDeferreds.awaitAll()
            val tableBlocks = mutableListOf<String>()
            gfxResults.forEach { (args, pair) ->
                val (resp, encoded) = pair
                recorded.add(Triple("getGraphicContent", args, encoded))
                if (resp.error.isNullOrBlank() && resp.markdown.isNotBlank()) {
                    tableBlocks.add(resp.markdown.trim())
                    if (cache != null) {
                        val cleanGid = AiJsonUtils.cleanGraphicId(extractGraphicIdArg(args))
                        if (cleanGid.isNotBlank()) {
                            cache.graphicCache[cleanGid] = ToolExecutionOutcome(
                                resultJson = encoded,
                                canonicalTool = "getGraphicContent",
                                didPrefetch = false,
                                hasError = false,
                                isEmptyResult = false,
                                isEvidenceProducer = true
                            )
                        }
                    }
                }
            }
            if (tableBlocks.isNotEmpty()) {
                tablesMd = tableBlocks.joinToString("\n\n")
            }
        }

        val actuallyLoaded = !sectionsMd.isNullOrBlank() || !tablesMd.isNullOrBlank()
        return AutoPrefetchOutcome(
            didPrefetch = actuallyLoaded,
            sectionsMarkdown = sectionsMd,
            tablesMarkdown = tablesMd,
            recordedCalls = recorded
        )
    }

    private fun buildPrefetchSectionsArgs(topicId: String, sectionIds: List<String>): String =
        json.encodeToString(
            buildJsonObject {
                put("topicId", topicId)
                put("sectionIds", buildJsonArray { sectionIds.forEach { add(JsonPrimitive(it)) } })
            }
        )

    private fun buildPrefetchGraphicArgs(graphicId: String): String =
        json.encodeToString(
            buildJsonObject {
                put("graphicId", graphicId)
            }
        )

    private fun buildPromptWithHistory(
        id: String,
        finalUserContent: String,
        chatHistory: List<MessageEntity>,
        providerParams: LLMParams?,
        isAnthropic: Boolean,
        patientProfile: PatientProfile? = null,
        systemInstruction: String = SystemPrompt.RETRIEVAL_SYSTEM_PROMPT,
        maxHistoryTurns: Int = MAX_CHAT_HISTORY_TURNS,
        truncateAssistantHistoryChars: Int? = null,
        includePatientInSystem: Boolean = true
    ): Prompt {
        val cache = if (isAnthropic) AnthropicCacheControl.Default else null
        val patientBlock = if (includePatientInSystem && patientProfile != null) SystemPrompt.patientContextBlock(patientProfile) else ""

        // Pair filtering: only include user messages that are followed by a valid (non-error, non-cancelled) assistant reply.
        // This prevents orphaned user messages from failed turns from causing consecutive user messages in the prompt.
        val validHistory = mutableListOf<MessageEntity>()
        var pendingUser: MessageEntity? = null
        for (msg in chatHistory) {
            when {
                msg.role == "user" && !msg.isError -> {
                    pendingUser = msg
                }
                msg.role == "assistant" && !msg.isError && msg.role != "cancelled" -> {
                    if (pendingUser != null) {
                        validHistory.add(pendingUser)
                        validHistory.add(msg)
                        pendingUser = null
                    }
                }
                else -> {
                    // Encountered an error, cancellation, or unexpected role -> discard pending user message
                    pendingUser = null
                }
            }
        }

        val historyTurns = validHistory.takeLast(maxHistoryTurns)

        return prompt(id = id, params = providerParams ?: LLMParams()) {
            system(systemInstruction, cache = cache)
            if (includePatientInSystem && patientBlock.isNotBlank()) {
                system(patientBlock)
            }
            historyTurns.forEach { msg ->
                if (msg.role == "user") {
                    user(msg.content)
                } else {
                    val content = if (truncateAssistantHistoryChars != null && msg.content.length > truncateAssistantHistoryChars) {
                        msg.content.take(truncateAssistantHistoryChars) + "…"
                    } else {
                        msg.content
                    }
                    assistant(content)
                }
            }
            user(finalUserContent)
        }
    }

    private fun buildInitialRetrievalPrompt(
        question: String,
        patientProfile: PatientProfile,
        chatHistory: List<MessageEntity>,
        providerParams: LLMParams?,
        isAnthropic: Boolean,
        rerankActive: Boolean = false
    ): Prompt = buildPromptWithHistory(
        id = "clinref-retrieval",
        finalUserContent = question,
        chatHistory = chatHistory,
        providerParams = providerParams,
        isAnthropic = isAnthropic,
        patientProfile = patientProfile,
        systemInstruction = if (rerankActive) {
            SystemPrompt.RETRIEVAL_RERANK_SYSTEM_PROMPT
        } else {
            SystemPrompt.RETRIEVAL_SYSTEM_PROMPT
        },
        maxHistoryTurns = 4,
        truncateAssistantHistoryChars = 300
    )

    private fun buildSynthesisPrompt(
        synthesisDirective: String,
        chatHistory: List<MessageEntity>,
        providerParams: LLMParams?,
        isAnthropic: Boolean
    ): Prompt = buildPromptWithHistory(
        id = "clinref-synthesis",
        finalUserContent = synthesisDirective,
        chatHistory = chatHistory,
        providerParams = providerParams,
        isAnthropic = isAnthropic,
        patientProfile = null,
        systemInstruction = SystemPrompt.SYNTHESIS_SYSTEM_PROMPT,
        maxHistoryTurns = 4,
        truncateAssistantHistoryChars = 300,
        includePatientInSystem = false
    )

    private fun extractQueryArg(argsStr: String): String {
        return try {
            val element = json.parseToJsonElement(argsStr)
            if (element is JsonObject) {
                element["query"]?.jsonPrimitive?.contentOrNull
                    ?: element["search_query"]?.jsonPrimitive?.contentOrNull
                    ?: element["searchQuery"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            } else argsStr
        } catch (_: Exception) {
            argsStr
        }
    }

    private fun extractTopicIdArg(argsStr: String): String {
        return try {
            val element = json.parseToJsonElement(argsStr)
            if (element is JsonObject) {
                element["topicId"]?.jsonPrimitive?.contentOrNull
                    ?: element["topic_id"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            } else {
                TOPIC_ID_NUM_REGEX.find(argsStr)?.value ?: ""
            }
        } catch (_: Exception) {
            TOPIC_ID_NUM_REGEX.find(argsStr)?.value ?: ""
        }
    }

    private fun extractSectionsArg(argsStr: String): Pair<String, List<String>> {
        return try {
            val element = json.parseToJsonElement(argsStr) as? JsonObject ?: return "" to emptyList()
            val topicId = (element["topicId"] as? JsonPrimitive)?.contentOrNull
                ?: (element["topic_id"] as? JsonPrimitive)?.contentOrNull
                ?: ""

            fun parseIds(el: kotlinx.serialization.json.JsonElement?): List<String>? = when (el) {
                is kotlinx.serialization.json.JsonArray ->
                    el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.filter { it.isNotEmpty() }
                is JsonPrimitive ->
                    el.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                else -> null
            }

            val sectionIds = parseIds(element["sectionIds"])
                ?: parseIds(element["section_ids"])
                ?: emptyList()

            topicId to sectionIds
        } catch (_: Exception) {
            "" to emptyList()
        }
    }

    private fun extractGraphicIdArg(argsStr: String): String {
        return try {
            val element = json.parseToJsonElement(argsStr)
            if (element is JsonObject) {
                element["graphicId"]?.jsonPrimitive?.contentOrNull
                    ?: element["graphic_id"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            } else argsStr
        } catch (_: Exception) {
            argsStr
        }
    }
}

