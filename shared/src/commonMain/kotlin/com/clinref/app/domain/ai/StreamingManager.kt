package com.clinref.app.domain.ai

import com.clinref.app.data.GraphicContentResponse
import com.clinref.app.data.RelatedTopicsResponse
import com.clinref.app.data.SearchTopicSectionsResponse
import com.clinref.app.data.SearchTopicsResponse
import com.clinref.app.data.TopicOverviewResponse
import com.clinref.app.data.TopicSectionsResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class StreamingManager {

    data class TokenUsage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0
    )

    enum class StepStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    data class OrchestrationStep(
        val id: String,
        val title: String,
        val detail: String? = null,
        val status: StepStatus = StepStatus.IN_PROGRESS,
        val toolName: String? = null
    )

    sealed class AgentState {
        data object Idle : AgentState()
        data class ToolCallInProgress(
            val toolName: String,
            val args: String,
            val stepIndex: Int
        ) : AgentState()
        data class WaitingForLlm(val stepIndex: Int) : AgentState()
        data class Completed(
            val result: String,
            val validation: SafetyValidator.ValidationResult,
            val tokenUsage: TokenUsage = TokenUsage()
        ) : AgentState()
        data class Error(
            val message: String,
            val type: ErrorType
        ) : AgentState()
    }

    data class ToolProgress(
        val toolName: String,
        val description: String
    )

    enum class ErrorType { INVALID_KEY, NO_NETWORK, RATE_LIMIT, TIMEOUT, NO_RESULTS, UNKNOWN }

    private val json = Json { ignoreUnknownKeys = true }

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _toolProgress = MutableStateFlow<ToolProgress?>(null)
    val toolProgress: StateFlow<ToolProgress?> = _toolProgress.asStateFlow()

    private val _orchestrationSteps = MutableStateFlow<List<OrchestrationStep>>(emptyList())
    val orchestrationSteps: StateFlow<List<OrchestrationStep>> = _orchestrationSteps.asStateFlow()

    private val _currentStatusText = MutableStateFlow("Initializing clinical core…")
    val currentStatusText: StateFlow<String> = _currentStatusText.asStateFlow()

    private val _liveDiscoveredSources = MutableStateFlow<List<SafetyValidator.TopicRef>>(emptyList())
    val liveDiscoveredSources: StateFlow<List<SafetyValidator.TopicRef>> = _liveDiscoveredSources.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _reasoningText = MutableStateFlow("")
    val reasoningText: StateFlow<String> = _reasoningText.asStateFlow()

    private val _isReasoning = MutableStateFlow(false)
    val isReasoning: StateFlow<Boolean> = _isReasoning.asStateFlow()

    private var stepCounter = 0
    private var activeToolCalls = 0
    private var accumulatedUsage = TokenUsage()
    private val streamingBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val pendingTagBuffer = StringBuilder()
    private var inReasoningBlock = false

    private val knownTopicTitles = mutableMapOf<String, String>()

    fun onToolCallStarting(toolName: String, args: String, callId: String = "") {
        stepCounter++
        activeToolCalls++

        // Pre-tool thought hygiene: reset streaming and reasoning buffers so intermediate thoughts don't contaminate response
        streamingBuffer.clear()
        reasoningBuffer.clear()
        pendingTagBuffer.clear()
        inReasoningBlock = false
        _streamingText.value = ""
        _reasoningText.value = ""
        _isReasoning.value = false

        val stepId = callId.ifBlank { "step_$stepCounter" }
        val (title, detail) = getStepInfo(toolName, args)

        _orchestrationSteps.value = _orchestrationSteps.value + OrchestrationStep(
            id = stepId,
            title = title,
            detail = detail,
            status = StepStatus.IN_PROGRESS,
            toolName = toolName
        )

        val description = when (toolName) {
            "searchTopics" -> "Searching medical topics…"
            "getTopicOverview" -> "Retrieving topic overview & core evidence…"
            "getRelatedTopics" -> "Finding related clinical topics…"
            "getTopicSectionsText" -> "Retrieving evidence sections…"
            "getGraphicContent" -> "Loading clinical reference table…"
            else -> "Executing $toolName…"
        }
        _currentStatusText.value = description
        _toolProgress.value = ToolProgress(toolName, description)
        _agentState.value = AgentState.ToolCallInProgress(toolName, args, stepCounter)
    }

    fun onToolCallCompleted(toolName: String, resultText: String = "", callId: String = "") {
        activeToolCalls = (activeToolCalls - 1).coerceAtLeast(0)

        // Parse any discovered source titles or tables
        parseDiscoveredSources(toolName, resultText)

        // Mark the active step as completed
        _orchestrationSteps.value = _orchestrationSteps.value.map { step ->
            val matches = if (callId.isNotBlank()) {
                step.id == callId
            } else {
                step.toolName == toolName && step.status == StepStatus.IN_PROGRESS
            }
            if (matches) {
                val completionDetail = getCompletionDetail(toolName, resultText) ?: step.detail
                step.copy(status = StepStatus.COMPLETED, detail = completionDetail)
            } else step
        }

        _currentStatusText.value = "Evaluating clinical evidence…"
        _toolProgress.value = ToolProgress(toolName, "Evaluating clinical evidence…")
    }

    fun onSynthesisStarting() {
        activeToolCalls = 0
        // Complete any previously in-progress tool steps
        _orchestrationSteps.value = _orchestrationSteps.value.map { step ->
            if (step.status == StepStatus.IN_PROGRESS) {
                step.copy(status = StepStatus.COMPLETED)
            } else step
        }

        val existingIndex = _orchestrationSteps.value.indexOfFirst { it.id == "step_synthesis" }
        if (existingIndex >= 0) {
            val updated = _orchestrationSteps.value.toMutableList()
            updated[existingIndex] = updated[existingIndex].copy(
                status = StepStatus.IN_PROGRESS,
                detail = "Drafting evidence-grounded recommendations & BLUF"
            )
            _orchestrationSteps.value = updated
        } else {
            _orchestrationSteps.value = _orchestrationSteps.value + OrchestrationStep(
                id = "step_synthesis",
                title = "Synthesize Clinical Guidance",
                detail = "Drafting evidence-grounded recommendations & BLUF",
                status = StepStatus.IN_PROGRESS
            )
        }

        _currentStatusText.value = "Synthesizing point-of-care guidance…"
        _agentState.value = AgentState.WaitingForLlm(stepCounter)
    }

    fun onWaitingForLlm() {
        if (activeToolCalls > 0) return
        val inSynthesis = _orchestrationSteps.value.any { it.id == "step_synthesis" && it.status == StepStatus.IN_PROGRESS }
        if (inSynthesis) {
            _currentStatusText.value = "Synthesizing point-of-care guidance…"
        } else {
            _currentStatusText.value = "Reasoning over clinical findings…"
        }
        _agentState.value = AgentState.WaitingForLlm(stepCounter)
    }

    fun onStreamingTextDelta(delta: String) {
        for (i in delta.indices) {
            val ch = delta[i]
            processStreamingChar(ch)
        }
        _streamingText.value = streamingBuffer.toString()
        _reasoningText.value = reasoningBuffer.toString()
    }

    fun onStreamingReasoningDelta(delta: String) {
        reasoningBuffer.append(delta)
        _reasoningText.value = reasoningBuffer.toString()
        _isReasoning.value = true
    }

    private fun processStreamingChar(ch: Char) {
        if (pendingTagBuffer.isNotEmpty()) {
            pendingTagBuffer.append(ch)
            if (ch == '>') {
                val candidate = pendingTagBuffer.toString()
                pendingTagBuffer.clear()
                if (isOpeningReasoningTag(candidate)) {
                    inReasoningBlock = true
                    _isReasoning.value = true
                } else if (isClosingReasoningTag(candidate)) {
                    inReasoningBlock = false
                    _isReasoning.value = false
                } else {
                    appendToActiveBuffer(candidate)
                }
            } else if (!isPotentialTagPrefix(pendingTagBuffer.toString())) {
                val notTag = pendingTagBuffer.toString()
                pendingTagBuffer.clear()
                appendToActiveBuffer(notTag)
            } else if (pendingTagBuffer.length > 64) {
                val overflow = pendingTagBuffer.toString()
                pendingTagBuffer.clear()
                appendToActiveBuffer(overflow)
            }
        } else {
            if (ch == '<') {
                pendingTagBuffer.append(ch)
            } else {
                appendToActiveBuffer(ch.toString())
            }
        }
    }

    private fun appendToActiveBuffer(text: String) {
        if (inReasoningBlock) {
            reasoningBuffer.append(text)
        } else {
            // Trim leading whitespace if streamingBuffer is still empty
            if (streamingBuffer.isEmpty() && text.all { it == '\n' || it == '\r' || it == ' ' }) {
                return
            }
            streamingBuffer.append(text)
        }
    }

    private fun isOpeningReasoningTag(tag: String): Boolean {
        val lower = tag.lowercase()
        return lower == "<thought>" || lower == "<think>" || lower == "<reasoning>" ||
               lower.startsWith("<thought ") || lower.startsWith("<think ") || lower.startsWith("<reasoning ")
    }

    private fun isClosingReasoningTag(tag: String): Boolean {
        val lower = tag.lowercase()
        return lower == "</thought>" || lower == "</think>" || lower == "</reasoning>"
    }

    private fun isPotentialTagPrefix(prefix: String): Boolean {
        val lower = prefix.lowercase()
        val validPrefixes = listOf(
            "<", "<t", "<th", "<tho", "<thou", "<thoug", "<thought",
            "<thi", "<thin", "<think",
            "<r", "<re", "<rea", "<reas", "<reaso", "<reason", "<reasoni", "<reasonin", "<reasoning",
            "</", "</t", "</th", "</tho", "</thou", "</thoug", "</thought",
            "</thi", "</thin", "</think",
            "</r", "</re", "</rea", "</reas", "</reaso", "</reason", "</reasoni", "</reasonin", "</reasoning"
        )
        if (validPrefixes.any { it.startsWith(lower) }) return true
        if (lower.startsWith("<thought ") || lower.startsWith("<think ") || lower.startsWith("<reasoning ")) return true
        return false
    }

    fun onStreamingToolCallDelta(callId: String, content: String, toolName: String? = null) {
        // Direct markdown streaming renders via onStreamingTextDelta
    }

    fun onStreamingEnd() {
        if (pendingTagBuffer.isNotEmpty()) {
            val remaining = pendingTagBuffer.toString()
            pendingTagBuffer.clear()
            appendToActiveBuffer(remaining)
            _streamingText.value = streamingBuffer.toString()
            _reasoningText.value = reasoningBuffer.toString()
        }
        _isReasoning.value = false
    }

    fun onLlmCallCompleted(promptTokens: Int, completionTokens: Int, totalTokens: Int) {
        accumulatedUsage = TokenUsage(
            promptTokens = accumulatedUsage.promptTokens + promptTokens,
            completionTokens = accumulatedUsage.completionTokens + completionTokens,
            totalTokens = accumulatedUsage.totalTokens + totalTokens
        )
    }

    fun onCompleted(result: String, validation: SafetyValidator.ValidationResult) {
        activeToolCalls = 0
        _toolProgress.value = null
        _currentStatusText.value = "Complete"
        _orchestrationSteps.value = _orchestrationSteps.value.map {
            if (it.status == StepStatus.IN_PROGRESS) it.copy(status = StepStatus.COMPLETED) else it
        }
        streamingBuffer.clear()
        reasoningBuffer.clear()
        pendingTagBuffer.clear()
        inReasoningBlock = false
        _streamingText.value = ""
        _reasoningText.value = ""
        _isReasoning.value = false
        _agentState.value = AgentState.Completed(result, validation, accumulatedUsage)
    }

    fun onError(error: String) {
        activeToolCalls = 0
        _toolProgress.value = null
        _currentStatusText.value = "Error"
        _orchestrationSteps.value = _orchestrationSteps.value.map {
            if (it.status == StepStatus.IN_PROGRESS) it.copy(status = StepStatus.FAILED) else it
        }
        streamingBuffer.clear()
        reasoningBuffer.clear()
        pendingTagBuffer.clear()
        inReasoningBlock = false
        _streamingText.value = ""
        _reasoningText.value = ""
        _isReasoning.value = false
        val type = classifyError(error)
        _agentState.value = AgentState.Error(error, type)
    }

    fun reset() {
        stepCounter = 0
        activeToolCalls = 0
        accumulatedUsage = TokenUsage()
        streamingBuffer.clear()
        reasoningBuffer.clear()
        pendingTagBuffer.clear()
        inReasoningBlock = false
        _streamingText.value = ""
        _reasoningText.value = ""
        _isReasoning.value = false
        _toolProgress.value = null
        _orchestrationSteps.value = emptyList()
        _liveDiscoveredSources.value = emptyList()
        knownTopicTitles.clear()
        _currentStatusText.value = "Initializing clinical core…"
        _agentState.value = AgentState.Idle
    }

    private fun getStepInfo(toolName: String, args: String): Pair<String, String?> {
        return try {
            val canonicalName = AiJsonUtils.normalizeToolName(toolName)
            val element = json.parseToJsonElement(args) as? JsonObject
            when (canonicalName) {
                "searchTopics" -> {
                    val query = element?.get("query")?.jsonPrimitive?.content ?: ""
                    "Search Knowledge Base" to if (query.isNotBlank()) "Query: \"$query\"" else null
                }
                "searchTopicSections" -> {
                    val topicId = element?.get("topicId")?.jsonPrimitive?.content
                        ?: element?.get("topic_id")?.jsonPrimitive?.content ?: ""
                    val query = element?.get("query")?.jsonPrimitive?.content ?: ""
                    val resolvedTitle = knownTopicTitles[topicId]
                    val detail = if (!resolvedTitle.isNullOrBlank() && !AiJsonUtils.isNumericOnly(resolvedTitle)) {
                        "Searching \"$query\" in \"$resolvedTitle\""
                    } else if (query.isNotBlank()) {
                        "Query: \"$query\""
                    } else null
                    "Search In-Topic Sections" to detail
                }
                "getTopicOverview" -> {
                    val topicId = element?.get("topicId")?.jsonPrimitive?.content
                        ?: element?.get("topic_id")?.jsonPrimitive?.content ?: ""
                    val resolvedTitle = knownTopicTitles[topicId]
                    val detail = if (!resolvedTitle.isNullOrBlank() && !AiJsonUtils.isNumericOnly(resolvedTitle)) {
                        "Overview: \"$resolvedTitle\""
                    } else null
                    "Examine Topic Overview" to detail
                }
                "getRelatedTopics" -> {
                    val topicId = element?.get("topicId")?.jsonPrimitive?.content
                        ?: element?.get("topic_id")?.jsonPrimitive?.content ?: ""
                    val resolvedTitle = knownTopicTitles[topicId]
                    val detail = if (!resolvedTitle.isNullOrBlank() && !AiJsonUtils.isNumericOnly(resolvedTitle)) {
                        "Related topics for \"$resolvedTitle\""
                    } else {
                        "Retrieving related sub-topics"
                    }
                    "Expand Topic Pool" to detail
                }
                "getTopicSectionsText" -> {
                    val topicId = element?.get("topicId")?.jsonPrimitive?.content
                        ?: element?.get("topic_id")?.jsonPrimitive?.content ?: ""
                    val resolvedTitle = knownTopicTitles[topicId]
                    val detail = if (!resolvedTitle.isNullOrBlank() && !AiJsonUtils.isNumericOnly(resolvedTitle)) {
                        "Fetching evidence from \"$resolvedTitle\""
                    } else {
                        "Fetching evidence sections"
                    }
                    "Retrieve Clinical Evidence" to detail
                }
                "getGraphicContent" -> {
                    "Load Reference Table" to null
                }
                else -> toolName to null
            }
        } catch (_: Exception) {
            toolName to null
        }
    }

    private fun getCompletionDetail(toolName: String, resultText: String): String? {
        if (resultText.isBlank()) return null
        return try {
            val canonicalName = AiJsonUtils.normalizeToolName(toolName)
            when (canonicalName) {
                "searchTopics" -> {
                    val res = json.decodeFromString<SearchTopicsResponse>(resultText)
                    val count = res.results.size
                    if (count > 0) {
                        "$count candidate topics identified"
                    } else if (res.message.startsWith("Auto-refined", ignoreCase = true)) {
                        res.message
                    } else {
                        "No topics found"
                    }
                }
                "getTopicOverview" -> {
                    val res = json.decodeFromString<TopicOverviewResponse>(resultText)
                    val count = res.sections.size
                    val resolvedTitle = res.title.takeIf { it.isNotBlank() && !AiJsonUtils.isNumericOnly(it) }
                        ?: knownTopicTitles[res.topicId]
                    if (!resolvedTitle.isNullOrBlank()) {
                        "Overview: \"$resolvedTitle\" ($count sections)"
                    } else {
                        "Topic overview loaded ($count sections)"
                    }
                }
                "searchTopicSections" -> {
                    val res = json.decodeFromString<SearchTopicSectionsResponse>(resultText)
                    val count = res.matches.size
                    val resolvedTitle = knownTopicTitles[res.topicId]
                    if (count > 0 && !resolvedTitle.isNullOrBlank()) {
                        "$count matching sections in \"$resolvedTitle\""
                    } else if (count > 0) {
                        "$count matching sections found"
                    } else "No matching sections found"
                }
                "getTopicSectionsText" -> {
                    val res = json.decodeFromString<TopicSectionsResponse>(resultText)
                    val count = res.sectionTitles.size
                    val resolvedTitle = res.topicTitle.takeIf { it.isNotBlank() && !AiJsonUtils.isNumericOnly(it) }
                        ?: knownTopicTitles[res.topicId]
                    if (!resolvedTitle.isNullOrBlank() && count > 0) {
                        "$count sections from \"$resolvedTitle\""
                    } else if (count > 0) {
                        "$count sections retrieved"
                    } else null
                }
                "getGraphicContent" -> {
                    val res = json.decodeFromString<GraphicContentResponse>(resultText)
                    if (res.title.isNotBlank()) "Table: ${res.title}" else "Table retrieved"
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseDiscoveredSources(toolName: String, resultText: String) {
        if (resultText.isBlank()) return
        try {
            val canonicalName = AiJsonUtils.normalizeToolName(toolName)
            when (canonicalName) {
                "searchTopics" -> {
                    val res = json.decodeFromString<SearchTopicsResponse>(resultText)
                    for (item in res.results) {
                        if (item.id.isNotBlank() && item.title.isNotBlank() && !AiJsonUtils.isNumericOnly(item.title)) {
                            knownTopicTitles[item.id] = item.title
                        }
                    }
                }
                "getTopicOverview" -> {
                    val res = json.decodeFromString<TopicOverviewResponse>(resultText)
                    val topicId = res.topicId
                    val title = res.title
                    if (topicId.isNotBlank() && title.isNotBlank() && !AiJsonUtils.isNumericOnly(title)) {
                        knownTopicTitles[topicId] = title
                        val current = _liveDiscoveredSources.value.toMutableList()
                        val existingIndex = current.indexOfFirst { it.topicId == topicId }
                        if (existingIndex >= 0) {
                            current[existingIndex] = current[existingIndex].copy(label = title, topicTitle = title)
                        } else {
                            current.add(SafetyValidator.TopicRef(topicId = topicId, label = title, topicTitle = title))
                        }
                        _liveDiscoveredSources.value = current
                    }
                }
                "getRelatedTopics" -> {
                    val res = json.decodeFromString<RelatedTopicsResponse>(resultText)
                    for (item in res.relatedTopics) {
                        if (item.id.isNotBlank() && item.title.isNotBlank() && !AiJsonUtils.isNumericOnly(item.title)) {
                            knownTopicTitles[item.id] = item.title
                        }
                    }
                }
                "getTopicSectionsText" -> {
                    val res = json.decodeFromString<TopicSectionsResponse>(resultText)
                    val topicId = res.topicId
                    val topicTitle = res.topicTitle
                    if (topicId.isNotBlank() && topicTitle.isNotBlank() && !AiJsonUtils.isNumericOnly(topicTitle)) {
                        knownTopicTitles[topicId] = topicTitle
                    }
                    if (res.sectionTitles.isNotEmpty() && topicTitle.isNotBlank()) {
                        val current = _liveDiscoveredSources.value.toMutableList()
                        for ((secId, secTitleVal) in res.sectionTitles) {
                            val secTitle = AiJsonUtils.cleanSectionTitle(secTitleVal)
                            val cleanSecId = if (secId.equals("FULL", ignoreCase = true)) "" else secId
                            if (secTitle.isNotBlank() && current.none { it.sectionId == cleanSecId && it.topicTitle == topicTitle }) {
                                current.add(
                                    SafetyValidator.TopicRef(
                                        topicId = topicId,
                                        sectionId = cleanSecId,
                                        label = secTitle,
                                        topicTitle = topicTitle
                                    )
                                )
                            }
                        }
                        _liveDiscoveredSources.value = current
                    }
                }
                "searchTopicSections" -> {
                    val res = json.decodeFromString<SearchTopicSectionsResponse>(resultText)
                    val topicId = res.topicId
                    if (res.matches.isNotEmpty() && topicId.isNotBlank()) {
                        val current = _liveDiscoveredSources.value.toMutableList()
                        for (item in res.matches) {
                            val secId = item.sectionId
                            val secTitle = AiJsonUtils.cleanSectionTitle(item.sectionTitle)
                            val cleanSecId = if (secId.equals("FULL", ignoreCase = true)) "" else secId
                            if (secTitle.isNotBlank() && current.none { it.topicId == topicId && it.sectionId == cleanSecId }) {
                                current.add(
                                    SafetyValidator.TopicRef(
                                        topicId = topicId,
                                        sectionId = cleanSecId,
                                        label = secTitle,
                                        topicTitle = ""
                                    )
                                )
                            }
                        }
                        _liveDiscoveredSources.value = current
                    }
                }
                "getGraphicContent" -> {
                    val res = json.decodeFromString<GraphicContentResponse>(resultText)
                    val title = res.title.trim()
                    if (title.isNotBlank()) {
                        val current = _liveDiscoveredSources.value.toMutableList()
                        if (current.none { it.label == title }) {
                            current.add(
                                SafetyValidator.TopicRef(
                                    topicId = "",
                                    sectionId = "",
                                    label = title,
                                    topicTitle = title
                                )
                            )
                            _liveDiscoveredSources.value = current
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore parsing failures in telemetry
        }
    }

    private fun classifyError(error: String): ErrorType {
        val lower = error.lowercase()
        return when {
            "api key" in lower || "unauthorized" in lower || "401" in lower -> ErrorType.INVALID_KEY
            "timeout" in lower || "deadline" in lower -> ErrorType.TIMEOUT
            "rate" in lower || "429" in lower -> ErrorType.RATE_LIMIT
            "network" in lower || "connect" in lower || "unreachable" in lower -> ErrorType.NO_NETWORK
            "no topic match" in lower || "no results for" in lower || "no clinical data" in lower -> ErrorType.NO_RESULTS
            else -> ErrorType.UNKNOWN
        }
    }
}

