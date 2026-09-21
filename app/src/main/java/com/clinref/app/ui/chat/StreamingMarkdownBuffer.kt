package com.clinref.app.ui.chat

/**
 * Gemini-style streaming markdown buffer.
 *
 * Tracks open syntax states (code fences, inline code, bold, italic, tables)
 * and only flushes completed blocks to the renderer. Incomplete blocks are
 * held in a pending buffer and shown as plain text until the closing tag arrives.
 *
 * Uses incremental scanning: only re-scans new content appended since the
 * last flush point, avoiding O(N²) re-scans of the full accumulated text.
 */
class StreamingMarkdownBuffer {

    // Incremental scan state at the last flush point
    private var lastFlushIndex = 0
    private var openCodeFence = false
    private var openTableRow = false
    private var openInlineCode = false
    private var openBold = 0
    private var openItalic = 0

    // Cached flush point and rendered length from previous call
    private var cachedFlushPoint = 0
    private var cachedTextLength = 0

    /**
     * Process accumulated text and return safe-to-render markdown.
     *
     * @param accumulatedText The full raw text received so far from the LLM.
     * @return A [RenderState] with completed blocks and pending plain text.
     */
    fun process(accumulatedText: String): RenderState {
        if (accumulatedText.isEmpty()) return RenderState("", "")

        // If text hasn't changed, return cached result
        if (accumulatedText.length == cachedTextLength && cachedFlushPoint > 0) {
            return RenderState(
                accumulatedText.substring(0, cachedFlushPoint),
                accumulatedText.substring(cachedFlushPoint)
            )
        }

        // If text only grew (normal streaming), scan incrementally from last flush point
        if (accumulatedText.length > cachedTextLength && cachedFlushPoint > 0) {
            // State at lastFlushIndex is already correct from previous scan
            scanIncremental(accumulatedText, lastFlushIndex)
        } else {
            // Text was replaced or shortened — full rescan
            reset()
            scanAll(accumulatedText)
        }

        val flushPoint = findFlushPoint(accumulatedText)

        cachedFlushPoint = flushPoint
        cachedTextLength = accumulatedText.length
        lastFlushIndex = flushPoint

        return RenderState(
            accumulatedText.substring(0, flushPoint),
            accumulatedText.substring(flushPoint)
        )
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
        lastFlushIndex = 0
        cachedFlushPoint = 0
        cachedTextLength = 0
    }

    /**
     * Scan the entire text from index 0 to determine the final syntax state.
     * Used only when text is replaced or shortened.
     */
    private fun scanAll(text: String) {
        var i = 0
        while (i < text.length) {
            if (!openInlineCode && i + 2 < text.length &&
                text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`'
            ) {
                val lineStart = text.lastIndexOf('\n', i - 1).let { if (it < 0) 0 else it + 1 }
                val prefix = text.substring(lineStart, i)
                if (prefix.isBlank()) {
                    openCodeFence = !openCodeFence
                    i += 3
                    val nl = text.indexOf('\n', i)
                    i = if (nl >= 0) nl + 1 else text.length
                    continue
                }
            }

            if (!openCodeFence && text[i] == '`') {
                openInlineCode = !openInlineCode
                i++
                continue
            }

            if (openCodeFence || openInlineCode) {
                i++
                continue
            }

            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' &&
                (i == 0 || text[i - 1] != '*') &&
                (i + 2 >= text.length || text[i + 2] != '*')
            ) {
                if (openBold > 0) openBold-- else openBold++
                i += 2
                continue
            }
            if (i + 1 < text.length && text[i] == '_' && text[i + 1] == '_' &&
                (i == 0 || text[i - 1] != '_') &&
                (i + 2 >= text.length || text[i + 2] != '_')
            ) {
                if (openBold > 0) openBold-- else openBold++
                i += 2
                continue
            }

            if (text[i] == '*' && (i == 0 || text[i - 1] != '*')) {
                if (openItalic > 0) openItalic-- else openItalic++
                i++
                continue
            }
            if (text[i] == '_' && (i == 0 || text[i - 1] != '_')) {
                if (openItalic > 0) openItalic-- else openItalic++
                i++
                continue
            }

            if (text[i] == '|') {
                openTableRow = true
                i++
                continue
            }

            if (text[i] == '\n') {
                openTableRow = false
                if (openItalic > 0) openItalic = 0
                i++
                continue
            }

            i++
        }
    }

    /**
     * Scan only from [startIndex] to end of text, updating syntax state.
     * The state at [startIndex] must already be correct from a previous scan.
     */
    private fun scanIncremental(text: String, startIndex: Int) {
        var i = startIndex
        while (i < text.length) {
            if (!openInlineCode && i + 2 < text.length &&
                text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`'
            ) {
                val lineStart = text.lastIndexOf('\n', i - 1).let { if (it < 0) 0 else it + 1 }
                val prefix = text.substring(lineStart, i)
                if (prefix.isBlank()) {
                    openCodeFence = !openCodeFence
                    i += 3
                    val nl = text.indexOf('\n', i)
                    i = if (nl >= 0) nl + 1 else text.length
                    continue
                }
            }

            if (!openCodeFence && text[i] == '`') {
                openInlineCode = !openInlineCode
                i++
                continue
            }

            if (openCodeFence || openInlineCode) {
                i++
                continue
            }

            if (i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' &&
                (i == 0 || text[i - 1] != '*') &&
                (i + 2 >= text.length || text[i + 2] != '*')
            ) {
                if (openBold > 0) openBold-- else openBold++
                i += 2
                continue
            }
            if (i + 1 < text.length && text[i] == '_' && text[i + 1] == '_' &&
                (i == 0 || text[i - 1] != '_') &&
                (i + 2 >= text.length || text[i + 2] != '_')
            ) {
                if (openBold > 0) openBold-- else openBold++
                i += 2
                continue
            }

            if (text[i] == '*' && (i == 0 || text[i - 1] != '*')) {
                if (openItalic > 0) openItalic-- else openItalic++
                i++
                continue
            }
            if (text[i] == '_' && (i == 0 || text[i - 1] != '_')) {
                if (openItalic > 0) openItalic-- else openItalic++
                i++
                continue
            }

            if (text[i] == '|') {
                openTableRow = true
                i++
                continue
            }

            if (text[i] == '\n') {
                openTableRow = false
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
        var tempBold = openBold
        var tempItalic = openItalic
        var tempCodeFence = openCodeFence
        var tempInlineCode = openInlineCode
        var tempTableRow = openTableRow

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
                    if (allClosed(tempCodeFence, tempInlineCode, tempBold, tempItalic, tempTableRow)) {
                        bestFlush = i
                    } else {
                        bestFlush = (text.lastIndexOf('\n', i - 1).let { if (it < 0) 0 else it + 1 }).coerceAtMost(i - 3)
                    }
                    continue
                }
            }

            // Inline code
            if (!tempCodeFence && text[i] == '`') {
                tempInlineCode = !tempInlineCode
                i++
                if (!allClosed(tempCodeFence, tempInlineCode, tempBold, tempItalic, tempTableRow)) {
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
