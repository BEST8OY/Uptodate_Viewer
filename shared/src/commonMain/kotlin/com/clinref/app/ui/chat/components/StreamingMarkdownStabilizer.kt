package com.clinref.app.ui.chat.components

/**
 * Stabilizes partially streamed Markdown so live token updates render cleanly without
 * flashing unclosed emphasis markers or half-formed code fences.
 *
 * Only applied while [isStreaming] is true; completed messages render verbatim.
 */
object StreamingMarkdownStabilizer {

    fun stabilize(raw: String): String {
        if (raw.isBlank()) return raw

        val sb = StringBuilder(raw.length + 8).append(raw)

        // 1. Close unclosed fenced code block (```)
        var fenceCount = 0
        var idx = 0
        while (idx < raw.length) {
            val lineEnd = raw.indexOf('\n', idx).let { if (it == -1) raw.length else it }
            if (raw.substring(idx, lineEnd).trimStart().startsWith("```")) fenceCount++
            idx = lineEnd + 1
        }
        if (fenceCount % 2 == 1) {
            if (!raw.endsWith("\n")) sb.append('\n')
            sb.append("```")
            return sb.toString()
        }

        // 2. Fix dangling inline tokens on the active trailing line
        val trailingStart = sb.lastIndexOf('\n') + 1
        val trailingLine = sb.substring(trailingStart)

        if (trailingLine.count { it == '`' } % 2 == 1) {
            sb.append('`')
            return sb.toString()
        }

        if (countNonOverlapping(trailingLine, "**") % 2 == 1) {
            val trimmed = trailingLine.trimEnd()
            if (trimmed.endsWith("**")) {
                val trimIdx = sb.lastIndexOf("**")
                if (trimIdx >= trailingStart) sb.delete(trimIdx, sb.length)
            } else {
                val trailingSpaces = trailingLine.length - trimmed.length
                sb.insert(sb.length - trailingSpaces, "**")
            }
        }

        return sb.toString()
    }

    private fun countNonOverlapping(text: String, needle: String): Int {
        var count = 0
        var index = 0
        while (true) {
            val found = text.indexOf(needle, index)
            if (found < 0) break
            count++
            index = found + needle.length
        }
        return count
    }
}
