package com.clinref.app.domain.ai

import android.content.Context
import ai.koog.agents.features.tracing.writer.TraceFeatureMessageFileWriter
import java.io.File

/**
 * Creates a TraceFeatureMessageFileWriter that writes trace events to a file.
 * Read with: adb pull /data/data/com.clinref.app/files/traces/agent_trace.log
 */
fun createTraceFileWriter(context: Context): TraceFeatureMessageFileWriter<java.nio.file.Path> {
    val traceDir = File(context.filesDir, "traces")
    traceDir.mkdirs()
    val traceFile = File(traceDir, "agent_trace.log")
    return TraceFeatureMessageFileWriter.create(traceFile.toPath())
}
