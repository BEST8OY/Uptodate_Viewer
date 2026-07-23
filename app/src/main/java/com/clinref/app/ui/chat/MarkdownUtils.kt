package com.clinref.app.ui.chat

/**
 * Auto-closes unclosed markdown syntax to prevent visual flickering during streaming.
 *
 * LLMs emit tokens incrementally, so mid-stream you may have:
 * - `**bold` (missing closing `**`)
 * - `` `code`` (missing closing `` ` ``)
 * - `` ```kotlin\ncode`` (missing closing `` ``` ``)
 * - `| col1 | col2` (incomplete table row)
 *
 * This function detects these cases and appends temporary closing markers
 * so the markdown renderer produces stable output on every token update.
 */
object MarkdownUtils {

    fun autoCloseMarkdown(text: String): String {
        if (text.isEmpty()) return text

        val sb = StringBuilder(text)

        // Close unclosed inline code (single backtick)
        // Count backticks not inside code blocks
        val singleBackticks = countOutsideCodeFences(text, '`')
        if (singleBackticks % 2 != 0) {
            sb.append('`')
        }

        // Close unclosed bold/italic (**, ***, __, ___)
        closeUnclosedPair(sb, text, "**", "***")
        closeUnclosedPair(sb, text, "__", "___")

        // Close unclosed code fence (```)
        val tripleBackticks = text.lines().count { it.trimStart().startsWith("```") }
        if (tripleBackticks % 2 != 0) {
            sb.append("\n```")
        }

        // Close unclosed blockquote
        val blockquoteLines = text.lines().count { it.trimStart().startsWith(">") }
        if (blockquoteLines > 0 && !text.trimEnd().endsWith("\n")) {
            sb.append("\n")
        }

        return sb.toString()
    }

    private fun countOutsideCodeFences(text: String, char: Char): Int {
        var count = 0
        var inCodeFence = false
        var inInlineCode = false
        var i = 0

        while (i < text.length) {
            // Check for code fence (```)
            if (i + 2 < text.length && text[i] == '`' && text[i + 1] == '`' && text[i + 2] == '`') {
                inCodeFence = !inCodeFence
                i += 3
                continue
            }

            // Check for inline code (`)
            if (text[i] == '`' && !inCodeFence) {
                inInlineCode = !inInlineCode
                i++
                continue
            }

            // Count the character only outside code
            if (text[i] == char && !inCodeFence && !inInlineCode) {
                count++
            }

            i++
        }

        return count
    }

    private fun closeUnclosedPair(sb: StringBuilder, text: String, short: String, long: String) {
        // Check if the text ends mid-syntax (e.g., "**bold" without closing "**")
        val trimmed = text.trimEnd()

        // Try long form first (*** or ___)
        if (trimmed.endsWith(long.last()) && !trimmed.endsWith(long)) {
            // Check if it's an unclosed long form
            val suffixCount = trimmed.takeLastWhile { it == long.last() }.length
            if (suffixCount == long.length - 1 || suffixCount == 1) {
                // It's partially typed — close it
                val needed = long.length - suffixCount
                for (j in 0 until needed) {
                    sb.append(long.last())
                }
                return
            }
        }

        // Check for unclosed short form (** or __)
        if (trimmed.endsWith(short.last())) {
            val suffixCount = trimmed.takeLastWhile { it == short.last() }.length
            if (suffixCount % 2 != 0) {
                // Odd number means unclosed — add one more
                sb.append(short.last())
            }
        }
    }
}
