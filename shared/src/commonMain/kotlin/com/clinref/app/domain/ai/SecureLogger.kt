package com.clinref.app.domain.ai

import com.clinref.shared.platform.PlatformLogger

class SecureLogger {

    private val phiPatterns = listOf(
        Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        Regex("\\b[A-Z]{2,3}\\d{6,10}\\b"),
        Regex("\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b")
    )

    private val patientProfileStart = "## Patient Profile"

    fun log(level: Level, tag: String, message: String) {
        val scrubbed = scrubPhi(message)
        when (level) {
            Level.DEBUG -> PlatformLogger.d(tag, scrubbed)
            Level.INFO -> PlatformLogger.i(tag, scrubbed)
            Level.WARN -> PlatformLogger.w(tag, scrubbed)
            Level.ERROR -> PlatformLogger.e(tag, scrubbed)
        }
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

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}
