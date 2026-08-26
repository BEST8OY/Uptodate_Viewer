package com.clinref.app.domain.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StreamingManager {

    data class TokenUsage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0
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

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _toolProgress = MutableStateFlow<ToolProgress?>(null)
    val toolProgress: StateFlow<ToolProgress?> = _toolProgress.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private var stepCounter = 0
    private var accumulatedUsage = TokenUsage()
    private val streamingBuffer = StringBuilder()

    fun onToolCallStarting(toolName: String, args: String) {
        stepCounter++
        streamingBuffer.clear()
        _streamingText.value = ""
        val description = when (toolName) {
            "quickSearchTopic" -> "Discovering topic outline\u2026"
            "searchTopics" -> "Searching topics\u2026"
            "getTopicOutline" -> "Reading topic outline\u2026"
            "getTopicSectionsText" -> "Fetching section text\u2026"
            "getGraphicContent" -> "Retrieving graphic table\u2026"
            "submitClinicalAnswer" -> "Submitting response\u2026"
            else -> "Calling $toolName\u2026"
        }
        _toolProgress.value = ToolProgress(toolName, description)
        _agentState.value = AgentState.ToolCallInProgress(toolName, args, stepCounter)
    }

    fun onToolCallCompleted(toolName: String) {
        _toolProgress.value = null
    }

    fun onWaitingForLlm() {
        streamingBuffer.clear()
        _streamingText.value = ""
        _agentState.value = AgentState.WaitingForLlm(stepCounter)
    }

    fun onStreamingTextDelta(delta: String) {
        streamingBuffer.append(delta)
        _streamingText.value = streamingBuffer.toString()
    }

    fun onLlmCallCompleted(promptTokens: Int, completionTokens: Int, totalTokens: Int) {
        accumulatedUsage = TokenUsage(
            promptTokens = accumulatedUsage.promptTokens + promptTokens,
            completionTokens = accumulatedUsage.completionTokens + completionTokens,
            totalTokens = accumulatedUsage.totalTokens + totalTokens
        )
    }

    fun onCompleted(result: String, validation: SafetyValidator.ValidationResult) {
        _toolProgress.value = null
        streamingBuffer.clear()
        _streamingText.value = ""
        _agentState.value = AgentState.Completed(result, validation, accumulatedUsage)
    }

    fun onError(error: String) {
        _toolProgress.value = null
        streamingBuffer.clear()
        _streamingText.value = ""
        val type = classifyError(error)
        _agentState.value = AgentState.Error(error, type)
    }

    fun reset() {
        stepCounter = 0
        accumulatedUsage = TokenUsage()
        streamingBuffer.clear()
        _streamingText.value = ""
        _toolProgress.value = null
        _agentState.value = AgentState.Idle
    }

    private fun classifyError(error: String): ErrorType {
        val lower = error.lowercase()
        return when {
            "api key" in lower || "unauthorized" in lower || "401" in lower -> ErrorType.INVALID_KEY
            "timeout" in lower || "deadline" in lower -> ErrorType.TIMEOUT
            "rate" in lower || "429" in lower -> ErrorType.RATE_LIMIT
            "network" in lower || "connect" in lower -> ErrorType.NO_NETWORK
            "no result" in lower || "not found" in lower -> ErrorType.NO_RESULTS
            else -> ErrorType.UNKNOWN
        }
    }
}
