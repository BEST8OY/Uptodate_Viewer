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
        // Session watchdog. Rationale (post 3c51656): the strategy graph is
        // self-terminating — MAX_TOOL_ROUNDS=24 forced validation exit + ≤2
        // premature + ≤2 remediation cycles bound the run to ~30 LLM calls, so
        // the watchdog's job is only to catch pathological hangs, not to race
        // convergent runs (per-call HTTP socket timeouts already police true
        // dead connections). 120s killed legitimate multi-round runs on mobile
        // networks, where a single cloud call commonly takes 10-20s on LTE.
        // 600s ≈ full worst-case graph progression with margin on degraded
        // networks. Python carries no session cap at all.
        const val CLOUD_AGENT_TIMEOUT_MS = 600_000L // 10 minutes session watchdog for Cloud LLMs
        const val LOCAL_AGENT_TIMEOUT_MS = 180_000L // 3 minutes session watchdog for Local Ollama
    }
}
