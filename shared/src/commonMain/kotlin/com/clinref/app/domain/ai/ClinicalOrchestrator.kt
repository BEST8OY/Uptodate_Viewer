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
import ai.koog.prompt.streaming.StreamFrame
import com.clinref.app.data.MedicalDatabaseTools
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
 * - Gate: 1-pass revision on unverified clinical quantities with amber warning badges on unresolved figures.
 */
class ClinicalOrchestrator(
    private val medicalDatabaseTools: MedicalDatabaseTools,
    private val safetyValidator: SafetyValidator = SafetyValidator(),
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
        val validation: SafetyValidator.ValidationResult,
        val tokenUsage: StreamingManager.TokenUsage
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
        decisionConfig: DecisionConfig? = null
    ): ExecutionResult {
        val trimmedQuestion = question.trim()

        // ════════════════════════════════════════════════════════════════════
        // STAGE 0: Small Talk Gating
        // ════════════════════════════════════════════════════════════════════
        if (QuestionRouter.smallTalk(trimmedQuestion)) {
            Log.d(TAG, "Small talk detected: returning canned reply with zero LLM calls")
            val canned = QuestionRouter.CANNED_FALLBACK_REPLY
            streamingManager.onStreamingTextDelta(canned)
            val validation = SafetyValidator.ValidationResult(passed = true, warnings = emptyList())
            val usage = StreamingManager.TokenUsage()
            streamingManager.onCompleted(canned, validation)
            return ExecutionResult(canned, validation, usage)
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
                streamingManager.onLlmCallCompleted(p, c, p + c)
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
                toolCalls.map { tc ->
                    async {
                        val outcome = executeSingleTool(
                            toolCall = tc,
                            accumulator = accumulator,
                            streamingManager = streamingManager,
                            decisionClient = decisionClient,
                            decisionConfig = decisionConfig,
                            onDecisionTokens = recordDecisionTokens
                        )
                        tc to outcome
                    }
                }.awaitAll()
            }

            // Append assistant tool calls and user tool results into ephemeral retrieval prompt
            val toolResultParts = executedResults.map { (tc, outcome) ->
                MessagePart.Tool.Result(
                    id = tc.id ?: "",
                    tool = tc.tool,
                    output = outcome
                )
            }
            val userToolMessage = Message.User(parts = toolResultParts, metaInfo = RequestMetaInfo.Empty)

            currentRetrievalPrompt = currentRetrievalPrompt.withMessages { oldMessages ->
                oldMessages + assistantMsg + userToolMessage
            }
        }

        // If no evidence exists after retrieval rounds and retry attempts, cleanly abstain
        if (!accumulator.hasEvidence) {
            Log.w(TAG, "Zero evidence retrieved after retrieval loop; returning canonical abstention")
            val abstention = SystemPrompt.CANONICAL_ABSTENTION
            streamingManager.onStreamingTextDelta(abstention)
            val validation = SafetyValidator.ValidationResult(passed = true, warnings = emptyList())
            val decTokens = synchronized(tokenLock) { decisionPromptTokens }
            val usage = StreamingManager.TokenUsage(
                promptTokens = totalPromptTokens,
                completionTokens = totalCompletionTokens,
                totalTokens = totalPromptTokens + totalCompletionTokens + decTokens,
                toolTokens = decTokens
            )
            streamingManager.onCompleted(abstention, validation, usage)
            return ExecutionResult(abstention, validation, usage)
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 2: Closed-Book Clinical Synthesis (Live Token Streaming)
        // ════════════════════════════════════════════════════════════════════
        streamingManager.onSynthesisStarting()

        val evidenceSummary = SystemPrompt.evidenceSummaryFrom(
            sections = accumulator.getFetchedSections(),
            graphics = accumulator.getFetchedGraphics()
        )

        val synthesisDirective = SystemPrompt.formatSynthesisPrompt(
            pendingInput = trimmedQuestion,
            userQuestion = trimmedQuestion,
            turnContext = accumulator.buildTurnContext("")
        )

        val synthesisPrompt = buildSynthesisPrompt(
            question = trimmedQuestion,
            synthesisDirective = synthesisDirective,
            patientProfile = patientProfile,
            chatHistory = chatHistory,
            providerParams = providerParams,
            isAnthropic = isAnthropic
        )

        var (draftText, stage2Usage) = streamSynthesisCall(synthesisPrompt, model, executor, streamingManager)
        totalPromptTokens += stage2Usage.promptTokens
        totalCompletionTokens += stage2Usage.completionTokens

        // ════════════════════════════════════════════════════════════════════
        // GATE 2: Safety Validation & 1-Pass Revision on Unverified Quantities
        // ════════════════════════════════════════════════════════════════════
        streamingManager.onVerificationStarting()
        var turnContext = accumulator.buildTurnContext(draftText)
        var validation = safetyValidator.validate(turnContext)

        val hasRevisableViolation = validation.violations.any {
            it.code == ViolationCodes.UNVERIFIED_QTY ||
                it.code == ViolationCodes.UNRETRIEVED_DOSES ||
                it.code == ViolationCodes.VISUAL ||
                it.code == ViolationCodes.BLANK_WITH_EVIDENCE
        }

        if (!validation.passed && hasRevisableViolation) {
            Log.w(TAG, "Gate 2 detected revisable violations: ${validation.violations.map { it.code }}; issuing 1 revision pass")
            streamingManager.onRevisionStarting()
            val revisionDirective = SystemPrompt.buildRevisionPrompt(
                violations = validation.violations,
                draft = draftText,
                evidenceSummary = evidenceSummary
            )

            val revisionPrompt = buildPromptWithHistory(
                id = "clinref-revision",
                finalUserContent = "CLINICAL INQUIRY:\n$trimmedQuestion\n\n$revisionDirective",
                patientProfile = patientProfile,
                chatHistory = chatHistory,
                providerParams = providerParams,
                isAnthropic = isAnthropic,
                questionToDeduplicate = trimmedQuestion
            )

            streamingManager.onSynthesisStarting()
            val (revisedText, revisionUsage) = streamSynthesisCall(revisionPrompt, model, executor, streamingManager)
            totalPromptTokens += revisionUsage.promptTokens
            totalCompletionTokens += revisionUsage.completionTokens

            if (revisedText.isNotBlank()) {
                draftText = revisedText
                turnContext = accumulator.buildTurnContext(draftText)
                validation = safetyValidator.validate(turnContext)
            }
        }

        val decTokens = synchronized(tokenLock) { decisionPromptTokens }
        val totalUsage = StreamingManager.TokenUsage(
            promptTokens = totalPromptTokens,
            completionTokens = totalCompletionTokens,
            totalTokens = totalPromptTokens + totalCompletionTokens + decTokens,
            toolTokens = decTokens
        )

        streamingManager.onCompleted(draftText, validation, totalUsage)
        return ExecutionResult(draftText, validation, totalUsage)
    }

    private suspend fun streamSynthesisCall(
        prompt: Prompt,
        model: LLModel,
        executor: PromptExecutor,
        streamingManager: StreamingManager
    ): Pair<String, StreamingManager.TokenUsage> {
        val streamFlow: Flow<StreamFrame> = executor.executeStreaming(prompt, model, emptyList())
        val textAccumulator = StringBuilder()
        var usage = StreamingManager.TokenUsage()

        streamFlow.collect { frame ->
            when (frame) {
                is StreamFrame.TextDelta -> {
                    textAccumulator.append(frame.text)
                    streamingManager.onStreamingTextDelta(frame.text)
                }
                is StreamFrame.ReasoningDelta -> {
                    frame.text?.let { streamingManager.onStreamingReasoningDelta(it) }
                }
                is StreamFrame.End -> {
                    streamingManager.onStreamingEnd()
                    frame.metaInfo.let { meta ->
                        val p = meta.inputTokensCount ?: 0
                        val c = meta.outputTokensCount ?: 0
                        usage = StreamingManager.TokenUsage(p, c, p + c)
                        streamingManager.onLlmCallCompleted(p, c, p + c)
                    }
                }
                else -> {}
            }
        }

        val cleaned = ReasoningTokenSanitizer.stripReasoning(textAccumulator.toString())
        return cleaned to usage
    }

    private suspend fun executeSingleTool(
        toolCall: MessagePart.Tool.Call,
        accumulator: TurnContextAccumulator,
        streamingManager: StreamingManager,
        decisionClient: DecisionClient? = null,
        decisionConfig: DecisionConfig? = null,
        onDecisionTokens: (Int) -> Unit = {}
    ): String {
        val canonicalName = AiJsonUtils.normalizeToolName(toolCall.tool)
        val callId = toolCall.id ?: ""
        val argsStr = toolCall.args.ifBlank { toolCall.argsJson.toString() }
        val cleanArgs = AiJsonUtils.extractJsonString(argsStr).ifBlank { argsStr }

        accumulator.onToolCallStarting(callId, cleanArgs)
        streamingManager.onToolCallStarting(canonicalName, cleanArgs, callId)

        var deferredPrefetchCalls = emptyList<Triple<String, String, String>>() // toolName, argsJson, resultJson

        val resultJson = try {
            when (canonicalName) {
                "searchTopics" -> {
                    val query = extractQueryArg(cleanArgs)
                    val resp = medicalDatabaseTools.searchTopics(query)
                    val rawJson = json.encodeToString(resp)
                    if (resp.error.isNullOrBlank() &&
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
                        DecisionGates.injectSearchRerankIntoJson(rawJson, rerank, json)
                    } else {
                        rawJson
                    }
                }
                "getTopicOverview" -> {
                    val topicId = extractTopicIdArg(cleanArgs)
                    val resp = medicalDatabaseTools.getTopicOverview(topicId)
                    val rawJson = json.encodeToString(resp)
                    if (resp.error.isNullOrBlank() &&
                        decisionClient != null &&
                        decisionConfig?.enableRetrievalRerank == true
                    ) {
                        val rerank = DecisionGates.rankOverviewItems(
                            client = decisionClient,
                            question = accumulator.question,
                            patientProfileText = accumulator.getPatientProfile()?.toSystemBlock().orEmpty(),
                            overview = resp,
                            config = decisionConfig
                        )
                        onDecisionTokens(rerank.usage.totalInputTokens)
                        if (rerank.ok && decisionConfig.autoPrefetch) {
                            val prefetch = executeAutoPrefetch(
                                topicId = topicId,
                                rerank = rerank,
                                accumulator = accumulator
                            )
                            deferredPrefetchCalls = prefetch.recordedCalls
                            DecisionGates.injectOverviewRerankIntoJson(
                                toolJson = rawJson,
                                rerank = rerank,
                                autoPrefetched = prefetch.didPrefetch,
                                prefetchedSectionsMarkdown = prefetch.sectionsMarkdown,
                                prefetchedTablesMarkdown = prefetch.tablesMarkdown,
                                json = json
                            )
                        } else {
                            DecisionGates.injectOverviewRerankIntoJson(rawJson, rerank, json = json)
                        }
                    } else {
                        rawJson
                    }
                }
                "getTopicSectionsText" -> {
                    val (topicId, sectionIds) = extractSectionsArg(cleanArgs)
                    val resp = medicalDatabaseTools.getTopicSectionsText(topicId, sectionIds)
                    json.encodeToString(resp)
                }
                "getGraphicContent" -> {
                    val graphicId = extractGraphicIdArg(cleanArgs)
                    val resp = medicalDatabaseTools.getGraphicContent(graphicId)
                    json.encodeToString(resp)
                }
                else -> {
                    """{"error":"Unknown tool: ${toolCall.tool}"}"""
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error for ${toolCall.tool}: ${e.message}")
            """{"error":"Execution failed: ${e.message}"}"""
        }

        accumulator.onToolCallCompleted(callId, canonicalName, resultJson, success = true, toolArgs = cleanArgs)
        streamingManager.onToolCallCompleted(canonicalName, resultJson, callId)

        // Record prefetched sections/tables AFTER getTopicOverview so topic/section/graphic metadata is indexed first.
        deferredPrefetchCalls.forEachIndexed { idx, (prefetchTool, prefetchArgs, prefetchResult) ->
            val prefetchCallId = if (callId.isNotBlank()) {
                "${callId}_prefetch_$idx"
            } else {
                "prefetch_${kotlin.random.Random.nextLong().toString(16)}_$idx"
            }
            accumulator.onToolCallStarting(prefetchCallId, prefetchArgs)
            streamingManager.onToolCallStarting(prefetchTool, prefetchArgs, prefetchCallId)
            accumulator.onToolCallCompleted(
                toolCallId = prefetchCallId,
                toolName = prefetchTool,
                result = prefetchResult,
                success = true,
                toolArgs = prefetchArgs
            )
            streamingManager.onToolCallCompleted(prefetchTool, prefetchResult, prefetchCallId)
        }

        streamingManager.onWaitingForLlm()

        return resultJson
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
        accumulator: TurnContextAccumulator
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
                }
            }

            val gfxResults = gfxDeferreds.awaitAll()
            val tableBlocks = mutableListOf<String>()
            gfxResults.forEach { (args, pair) ->
                val (resp, encoded) = pair
                recorded.add(Triple("getGraphicContent", args, encoded))
                if (resp.error.isNullOrBlank() && resp.markdown.isNotBlank()) {
                    tableBlocks.add(resp.markdown.trim())
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
        patientProfile: PatientProfile,
        chatHistory: List<MessageEntity>,
        providerParams: LLMParams?,
        isAnthropic: Boolean,
        questionToDeduplicate: String? = null
    ): Prompt {
        val cache = if (isAnthropic) AnthropicCacheControl.Default else null
        val patientBlock = SystemPrompt.patientContextBlock(patientProfile)
        val trimmedTurns = chatHistory
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(MAX_CHAT_HISTORY_TURNS)
        val historyTurns = if (questionToDeduplicate != null && trimmedTurns.lastOrNull()?.role == "user" && trimmedTurns.lastOrNull()?.content == questionToDeduplicate) {
            trimmedTurns.dropLast(1)
        } else {
            trimmedTurns
        }

        return prompt(id = id, params = providerParams ?: LLMParams()) {
            system(SystemPrompt.STATIC_CLINICAL_GUIDANCE, cache = cache)
            if (patientBlock.isNotBlank()) {
                system(patientBlock)
            }
            historyTurns.forEach { msg ->
                if (msg.role == "user") {
                    user(msg.content)
                } else {
                    assistant(msg.content)
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
        patientProfile = patientProfile,
        chatHistory = chatHistory,
        providerParams = providerParams,
        isAnthropic = isAnthropic,
        questionToDeduplicate = question
    )

    private fun buildSynthesisPrompt(
        question: String,
        synthesisDirective: String,
        patientProfile: PatientProfile,
        chatHistory: List<MessageEntity>,
        providerParams: LLMParams?,
        isAnthropic: Boolean
    ): Prompt = buildPromptWithHistory(
        id = "clinref-synthesis",
        finalUserContent = synthesisDirective,
        patientProfile = patientProfile,
        chatHistory = chatHistory,
        providerParams = providerParams,
        isAnthropic = isAnthropic,
        questionToDeduplicate = question
    )

    private fun extractQueryArg(argsStr: String): String {
        return try {
            val element = json.parseToJsonElement(argsStr)
            if (element is JsonObject) {
                element["query"]?.jsonPrimitive?.contentOrNull
                    ?: element["search_query"]?.jsonPrimitive?.contentOrNull
                    ?: element["q"]?.jsonPrimitive?.contentOrNull
                    ?: element["query_text"]?.jsonPrimitive?.contentOrNull
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
                    ?: element["id"]?.jsonPrimitive?.contentOrNull
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
                ?: parseIds(element["sectionId"])
                ?: parseIds(element["section_id"])
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
                    ?: element["id"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            } else argsStr
        } catch (_: Exception) {
            argsStr
        }
    }
}

