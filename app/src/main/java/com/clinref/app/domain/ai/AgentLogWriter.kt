package com.clinref.app.domain.ai

import ai.koog.agents.features.tracing.writer.TraceFeatureMessageLogWriter
import io.github.microutils.kotlinlogging.KotlinLogging

/**
 * Creates a TraceFeatureMessageLogWriter using kotlin-logging's KLogger.
 */
fun createTraceLogWriter(): TraceFeatureMessageLogWriter {
    val logger = KotlinLogging.logger("KoogAgent")
    return TraceFeatureMessageLogWriter(logger)
}
