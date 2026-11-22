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
import com.clinref.app.data.GraphicContentResponse
import com.clinref.app.data.MedicalDatabaseTools
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
        val toolDescriptors = medicalDatabaseTools.asToolList().map { it.descriptor }
        var currentRetrievalPrompt = buildInitialRetrievalPrompt(
            question = trimmedQuestion,
            patientProfile = patientProfile,
            chatHistory = chatHistory,
            providerParams = providerParams,
            isAnthropic = isAnthropic
        )
        val turnToolCache = TurnToolCache()
        val lastSearchRequiresTableData = java.util.concurrent.atomic.AtomicBoolean(false)

        var round = 0
        var emptyEvidenceRetries = 0
        val maxEmptyEvidenceRetries = 1

        while (round < MAX_RETRIEVAL_ROUNDS) {
            round++
            Log.d(TAG, "Stage 1 Retrieval Round $round/$MAX_RETRIEVAL_ROUNDS")

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
                            searchRequiresTableData = lastSearchRequiresTableData.get(),
                            onTableDataRequired = { if (it) lastSearchRequiresTableData.set(true) }
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

            // Short-circuit retrieval if every tool call resolved auto-prefetched/calc evidence with zero monograph/related-topic requirements
            val allPrefetched = executedResults.all { it.third.didPrefetch }
            val allSucceeded = executedResults.all { !it.third.hasError && !it.third.isEmptyResult }
            val noMonographNeeded = executedResults.none { it.third.needsDrugMonograph }
            val noRelatedTopicNeeded = executedResults.none { it.third.needsRelatedTopic }
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
        val needsRelatedTopic: Boolean = false,
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
        val previewMarkdown: String? = null
    )

    private class TurnToolCache {
        val searchCache = java.util.concurrent.ConcurrentHashMap<String, ToolExecutionOutcome>()
        val overviewCache = java.util.concurrent.ConcurrentHashMap<String, ToolExecutionOutcome>()
        val graphicCache = java.util.concurrent.ConcurrentHashMap<String, ToolExecutionOutcome>()
        val sectionCache = java.util.concurrent.ConcurrentHashMap<String, ToolExecutionOutcome>()
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
        searchRequiresTableData: Boolean = false,
        onTableDataRequired: (Boolean) -> Unit = {}
    ): ToolExecutionOutcome {
        val canonicalName = AiJsonUtils.normalizeToolName(toolCall.tool)
        val argsStr = toolCall.args.ifBlank { toolCall.argsJson.toString() }
        val cleanArgs = AiJsonUtils.extractJsonString(argsStr).ifBlank { argsStr }

        accumulator.onToolCallStarting(callId, cleanArgs)
        streamingManager.onToolCallStarting(canonicalName, cleanArgs, callId, turnId = turnId)

        var deferredPrefetchCalls = emptyList<Triple<String, String, String>>() // toolName, argsJson, resultJson
        var didPrefetch = false
        var needsDrugMonograph = false
        var needsRelatedTopic = false
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
                        needsRelatedTopic = cached.needsRelatedTopic
                        cached.resultJson
                    } else {
                        val resp = medicalDatabaseTools.searchTopics(query)
                        val rawJson = json.encodeToString(resp)
                        hasError = !resp.error.isNullOrBlank()
                        isEmptyResult = resp.results.isEmpty()

                        val finalJson = if (resp.error.isNullOrBlank() &&
                            decisionClient != null &&
                            decisionConfig?.enableRetrievalRerank == true &&
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
                            onTableDataRequired(rerank.requiresTableData)

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
                                                alreadyChainedIds = alreadyChainedIds
                                            )
                                        }
                                    }.awaitAll()
                                }

                                val allPrefetched = chainResults.isNotEmpty() && chainResults.all { it.didPrefetch }
                                val anyDrugSatisfied = chainResults.any { it.isDrug && it.didPrefetch }
                                val anyNeedsDrug = chainResults.any { it.needsDrug }
                                val effectiveDrugNeeded = if (anyDrugSatisfied) false else anyNeedsDrug
                                val anyNeedsRelatedTopic = chainResults.any { it.needsRelatedTopic }
                                val promisingRelatedSummary = chainResults.firstOrNull { it.needsRelatedTopic }?.dominantRelatedTopic?.let {
                                    "${it.id} (${it.title})"
                                }

                                deferredPrefetchCalls = chainResults.flatMap { it.deferredCalls }
                                didPrefetch = allPrefetched
                                isEvidenceProducer = chainResults.any { it.isEvidenceProducer }
                                needsDrugMonograph = effectiveDrugNeeded
                                needsRelatedTopic = anyNeedsRelatedTopic

                                val combinedPreview = if (allPrefetched && effectiveDrugNeeded) {
                                    chainResults.mapNotNull { it.previewMarkdown?.trim() }
                                        .filter { it.isNotBlank() }
                                        .joinToString("\n\n---\n\n")
                                        .take(2500)
                                        .ifBlank { null }
                                } else null

                                DecisionGates.injectSearchRerankIntoJson(
                                    toolJson = rawJson,
                                    rerank = rerank,
                                    json = json,
                                    autoChainedTopicId = candidatesToChain.first().id,
                                    autoChainedTopicIds = candidatesToChain.map { it.id },
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
                            rawJson
                        }
                        if (resp.error.isNullOrBlank()) {
                            cache.searchCache[query] = ToolExecutionOutcome(
                                resultJson = finalJson,
                                canonicalTool = "searchTopics",
                                didPrefetch = didPrefetch,
                                needsDrugMonograph = needsDrugMonograph,
                                needsRelatedTopic = needsRelatedTopic,
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
                        needsRelatedTopic = cached.needsRelatedTopic
                        cached.resultJson
                    } else {
                        val resp = medicalDatabaseTools.getTopicOverview(topicId)
                        val rawJson = json.encodeToString(resp)
                        hasError = !resp.error.isNullOrBlank()

                        val finalJson = if (resp.error.isNullOrBlank() &&
                            decisionClient != null &&
                            decisionConfig?.enableRetrievalRerank == true
                        ) {
                            val isCalc = resp.topicType.equals("calc", ignoreCase = true)
                            val isDrug = resp.topicType.equals("drug", ignoreCase = true)
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
                                accumulator.recordSectionScore(topicId, sec.id, sec.score)
                            }
                            rerank.rankedGraphics.forEach { gfx ->
                                accumulator.recordGraphicScore(gfx.id, gfx.score)
                            }
                            val prefetch = if (rerank.ok && decisionConfig.autoPrefetch) {
                                executeAutoPrefetch(
                                    topicId = topicId,
                                    rerank = rerank,
                                    accumulator = accumulator,
                                    cache = cache
                                )
                            } else null
                            val actuallyPrefetched = (prefetch?.didPrefetch == true) || isCalc
                            val effectiveDrugNeeded = if (isDrug) false else rerank.needsDrugMonograph
                            val uninspectedRelated = rerank.recommendedRelatedTopics.firstOrNull { rel ->
                                rel.id != topicId &&
                                !accumulator.hasInspectedTopic(rel.id) &&
                                !cache.overviewCache.containsKey(rel.id)
                            }
                            val bestSectionScore = rerank.rankedSections.firstOrNull()?.score ?: 0.0
                            val effectiveRelatedNeeded = uninspectedRelated != null &&
                                uninspectedRelated.score >= 2.6 &&
                                (uninspectedRelated.score > bestSectionScore || uninspectedRelated.score >= 2.8)

                            if (prefetch != null) {
                                deferredPrefetchCalls = prefetch.recordedCalls
                            }
                            didPrefetch = actuallyPrefetched
                            isEvidenceProducer = actuallyPrefetched
                            needsDrugMonograph = effectiveDrugNeeded
                            needsRelatedTopic = effectiveRelatedNeeded

                            val markdownToPreview = prefetch?.sectionsMarkdown?.takeIf { it.isNotBlank() } ?: prefetch?.tablesMarkdown
                            val previewExcerpt = if (actuallyPrefetched && effectiveDrugNeeded) {
                                markdownToPreview?.take(2500)?.trim()
                            } else null

                            DecisionGates.injectOverviewRerankIntoJson(
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
                        } else {
                            val isCalc = resp.topicType.equals("calc", ignoreCase = true)
                            if (isCalc && decisionClient != null && decisionConfig?.autoPrefetch == true) {
                                didPrefetch = true
                                isEvidenceProducer = true
                            }
                            rawJson
                        }
                        if (resp.error.isNullOrBlank()) {
                            cache.overviewCache[topicId] = ToolExecutionOutcome(
                                resultJson = finalJson,
                                canonicalTool = "getTopicOverview",
                                didPrefetch = didPrefetch,
                                needsDrugMonograph = needsDrugMonograph,
                                needsRelatedTopic = needsRelatedTopic,
                                hasError = hasError,
                                isEmptyResult = isEmptyResult,
                                isEvidenceProducer = isEvidenceProducer
                            )
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

        return ToolExecutionOutcome(
            resultJson = resultJson,
            canonicalTool = canonicalName,
            didPrefetch = didPrefetch,
            needsDrugMonograph = needsDrugMonograph,
            needsRelatedTopic = needsRelatedTopic,
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
        alreadyChainedIds: Set<String> = emptySet()
    ): ChainedCandidateResult {
        val candId = cand.id
        val isTable = cand.kind.equals("table", ignoreCase = true)

        if (isTable) {
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
                    previewMarkdown = gfxResp.markdown
                )
            } else {
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
                val isCalc = overviewResp.topicType.equals("calc", ignoreCase = true)
                val isDrug = overviewResp.topicType.equals("drug", ignoreCase = true)
                val overviewRerank = DecisionGates.rankOverviewItems(
                    client = decisionClient,
                    question = accumulator.question,
                    patientProfileText = accumulator.getPatientProfile()?.toSystemBlock().orEmpty(),
                    overview = overviewResp,
                    config = decisionConfig,
                    searchRequiresTableData = rerank.requiresTableData
                )
                onDecisionTokens(overviewRerank.usage.totalInputTokens)
                overviewRerank.rankedSections.forEach { sec ->
                    val finalScore = if (isCalc && sec.id == "FULL" && cand.score > 0.0) cand.score else sec.score
                    accumulator.recordSectionScore(candId, sec.id, finalScore)
                }
                overviewRerank.rankedGraphics.forEach { gfx ->
                    accumulator.recordGraphicScore(gfx.id, gfx.score)
                }
                val uninspectedRelated = overviewRerank.recommendedRelatedTopics.firstOrNull { rel ->
                    rel.id != candId &&
                    rel.id !in alreadyChainedIds &&
                    !accumulator.hasInspectedTopic(rel.id) &&
                    !cache.overviewCache.containsKey(rel.id)
                }
                val bestSectionScore = overviewRerank.rankedSections.firstOrNull()?.score ?: 0.0
                val effectiveRelatedNeeded = uninspectedRelated != null &&
                    uninspectedRelated.score >= 2.6 &&
                    (uninspectedRelated.score > bestSectionScore || uninspectedRelated.score >= 2.8)

                val prefetch = if (overviewRerank.ok && decisionConfig.autoPrefetch && !isCalc) {
                    executeAutoPrefetch(
                        topicId = candId,
                        rerank = overviewRerank,
                        accumulator = accumulator,
                        cache = cache
                    )
                } else null

                val actuallyPrefetched = (prefetch?.didPrefetch == true) || isCalc
                val effectiveDrugNeeded = when {
                    isDrug -> false
                    overviewRerank.ok -> overviewRerank.needsDrugMonograph
                    else -> rerank.requiresDrugMonograph
                }

                val markdownToPreview = prefetch?.sectionsMarkdown?.takeIf { it.isNotBlank() } ?: prefetch?.tablesMarkdown
                val previewExcerpt = if (actuallyPrefetched && effectiveDrugNeeded) {
                    markdownToPreview?.take(2500)?.trim()
                } else null

                val overviewJsonWithRerank = DecisionGates.injectOverviewRerankIntoJson(
                    toolJson = rawOverviewJson,
                    rerank = overviewRerank,
                    autoPrefetched = actuallyPrefetched,
                    prefetchedSectionsMarkdown = null,
                    prefetchedTablesMarkdown = null,
                    prefetchedSectionsChars = prefetch?.sectionsMarkdown?.length ?: 0,
                    prefetchedTablesChars = prefetch?.tablesMarkdown?.length ?: 0,
                    prefetchedPreview = previewExcerpt,
                    needsRelatedTopic = effectiveRelatedNeeded,
                    json = json
                )
                cache.overviewCache[candId] = ToolExecutionOutcome(
                    resultJson = overviewJsonWithRerank,
                    canonicalTool = "getTopicOverview",
                    didPrefetch = actuallyPrefetched,
                    needsDrugMonograph = effectiveDrugNeeded,
                    needsRelatedTopic = effectiveRelatedNeeded,
                    hasError = false,
                    isEmptyResult = false,
                    isEvidenceProducer = actuallyPrefetched
                )

                val chainedCalls = mutableListOf<Triple<String, String, String>>()
                val overviewArgs = json.encodeToString(
                    buildJsonObject { put("topicId", candId) }
                )
                chainedCalls.add(Triple("getTopicOverview", overviewArgs, overviewJsonWithRerank))
                if (prefetch != null) {
                    chainedCalls.addAll(prefetch.recordedCalls)
                }

                ChainedCandidateResult(
                    candidateId = candId,
                    isTable = false,
                    isDrug = isDrug,
                    isCalc = isCalc,
                    deferredCalls = chainedCalls,
                    didPrefetch = actuallyPrefetched,
                    isEvidenceProducer = actuallyPrefetched,
                    needsDrug = effectiveDrugNeeded,
                    needsRelatedTopic = effectiveRelatedNeeded,
                    dominantRelatedTopic = if (effectiveRelatedNeeded) uninspectedRelated else null,
                    previewMarkdown = markdownToPreview
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
        isAnthropic: Boolean
    ): Prompt = buildPromptWithHistory(
        id = "clinref-retrieval",
        finalUserContent = question,
        chatHistory = chatHistory,
        providerParams = providerParams,
        isAnthropic = isAnthropic,
        patientProfile = patientProfile,
        systemInstruction = SystemPrompt.RETRIEVAL_SYSTEM_PROMPT,
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

