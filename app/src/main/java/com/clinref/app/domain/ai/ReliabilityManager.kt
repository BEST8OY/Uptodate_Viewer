package com.clinref.app.domain.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class ReliabilityManager {

    suspend fun <T> runWithTimeout(
        timeoutMs: Long = TOOL_TIMEOUT_MS,
        block: suspend () -> T
    ): T = withTimeout(timeoutMs) { block() }

    suspend fun <T> withRetry(
        maxRetries: Int = MAX_RETRIES,
        initialDelayMs: Long = INITIAL_DELAY_MS,
        factor: Double = BACKOFF_FACTOR,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong()
                }
            }
        }
        throw lastException ?: IllegalStateException("Retry failed without exception")
    }

    companion object {
        const val TOOL_TIMEOUT_MS = 45_000L
        const val LOCAL_TIMEOUT_MS = 90_000L
        const val MAX_RETRIES = 3
        const val INITIAL_DELAY_MS = 1_000L
        const val BACKOFF_FACTOR = 2.0
    }
}
