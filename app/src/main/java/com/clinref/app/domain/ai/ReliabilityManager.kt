package com.clinref.app.domain.ai

import kotlinx.coroutines.withTimeout

class ReliabilityManager {

    suspend fun <T> runWithTimeout(
        timeoutMs: Long,
        block: suspend () -> T
    ): T = withTimeout(timeoutMs) { block() }

    fun getAgentTimeoutMs(provider: AiProvider): Long {
        return if (provider == AiProvider.OLLAMA) LOCAL_AGENT_TIMEOUT_MS else CLOUD_AGENT_TIMEOUT_MS
    }

    companion object {
        const val CLOUD_AGENT_TIMEOUT_MS = 120_000L // 2 minutes session watchdog for Cloud LLMs
        const val LOCAL_AGENT_TIMEOUT_MS = 180_000L // 3 minutes session watchdog for Local Ollama
    }
}
