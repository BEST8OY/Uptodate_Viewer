package com.clinref.app.domain.ai

import android.util.Log
import ai.koog.agents.core.feature.message.FeatureMessage
import ai.koog.agents.core.feature.message.FeatureMessageProcessor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A [FeatureMessageProcessor] that writes Koog trace events to Android's Log.
 *
 * Replaces [TraceFeatureMessageLogWriter] which requires kotlin-logging + SLF4J.
 * Filter Logcat by tag [TAG] to see all agent trace events.
 */
class AndroidTraceLogWriter(
    private val secureLogger: SecureLogger? = null,
    private val tag: String = TAG,
    private val minLevel: Level = Level.DEBUG
) : FeatureMessageProcessor() {

    enum class Level(val priority: Int) {
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR);

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
            Log.println(minLevel.priority, tag, rawMsg)
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
