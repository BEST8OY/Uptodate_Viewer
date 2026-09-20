package com.clinref.app.domain.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    private var stepCounter = 0
    private var activeToolCalls = 0
    private var accumulatedUsage = TokenUsage()
    private val streamingBuffer = StringBuilder()

    // Answer text extraction state from tool call args streaming
    private val toolCallStreamBuffer = StringBuilder()
    private var isStreamingAnswerText = false
    private var answerKeyFound = false
    private var answerExtractIndex = 0

    fun onToolCallStarting(toolName: String, args: String) {
        stepCounter++
        activeToolCalls++

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
            "getTopicOutline" -> "Reading topic outline…"
            "getRelatedTopics" -> "Finding related clinical topics…"
            "getTopicSectionsText" -> "Retrieving evidence sections…"
            "getGraphicContent" -> "Loading clinical reference table…"
            "submitClinicalAnswer" -> "Synthesizing clinical response…"
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

        if (toolName != "submitClinicalAnswer") {
            _currentStatusText.value = "Evaluating clinical evidence…"
            _toolProgress.value = ToolProgress(toolName, "Evaluating clinical evidence…")
        }
    }

    fun onWaitingForLlm() {
        if (activeToolCalls > 0) return
        _currentStatusText.value = "Reasoning over clinical findings…"
        _agentState.value = AgentState.WaitingForLlm(stepCounter)
    }

    fun onStreamingTextDelta(delta: String) {
        streamingBuffer.append(delta)
        _streamingText.value = streamingBuffer.toString()
    }

    fun onStreamingReasoningDelta(delta: String) {
        // Reserved for models with chain-of-thought streaming (e.g. Gemini Thinking, o-series)
    }

    fun onStreamingToolCallDelta(callId: String, content: String, toolName: String? = null) {
        if (content.isEmpty()) return
        toolCallStreamBuffer.append(content)

        // Extract streaming answer text if this is submitClinicalAnswer or contains answerText
        extractStreamingAnswerText()
    }

    private fun extractStreamingAnswerText() {
        val raw = toolCallStreamBuffer.toString()
        if (!answerKeyFound) {
            val keyPattern = Regex(""""(?:answerText|answer)"\s*:\s*"""")
            val match = keyPattern.find(raw)
            if (match != null) {
                answerKeyFound = true
                isStreamingAnswerText = true
                answerExtractIndex = match.range.last + 1
            }
        }

        if (isStreamingAnswerText && answerExtractIndex < raw.length) {
            val sb = StringBuilder()
            var i = answerExtractIndex
            var inEscape = false

            while (i < raw.length) {
                val c = raw[i]
                if (inEscape) {
                    when (c) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'u' -> {
                            if (i + 4 < raw.length) {
                                val hex = raw.substring(i + 1, i + 5)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 4
                            }
                        }
                        else -> sb.append(c)
                    }
                    inEscape = false
                    i++
                } else if (c == '\\') {
                    inEscape = true
                    i++
                } else if (c == '"') {
                    // Reached unescaped ending quote of answer string
                    isStreamingAnswerText = false
                    answerExtractIndex = i + 1
                    break
                } else {
                    sb.append(c)
                    i++
                }
            }

            if (sb.isNotEmpty()) {
                answerExtractIndex = i
                streamingBuffer.append(sb.toString())
                _streamingText.value = streamingBuffer.toString()
            }
        }
    }

    fun onStreamingEnd() {
        // LLM token emission completed for current turn
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
        toolCallStreamBuffer.clear()
        isStreamingAnswerText = false
        answerKeyFound = false
        answerExtractIndex = 0
        _streamingText.value = ""
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
        toolCallStreamBuffer.clear()
        isStreamingAnswerText = false
        answerKeyFound = false
        answerExtractIndex = 0
        _streamingText.value = ""
        val type = classifyError(error)
        _agentState.value = AgentState.Error(error, type)
    }

    fun reset() {
        stepCounter = 0
        activeToolCalls = 0
        accumulatedUsage = TokenUsage()
        streamingBuffer.clear()
        toolCallStreamBuffer.clear()
        isStreamingAnswerText = false
        answerKeyFound = false
        answerExtractIndex = 0
        _streamingText.value = ""
        _toolProgress.value = null
        _orchestrationSteps.value = emptyList()
        _liveDiscoveredSources.value = emptyList()
        _currentStatusText.value = "Initializing clinical core…"
        _agentState.value = AgentState.Idle
    }

    private fun getStepInfo(toolName: String, args: String): Pair<String, String?> {
        return try {
            val element = json.parseToJsonElement(args) as? JsonObject
            when (toolName) {
                "searchTopics" -> {
                    val query = element?.get("query")?.jsonPrimitive?.content ?: ""
                    "Search Knowledge Base" to if (query.isNotBlank()) "Query: \"$query\"" else null
                }
                "getTopicOutline" -> {
                    val topicId = element?.get("topicId")?.jsonPrimitive?.content ?: ""
                    "Examine Topic Outline" to if (topicId.isNotBlank()) "Topic ID: $topicId" else null
                }
                "getRelatedTopics" -> {
                    "Expand Topic Pool" to "Retrieving related sub-topics"
                }
                "getTopicSectionsText" -> {
                    "Retrieve Clinical Evidence" to "Fetching evidence sections"
                }
                "getGraphicContent" -> {
                    val graphicId = element?.get("graphicId")?.jsonPrimitive?.content ?: ""
                    "Load Reference Table" to if (graphicId.isNotBlank()) "Table ID: $graphicId" else null
                }
                "submitClinicalAnswer" -> {
                    "Synthesize Clinical Guidance" to "Formatting evidence-based response"
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
            val element = json.parseToJsonElement(resultText) as? JsonObject ?: return null
            when (toolName) {
                "getTopicOutline" -> {
                    val title = element["title"]?.jsonPrimitive?.content
                    if (!title.isNullOrBlank()) "Outline: $title" else null
                }
                "getTopicSectionsText" -> {
                    val topicTitle = element["topicTitle"]?.jsonPrimitive?.content
                    val sectionTitles = element["sectionTitles"] as? JsonObject
                    val count = sectionTitles?.size ?: 0
                    if (!topicTitle.isNullOrBlank()) {
                        "$count sections from \"$topicTitle\""
                    } else if (count > 0) {
                        "$count sections retrieved"
                    } else null
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
            val element = json.parseToJsonElement(resultText) as? JsonObject ?: return
            when (toolName) {
                "getTopicOutline" -> {
                    val topicId = element["topicId"]?.jsonPrimitive?.content ?: ""
                    val title = element["title"]?.jsonPrimitive?.content ?: ""
                    if (topicId.isNotBlank() && title.isNotBlank()) {
                        val current = _liveDiscoveredSources.value.toMutableList()
                        if (current.none { it.topicId == topicId }) {
                            current.add(SafetyValidator.TopicRef(topicId = topicId, label = title, topicTitle = title))
                            _liveDiscoveredSources.value = current
                        }
                    }
                }
                "getTopicSectionsText" -> {
                    val topicTitle = element["topicTitle"]?.jsonPrimitive?.content ?: ""
                    val sectionTitles = element["sectionTitles"] as? JsonObject
                    if (sectionTitles != null && topicTitle.isNotBlank()) {
                        val current = _liveDiscoveredSources.value.toMutableList()
                        for ((secId, secTitleObj) in sectionTitles) {
                            val secTitle = secTitleObj.jsonPrimitive.content.trimStart('-', '–', '—').trim()
                            if (secTitle.isNotBlank() && current.none { it.sectionId == secId }) {
                                current.add(
                                    SafetyValidator.TopicRef(
                                        topicId = "",
                                        sectionId = secId,
                                        label = secTitle,
                                        topicTitle = topicTitle
                                    )
                                )
                            }
                        }
                        _liveDiscoveredSources.value = current
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

