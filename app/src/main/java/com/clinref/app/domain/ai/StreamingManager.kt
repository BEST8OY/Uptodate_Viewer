package com.clinref.app.domain.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StreamingManager {

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
            val validation: SafetyValidator.ValidationResult
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

    private var stepCounter = 0

    fun onToolCallStarting(toolName: String, args: String) {
        stepCounter++
        val description = when (toolName) {
            "searchTopics" -> "Searching topics\u2026"
            "getTopicOutline" -> "Reading topic outline\u2026"
            "getTopicSectionText" -> "Reading section content\u2026"
            else -> "Calling $toolName\u2026"
        }
        _toolProgress.value = ToolProgress(toolName, description)
        _agentState.value = AgentState.ToolCallInProgress(toolName, args, stepCounter)
    }

    fun onToolCallCompleted(toolName: String) {
        _toolProgress.value = null
    }

    fun onWaitingForLlm() {
        _agentState.value = AgentState.WaitingForLlm(stepCounter)
    }

    fun onCompleted(result: String, validation: SafetyValidator.ValidationResult) {
        _toolProgress.value = null
        _agentState.value = AgentState.Completed(result, validation)
    }

    fun onError(error: String) {
        _toolProgress.value = null
        val type = classifyError(error)
        _agentState.value = AgentState.Error(error, type)
    }

    fun reset() {
        stepCounter = 0
        _toolProgress.value = null
        _agentState.value = AgentState.Idle
    }

    private fun classifyError(error: String): ErrorType {
        val lower = error.lowercase()
        return when {
            "api key" in lower || "unauthorized" in lower || "401" in lower -> ErrorType.INVALID_KEY
            "network" in lower || "connect" in lower || "timeout" in lower -> ErrorType.NO_NETWORK
            "rate" in lower || "429" in lower -> ErrorType.RATE_LIMIT
            "timeout" in lower || "deadline" in lower -> ErrorType.TIMEOUT
            "no result" in lower || "not found" in lower -> ErrorType.NO_RESULTS
            else -> ErrorType.UNKNOWN
        }
    }
}
