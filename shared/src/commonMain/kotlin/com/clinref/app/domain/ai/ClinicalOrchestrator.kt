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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

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
        isAnthropic: Boolean = false
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
                Log.d(TAG, "Stage 1 complete: Model requested zero further tool calls")
                break
            }

            // Execute all requested tool calls in parallel
            val executedResults = coroutineScope {
                toolCalls.map { tc ->
                    async {
                        val resultStr = executeSingleTool(tc, accumulator, streamingManager)
                        tc to resultStr
                    }
                }.awaitAll()
            }

            // Append assistant tool calls and user tool results into ephemeral retrieval prompt
            val toolResultParts = executedResults.map { (tc, resStr) ->
                MessagePart.Tool.Result(
                    id = tc.id ?: "",
                    tool = tc.tool,
                    output = resStr
                )
            }
            val userToolMessage = Message.User(parts = toolResultParts, metaInfo = RequestMetaInfo.Empty)

            currentRetrievalPrompt = currentRetrievalPrompt.withMessages { oldMessages ->
                oldMessages + assistantMsg + userToolMessage
            }
        }

        // ════════════════════════════════════════════════════════════════════
        // EVIDENCE GATING: 1-Pass Retrieval Retry if zero evidence captured
        // ════════════════════════════════════════════════════════════════════
        if (!accumulator.hasEvidence) {
            Log.w(TAG, "Stage 1 finished with zero evidence; issuing 1 retrieval retry")
            val pauseNotice = SystemPrompt.buildEvidenceGatePausePrompt(SystemPrompt.NOTHING_RETRIEVED_PLACEHOLDER)
            val retryPrompt = currentRetrievalPrompt.withMessages { old ->
                old + Message.User(content = pauseNotice, metaInfo = RequestMetaInfo.Empty)
            }

            val retryAssistantMsg = executor.execute(retryPrompt, model, toolDescriptors)
            retryAssistantMsg.metaInfo.let { meta ->
                val p = meta.inputTokensCount ?: 0
                val c = meta.outputTokensCount ?: 0
                totalPromptTokens += p
                totalCompletionTokens += c
                streamingManager.onLlmCallCompleted(p, c, p + c)
            }

            val retryToolCalls = retryAssistantMsg.parts.filterIsInstance<MessagePart.Tool.Call>()
            if (retryToolCalls.isNotEmpty()) {
                coroutineScope {
                    retryToolCalls.map { tc ->
                        async { executeSingleTool(tc, accumulator, streamingManager) }
                    }.awaitAll()
                }
            }
        }

        // If still no evidence exists, cleanly abstain
        if (!accumulator.hasEvidence) {
            Log.w(TAG, "Zero evidence retrieved after retry; returning canonical abstention")
            val abstention = SystemPrompt.CANONICAL_ABSTENTION
            streamingManager.onStreamingTextDelta(abstention)
            val validation = SafetyValidator.ValidationResult(passed = true, warnings = emptyList())
            val usage = StreamingManager.TokenUsage(totalPromptTokens, totalCompletionTokens, totalPromptTokens + totalCompletionTokens)
            streamingManager.onCompleted(abstention, validation)
            return ExecutionResult(abstention, validation, usage)
        }

        // ════════════════════════════════════════════════════════════════════
        // STAGE 2: Closed-Book Clinical Synthesis (Live Token Streaming)
        // ════════════════════════════════════════════════════════════════════
        streamingManager.onSynthesisStarting()

        val evidenceSummary = SystemPrompt.evidenceSummaryFrom(accumulator.getFetchedSections())

        val synthesisDirective = SystemPrompt.formatSynthesisPrompt(
            pendingInput = trimmedQuestion,
            userQuestion = trimmedQuestion,
            turnContext = accumulator.buildTurnContext("")
        )

        val synthesisPrompt = buildSynthesisPrompt(
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
        var turnContext = accumulator.buildTurnContext(draftText)
        var validation = safetyValidator.validate(turnContext)

        val hasUnverifiedNumbers = validation.violations.any {
            it.code == ViolationCodes.UNVERIFIED_QTY || it.code == ViolationCodes.UNRETRIEVED_DOSES
        }

        if (!validation.passed && hasUnverifiedNumbers && draftText.isNotBlank()) {
            Log.w(TAG, "Gate 2 detected unverified clinical quantities; issuing 1 revision pass")
            val revisionDirective = SystemPrompt.buildRevisionPrompt(
                violations = validation.violations,
                draft = draftText,
                evidenceSummary = evidenceSummary
            )

            val revisionPrompt = prompt(id = "clinref-revision", params = providerParams ?: LLMParams()) {
                val cache = if (isAnthropic) AnthropicCacheControl.Default else null
                system(SystemPrompt.STATIC_CLINICAL_GUIDANCE, cache = cache)
                val patientBlock = SystemPrompt.patientContextBlock(patientProfile)
                if (patientBlock.isNotBlank()) {
                    system(patientBlock)
                }
                user(synthesisDirective)
                assistant(draftText)
                user(revisionDirective)
            }

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

        val totalUsage = StreamingManager.TokenUsage(
            promptTokens = totalPromptTokens,
            completionTokens = totalCompletionTokens,
            totalTokens = totalPromptTokens + totalCompletionTokens
        )

        streamingManager.onCompleted(draftText, validation)
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
        streamingManager: StreamingManager
    ): String {
        val canonicalName = AiJsonUtils.normalizeToolName(toolCall.tool)
        val callId = toolCall.id ?: ""
        val argsStr = toolCall.args.ifBlank { toolCall.argsJson.toString() }
        val cleanArgs = AiJsonUtils.extractJsonString(argsStr).ifBlank { argsStr }

        accumulator.onToolCallStarting(callId, cleanArgs)
        streamingManager.onToolCallStarting(canonicalName, cleanArgs, callId)

        val resultJson = try {
            when (canonicalName) {
                "searchTopics" -> {
                    val query = extractQueryArg(cleanArgs)
                    val resp = medicalDatabaseTools.searchTopics(query)
                    json.encodeToString(resp)
                }
                "getTopicOverview" -> {
                    val topicId = extractTopicIdArg(cleanArgs)
                    val resp = medicalDatabaseTools.getTopicOverview(topicId)
                    json.encodeToString(resp)
                }
                "getTopicSectionsText" -> {
                    val (topicId, sectionIds) = extractSectionsArg(cleanArgs)
                    val resp = medicalDatabaseTools.getTopicSectionsText(topicId, sectionIds)
                    json.encodeToString(resp)
                }
                "searchTopicSections" -> {
                    val (topicId, query) = extractTopicQueryArg(cleanArgs)
                    val resp = medicalDatabaseTools.searchTopicSections(topicId, query)
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
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error for ${toolCall.tool}: ${e.message}")
            """{"error":"Execution failed: ${e.message}"}"""
        }

        accumulator.onToolCallCompleted(callId, canonicalName, resultJson, success = true, toolArgs = cleanArgs)
        streamingManager.onToolCallCompleted(canonicalName, resultJson, callId)
        streamingManager.onWaitingForLlm()

        return resultJson
    }

    private fun buildInitialRetrievalPrompt(
        question: String,
        patientProfile: PatientProfile,
        chatHistory: List<MessageEntity>,
        providerParams: LLMParams?,
        isAnthropic: Boolean
    ): Prompt {
        val cache = if (isAnthropic) AnthropicCacheControl.Default else null
        val patientBlock = SystemPrompt.patientContextBlock(patientProfile)
        val historyTurns = chatHistory
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(MAX_CHAT_HISTORY_TURNS)

        return prompt(id = "clinref-retrieval", params = providerParams ?: LLMParams()) {
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
            user(question)
        }
    }

    private fun buildSynthesisPrompt(
        synthesisDirective: String,
        patientProfile: PatientProfile,
        chatHistory: List<MessageEntity>,
        providerParams: LLMParams?,
        isAnthropic: Boolean
    ): Prompt {
        val cache = if (isAnthropic) AnthropicCacheControl.Default else null
        val patientBlock = SystemPrompt.patientContextBlock(patientProfile)
        val historyTurns = chatHistory
            .filter { it.role == "user" || it.role == "assistant" }
            .takeLast(MAX_CHAT_HISTORY_TURNS)

        return prompt(id = "clinref-synthesis", params = providerParams ?: LLMParams()) {
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
            user(synthesisDirective)
        }
    }

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
            } else argsStr.filter { it.isDigit() }
        } catch (_: Exception) {
            argsStr.filter { it.isDigit() }
        }
    }

    private fun extractSectionsArg(argsStr: String): Pair<String, List<String>> {
        return try {
            val element = json.parseToJsonElement(argsStr)
            if (element is JsonObject) {
                val topicId = element["topicId"]?.jsonPrimitive?.contentOrNull
                    ?: element["topic_id"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val sectionsArray = element["sectionIds"]?.jsonArray
                    ?: element["section_ids"]?.jsonArray
                val sectionIds = sectionsArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: element["section_id"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) }
                    ?: element["sectionId"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) }
                    ?: emptyList()
                topicId to sectionIds
            } else "" to emptyList()
        } catch (_: Exception) {
            "" to emptyList()
        }
    }

    private fun extractTopicQueryArg(argsStr: String): Pair<String, String> {
        return try {
            val element = json.parseToJsonElement(argsStr)
            if (element is JsonObject) {
                val topicId = element["topicId"]?.jsonPrimitive?.contentOrNull
                    ?: element["topic_id"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val query = element["query"]?.jsonPrimitive?.contentOrNull
                    ?: element["keywords"]?.jsonPrimitive?.contentOrNull
                    ?: element["keyword"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                topicId to query
            } else "" to ""
        } catch (_: Exception) {
            "" to ""
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

