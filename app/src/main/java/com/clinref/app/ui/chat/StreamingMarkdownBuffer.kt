package com.clinref.app.ui.chat

/**
 * Gemini-style streaming markdown buffer.
 *
 * Tracks open syntax states (code fences, inline code, bold, italic, tables)
 * and only flushes completed blocks to the renderer. Incomplete blocks are
 * held in a pending buffer and shown as plain text until the closing tag arrives.
 *
 * This eliminates visual flickering during LLM streaming — no auto-closing
 * fake tags, no re-parsing the entire string on every token.
 */
class StreamingMarkdownBuffer {

    // Block-level states
    private var openCodeFence = false
    private var openTableRow = false

    // Inline states
    private var openInlineCode = false
    private var openBold = 0
    private var openItalic = 0

    /**
     * Process accumulated text and return safe-to-render markdown.
     *
     * @param accumulatedText The full raw text received so far from the LLM.
     * @return A [RenderState] with completed blocks and pending plain text.
     */
    fun process(accumulatedText: String): RenderState {
        if (accumulatedText.isEmpty()) return RenderState("", "")

        // Reset and scan entire text to determine current state
        reset()
        scanAll(accumulatedText)

        // Find where safe-to-render content ends
        val flushPoint = findFlushPoint(accumulatedText)

        val rendered = accumulatedText.substring(0, flushPoint)
        val pending = accumulatedText.substring(flushPoint)

        return RenderState(rendered, pending)
    }

    data class RenderState(
        val renderedMarkdown: String,
        val pendingPlainText: String,
    )

    private fun reset() {
        openCodeFence = false
        openTableRow = false
        openInlineCode = false
        openBold = 0
        openItalic = 0
    }

    /**
     * Scan the entire text to determine the final syntax state.
     * We only care about the END state, not intermediate transitions.
     */
    private fun scanAll(text: String) {
        var i = 0
        while (i < text.length) {
            // Code fence (```)
            if (!openInlineCode && i + 2 < text.length &&
                text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`'
            ) {
                // Must be at start of line (after optional whitespace)
                val lineStart = text.lastIndexOf('\n', i - 1).let { if (it < 0) 0 else it + 1 }
                val prefix = text.substring(lineStart, i)
                if (prefix.isBlank()) {
                    openCodeFence = !openCodeFence
                    i += 3
                    // Skip language identifier after opening fence
                    if (!openCodeFence) {
                        // Just closed — skip to end of line
                        val nl = text.indexOf('\n', i)
                        i = if (nl >= 0) nl + 1 else text.length
                    } else {
                        // Just opened — skip to end of line
                        val nl = text.indexOf('\n', i)
                        i = if (nl >= 0) nl + 1 else text.length
                    }
                    continue
                }
            }

            // Inline code (`)
            if (!openCodeFence && text[i] == '`') {
                openInlineCode = !openInlineCode
                i++
                continue
            }

            // Skip inline formatting inside code contexts
            if (openCodeFence || openInlineCode) {
                i++
                continue
            }

            // Bold (** or __)
            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' &&
                (i == 0 || text[i - 1] != '*') &&
                (i + 2 >= text.length || text[i + 2] != '*')
            ) {
                if (openBold > 0) {
                    openBold--
                } else {
                    openBold++
                }
                i += 2
                continue
            }
            if (i + 1 < text.length && text[i] == '_' && text[i + 1] == '_' &&
                (i == 0 || text[i - 1] != '_') &&
                (i + 2 >= text.length || text[i + 2] != '_')
            ) {
                if (openBold > 0) {
                    openBold--
                } else {
                    openBold++
                }
                i += 2
                continue
            }

            // Italic (* or _)
            if (text[i] == '*' && (i == 0 || text[i - 1] != '*')) {
                if (openItalic > 0) {
                    openItalic--
                } else {
                    openItalic++
                }
                i++
                continue
            }
            if (text[i] == '_' && (i == 0 || text[i - 1] != '_')) {
                if (openItalic > 0) {
                    openItalic--
                } else {
                    openItalic++
                }
                i++
                continue
            }

            // Table row (|)
            if (text[i] == '|') {
                openTableRow = true
                i++
                continue
            }

            // Newline resets table row and inline formatting
            if (text[i] == '\n') {
                openTableRow = false
                // Newline also closes italic (common markdown behavior)
                if (openItalic > 0) openItalic = 0
                i++
                continue
            }

            i++
        }
    }

    /**
     * Find the last position where all syntax states are closed.
     * Content up to this point is safe to render as markdown.
     */
    private fun findFlushPoint(text: String): Int {
        var bestFlush = 0
        var i = 0
        var tempBold = 0
        var tempItalic = 0
        var tempCodeFence = false
        var tempInlineCode = false
        var tempTableRow = false

        while (i < text.length) {
            // Code fence
            if (!tempInlineCode && i + 2 < text.length &&
                text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`'
            ) {
                val lineStart = text.lastIndexOf('\n', i - 1).let { if (it < 0) 0 else it + 1 }
                val prefix = text.substring(lineStart, i)
                if (prefix.isBlank()) {
                    tempCodeFence = !tempCodeFence
                    i += 3
                    val nl = text.indexOf('\n', i)
                    i = if (nl >= 0) nl + 1 else text.length
                    if (!allClosed(tempCodeFence, tempInlineCode, tempBold, tempItalic, tempTableRow)) {
                        // State is open — save flush point BEFORE this fence
                        bestFlush = i - (if (nl >= 0) nl - i + 3 else text.length - i + 3)
                        bestFlush = (text.lastIndexOf('\n', i - 1).let { if (it < 0) 0 else it + 1 }).coerceAtMost(i - 3)
                    } else {
                        bestFlush = i
                    }
                    continue
                }
            }

            // Inline code
            if (!tempCodeFence && text[i] == '`') {
                tempInlineCode = !tempInlineCode
                i++
                if (!allClosed(tempCodeFence, tempInlineCode, tempBold, tempItalic, tempTableRow)) {
                    // Just opened inline code — flush up to here
                    bestFlush = i
                } else {
                    bestFlush = i
                }
                continue
            }

            if (tempCodeFence || tempInlineCode) {
                i++
                continue
            }

            // Bold
            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' &&
                (i == 0 || text[i - 1] != '*') &&
                (i + 2 >= text.length || text[i + 2] != '*')
            ) {
                if (tempBold > 0) tempBold-- else tempBold++
                i += 2
                if (allClosed(tempCodeFence, tempInlineCode, tempBold, tempItalic, tempTableRow)) {
                    bestFlush = i
                }
                continue
            }
            if (i + 1 < text.length && text[i] == '_' && text[i + 1] == '_' &&
                (i == 0 || text[i - 1] != '_') &&
                (i + 2 >= text.length || text[i + 2] != '_')
            ) {
                if (tempBold > 0) tempBold-- else tempBold++
                i += 2
                if (allClosed(tempCodeFence, tempInlineCode, tempBold, tempItalic, tempTableRow)) {
                    bestFlush = i
                }
                continue
            }

            // Italic
            if (text[i] == '*' && (i == 0 || text[i - 1] != '*')) {
                if (tempItalic > 0) tempItalic-- else tempItalic++
                i++
                if (allClosed(tempCodeFence, tempInlineCode, tempBold, tempItalic, tempTableRow)) {
                    bestFlush = i
                }
                continue
            }
            if (text[i] == '_' && (i == 0 || text[i - 1] != '_')) {
                if (tempItalic > 0) tempItalic-- else tempItalic++
                i++
                if (allClosed(tempCodeFence, tempInlineCode, tempBold, tempItalic, tempTableRow)) {
                    bestFlush = i
                }
                continue
            }

            // Table
            if (text[i] == '|') {
                tempTableRow = true
                i++
                continue
            }

            // Newline
            if (text[i] == '\n') {
                tempTableRow = false
                if (tempItalic > 0) tempItalic = 0
                i++
                if (allClosed(tempCodeFence, tempInlineCode, tempBold, tempItalic, tempTableRow)) {
                    bestFlush = i
                }
                continue
            }

            i++
        }

        return bestFlush
    }

    private fun allClosed(
        codeFence: Boolean,
        inlineCode: Boolean,
        bold: Int,
        italic: Int,
        tableRow: Boolean,
    ): Boolean {
        return !codeFence && !inlineCode && bold == 0 && italic == 0 && !tableRow
    }
}
