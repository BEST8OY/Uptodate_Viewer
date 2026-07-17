package com.clinref.app.domain.ai

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class ReliabilityManager {

    suspend fun <T> withTimeout(
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
        const val TOOL_TIMEOUT_MS = 30_000L
        const val MAX_RETRIES = 3
        const val INITIAL_DELAY_MS = 1_000L
        const val BACKOFF_FACTOR = 2.0
    }
}

class SecureLogger {

    private val phiPatterns = listOf(
        Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        Regex("\\b[A-Z]{2,3}\\d{6,10}\\b"),
        Regex("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b")
    )

    private val patientProfileStart = "## Patient Profile"

    fun log(level: Level, tag: String, message: String) {
        val scrubbed = scrubPhi(message)
        Log.println(level.priority, tag, scrubbed)
    }

    private fun scrubPhi(message: String): String {
        // Remove entire patient profile block
        val profileStart = message.indexOf(patientProfileStart)
        if (profileStart >= 0) {
            val afterProfile = message.indexOf("\n\n", profileStart + patientProfileStart.length)
            val cleanMessage = if (afterProfile >= 0) {
                message.substring(0, profileStart) + message.substring(afterProfile)
            } else {
                message.substring(0, profileStart)
            }
            return applyRegexScrubbing(cleanMessage)
        }
        return applyRegexScrubbing(message)
    }

    private fun applyRegexScrubbing(text: String): String {
        return phiPatterns.fold(text) { acc, regex ->
            regex.replace(acc, "[REDACTED]")
        }
    }

    enum class Level(val priority: Int) {
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR)
    }
}
