package com.clinref.app.domain.ai

import android.util.Log
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Creates a TraceFeatureMessageLogWriter that routes Koog trace events to Android Logcat.
 * Uses java.util.logging.Logger which TraceFeatureMessageLogWriter expects.
 */
fun createTraceLogWriter(): TraceFeatureMessageLogWriter {
    val logger = Logger.getLogger("KoogAgent")
    logger.level = Level.ALL
    logger.addHandler(object : java.util.logging.Handler() {
        override fun publish(record: java.util.logging.LogRecord?) {
            record ?: return
            val msg = record.message ?: return
            when (record.level) {
                Level.SEVERE -> Log.e("KoogAgent", msg)
                Level.WARNING -> Log.w("KoogAgent", msg)
                Level.INFO -> Log.i("KoogAgent", msg)
                else -> Log.d("KoogAgent", msg)
            }
        }
        override fun flush() {}
        override fun close() {}
    })
    return TraceFeatureMessageLogWriter(logger)
}
