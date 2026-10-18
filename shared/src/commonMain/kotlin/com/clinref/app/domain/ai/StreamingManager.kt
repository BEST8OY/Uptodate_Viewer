package com.clinref.app.domain.ai

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

    fun onToolCallStarting(toolName: String, args: String) {
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

        val stepId = "step_$stepCounter"
        val (title, detail) = getStepInfo(toolName, args)

        // Complete any previously in-progress steps
        _orchestrationSteps.value = _orchestrationSteps.value.map { step ->
            if (step.status == StepStatus.IN_PROGRESS) {
                step.copy(status = StepStatus.COMPLETED)
            } else step
        } + OrchestrationStep(
            id = stepId,
            title = title,
            detail = detail,
            status = StepStatus.IN_PROGRESS,
            toolName = toolName
        )

        val description = when (toolName) {
            "searchTopics" -> "Searching medical topics…"
            "getTopicOverview" -> "Retrieving topic overview & core evidence…"
            "getTopicOutline" -> "Reading topic outline…"
            "getRelatedTopics" -> "Finding related clinical topics…"
            "getTopicSectionsText" -> "Retrieving evidence sections…"
            "getGraphicContent" -> "Loading clinical reference table…"
            else -> "Executing $toolName…"
        }
        _currentStatusText.value = description
        _toolProgress.value = ToolProgress(toolName, description)
        _agentState.value = AgentState.ToolCallInProgress(toolName, args, stepCounter)
    }

    fun onToolCallCompleted(toolName: String, resultText: String = "") {
        activeToolCalls = (activeToolCalls - 1).coerceAtLeast(0)

        // Parse any discovered source titles or tables
        parseDiscoveredSources(toolName, resultText)

        // Mark the active step as completed
        _orchestrationSteps.value = _orchestrationSteps.value.map { step ->
            if (step.toolName == toolName && step.status == StepStatus.IN_PROGRESS) {
                val completionDetail = getCompletionDetail(toolName, resultText) ?: step.detail
                step.copy(status = StepStatus.COMPLETED, detail = completionDetail)
            } else step
        }

        _currentStatusText.value = "Evaluating clinical evidence…"
        _toolProgress.value = ToolProgress(toolName, "Evaluating clinical evidence…")
    }

    fun onWaitingForLlm() {
        if (activeToolCalls > 0) return
        _currentStatusText.value = "Reasoning over clinical findings…"
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
        _currentStatusText.value = "Initializing clinical core…"
        _agentState.value = AgentState.Idle
    }

    private fun getStepInfo(toolName: String, args: String): Pair<String, String?> {
        return try {
            val canonicalName = AiJsonUtils.normalizeToolName(toolName)
            val element = AiJsonUtils.parseAsJsonObject(args)
            when (canonicalName) {
                "searchTopics" -> {
                    val query = element?.get("query")?.jsonPrimitive?.content ?: ""
                    "Search Knowledge Base" to if (query.isNotBlank()) "Query: \"$query\"" else null
                }
                "searchTopicSections" -> {
                    val query = element?.get("query")?.jsonPrimitive?.content ?: ""
                    "Search In-Topic Sections" to if (query.isNotBlank()) "Query: \"$query\"" else null
                }
                "getTopicOverview" -> {
                    val topicId = element?.get("topicId")?.jsonPrimitive?.content
                        ?: element?.get("topic_id")?.jsonPrimitive?.content ?: ""
                    "Examine Topic Overview" to if (topicId.isNotBlank()) "Topic ID: $topicId" else null
                }
                "getTopicOutline" -> {
                    val topicId = element?.get("topicId")?.jsonPrimitive?.content
                        ?: element?.get("topic_id")?.jsonPrimitive?.content ?: ""
                    "Examine Topic Outline" to if (topicId.isNotBlank()) "Topic ID: $topicId" else null
                }
                "getRelatedTopics" -> {
                    "Expand Topic Pool" to "Retrieving related sub-topics"
                }
                "getTopicSectionsText" -> {
                    "Retrieve Clinical Evidence" to "Fetching evidence sections"
                }
                "getGraphicContent" -> {
                    val graphicId = element?.get("graphicId")?.jsonPrimitive?.content
                        ?: element?.get("graphic_id")?.jsonPrimitive?.content ?: ""
                    "Load Reference Table" to if (graphicId.isNotBlank()) "Table ID: $graphicId" else null
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
            val element = AiJsonUtils.parseAsJsonObject(resultText)
            when (canonicalName) {
                "searchTopics" -> {
                    if (element == null) return null
                    val results = element["results"] as? kotlinx.serialization.json.JsonArray
                    val count = results?.size ?: 0
                    if (count > 0) {
                        "$count candidate topics identified"
                    } else {
                        val message = element["message"]?.jsonPrimitive?.contentOrNull
                        if (!message.isNullOrBlank() && message.startsWith("Auto-refined", ignoreCase = true)) {
                            message
                        } else {
                            "No topics found"
                        }
                    }
                }
                "getTopicOverview" -> {
                    val title = element?.get("title")?.jsonPrimitive?.content
                        ?: element?.get("topic_title")?.jsonPrimitive?.content
                    val count = (element?.get("sections") as? kotlinx.serialization.json.JsonArray)?.size
                        ?: (element?.get("outline") as? kotlinx.serialization.json.JsonArray)?.size ?: 0
                    if (!title.isNullOrBlank() && !AiJsonUtils.isNumericOnly(title)) {
                        "Overview: \"$title\" ($count sections)"
                    } else {
                        "Topic overview loaded ($count sections)"
                    }
                }
                "getTopicOutline" -> {
                    val title = element?.get("title")?.jsonPrimitive?.content
                    if (!title.isNullOrBlank() && !AiJsonUtils.isNumericOnly(title)) "Outline: $title" else "Outline loaded"
                }
                "searchTopicSections" -> {
                    val matches = element?.get("matches") as? kotlinx.serialization.json.JsonArray
                    val count = matches?.size ?: 0
                    if (count > 0) "$count matching sections found" else "No matching sections found"
                }
                "getTopicSectionsText" -> {
                    val topicTitle = element?.get("topicTitle")?.jsonPrimitive?.content
                    val sectionTitles = element?.get("sectionTitles") as? JsonObject
                    val count = sectionTitles?.size ?: 0
                    if (!topicTitle.isNullOrBlank() && !AiJsonUtils.isNumericOnly(topicTitle)) {
                        "$count sections from \"$topicTitle\""
                    } else if (count > 0) {
                        "$count sections retrieved"
                    } else null
                }
                "getGraphicContent" -> {
                    val unquoted = AiJsonUtils.extractJsonString(resultText)
                    val match = AiJsonUtils.GRAPHIC_TABLE_REGEX.find(unquoted)
                    if (match != null) "Table: ${match.groupValues[1].trim()}" else "Table retrieved"
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
            val element = AiJsonUtils.parseAsJsonObject(resultText)
            when (canonicalName) {
                "getTopicOutline" -> {
                    if (element == null) return
                    val topicId = element["topicId"]?.jsonPrimitive?.content
                        ?: element["topic_id"]?.jsonPrimitive?.content ?: ""
                    val title = element["title"]?.jsonPrimitive?.content ?: ""
                    if (topicId.isNotBlank() && title.isNotBlank() && !AiJsonUtils.isNumericOnly(title)) {
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
                "searchTopicSections" -> {
                    if (element == null) return
                    val topicId = element["topicId"]?.jsonPrimitive?.content
                        ?: element["topic_id"]?.jsonPrimitive?.content ?: ""
                    val matches = element["matches"] as? kotlinx.serialization.json.JsonArray
                    if (matches != null && topicId.isNotBlank()) {
                        val current = _liveDiscoveredSources.value.toMutableList()
                        for (item in matches) {
                            val itemObj = item as? JsonObject ?: continue
                            val secId = itemObj["sectionId"]?.jsonPrimitive?.content ?: continue
                            val secTitle = AiJsonUtils.cleanSectionTitle(itemObj["sectionTitle"]?.jsonPrimitive?.content ?: "")
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
                "getTopicSectionsText" -> {
                    if (element == null) return
                    val topicId = element["topicId"]?.jsonPrimitive?.content
                        ?: element["topic_id"]?.jsonPrimitive?.content ?: ""
                    val topicTitle = element["topicTitle"]?.jsonPrimitive?.content ?: ""
                    val sectionTitles = element["sectionTitles"] as? JsonObject
                    if (sectionTitles != null && topicTitle.isNotBlank()) {
                        val current = _liveDiscoveredSources.value.toMutableList()
                        for ((secId, secTitleObj) in sectionTitles) {
                            val secTitle = AiJsonUtils.cleanSectionTitle(secTitleObj.jsonPrimitive.content)
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
                "getGraphicContent" -> {
                    val unquoted = AiJsonUtils.extractJsonString(resultText)
                    val match = AiJsonUtils.GRAPHIC_TABLE_REGEX.find(unquoted)
                    if (match != null) {
                        val title = match.groupValues[1].trim()
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

