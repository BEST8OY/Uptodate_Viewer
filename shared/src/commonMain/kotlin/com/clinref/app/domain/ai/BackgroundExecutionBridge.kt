package com.clinref.app.domain.ai

/**
 * Platform bridge interface allowing the shared clinical core to notify host OS
 * environments (e.g. Android Foreground Services) when an AI generation session starts,
 * updates progress, finishes, or is cancelled.
 */
interface BackgroundExecutionBridge {
    fun onExecutionStarted(conversationId: String, prompt: String)
    fun onExecutionProgress(conversationId: String, stepText: String)
    fun onExecutionCompleted(conversationId: String, summary: String?, isError: Boolean)
    fun onExecutionCancelled(conversationId: String)
}

/**
 * Default no-op implementation for platforms that do not require OS-level background services (Desktop JVM).
 */
class NoOpBackgroundExecutionBridge : BackgroundExecutionBridge {
    override fun onExecutionStarted(conversationId: String, prompt: String) {}
    override fun onExecutionProgress(conversationId: String, stepText: String) {}
    override fun onExecutionCompleted(conversationId: String, summary: String?, isError: Boolean) {}
    override fun onExecutionCancelled(conversationId: String) {}
}

