package com.clinref.app.domain.ai

import android.util.Log
import ai.koog.agents.core.feature.message.FeatureMessage
import ai.koog.agents.core.feature.message.FeatureMessageProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Custom Logcat-based message processor for Koog tracing.
 * Logs tool calls, LLM calls, and agent events to Android Logcat.
 */
class AgentLogWriter : FeatureMessageProcessor {

    override val messageFilter: (FeatureMessage) -> Boolean = { true }

    override fun setMessageFilter(filter: (FeatureMessage) -> Boolean) {
        // No-op — we log everything
    }

    override suspend fun initialize() {
        Log.d(TAG, "AgentLogWriter initialized")
    }

    override suspend fun onMessage(message: FeatureMessage) {
        val msg = message.toString()
        when {
            msg.contains("ToolCall") -> Log.d(TAG, "[TRACE] $msg")
            msg.contains("LLMCall") -> Log.d(TAG, "[TRACE] $msg")
            msg.contains("Agent") -> Log.d(TAG, "[TRACE] $msg")
            msg.contains("Node") -> Log.v(TAG, "[TRACE] $msg")
            else -> Log.v(TAG, "[TRACE] $msg")
        }
    }

    override suspend fun close() {
        Log.d(TAG, "AgentLogWriter closed")
    }

    companion object {
        private const val TAG = "KoogAgent"
    }
}
