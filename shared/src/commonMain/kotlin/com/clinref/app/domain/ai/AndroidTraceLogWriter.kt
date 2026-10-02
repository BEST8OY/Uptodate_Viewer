package com.clinref.app.domain.ai

import ai.koog.agents.core.feature.message.FeatureMessage
import ai.koog.agents.core.feature.message.FeatureMessageProcessor
import com.clinref.shared.platform.PlatformLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [FeatureMessageProcessor] that writes Koog trace events to the platform logger.
 */
class PlatformTraceLogWriter(
    private val secureLogger: SecureLogger? = null,
    private val tag: String = TAG,
    private val minLevel: Level = Level.DEBUG
) : FeatureMessageProcessor() {

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR;

        fun toSecureLoggerLevel(): SecureLogger.Level = when (this) {
            DEBUG -> SecureLogger.Level.DEBUG
            INFO -> SecureLogger.Level.INFO
            WARN -> SecureLogger.Level.WARN
            ERROR -> SecureLogger.Level.ERROR
        }
    }

    override val isOpen: StateFlow<Boolean> = MutableStateFlow(true)

    override suspend fun processMessage(message: FeatureMessage) {
        val rawMsg = message.toLogString()
        if (secureLogger != null) {
            secureLogger.log(minLevel.toSecureLoggerLevel(), tag, rawMsg)
        } else {
            when (minLevel) {
                Level.DEBUG -> PlatformLogger.d(tag, rawMsg)
                Level.INFO -> PlatformLogger.i(tag, rawMsg)
                Level.WARN -> PlatformLogger.w(tag, rawMsg)
                Level.ERROR -> PlatformLogger.e(tag, rawMsg)
            }
        }
    }

    override suspend fun close() {}

    private fun FeatureMessage.toLogString(): String {
        val type = this::class.simpleName ?: "Unknown"
        return "[$type] $this"
    }

    companion object {
        private const val TAG = "KoogTrace"
    }
}

typealias AndroidTraceLogWriter = PlatformTraceLogWriter
