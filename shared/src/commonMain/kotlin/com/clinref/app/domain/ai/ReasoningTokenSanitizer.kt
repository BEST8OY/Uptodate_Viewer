package com.clinref.app.domain.ai

/**
 * Pure deterministic sanitizer for chain-of-thought and reasoning tokens.
 *
 * Strips XML thought blocks (<thought>, <think>, <reasoning>) from model outputs,
 * preventing thought leakage into user-facing chat text, database persistence,
 * and safety validation (eliminating number validation false positives).
 */
object ReasoningTokenSanitizer {

    data class SanitizedOutput(
        val cleanText: String,
        val reasoningText: String
    )

    private val REASONING_TAG_REGEX = Regex(
        """(?is)<(thought|think|reasoning)(?:\s+[^>]*)?>([\s\S]*?)(?:</\1>|$)"""
    )

    /**
     * Extracts reasoning text and strips reasoning blocks from raw model output.
     *
     * @param raw The raw output from the LLM.
     * @return [SanitizedOutput] containing clean clinical text and isolated reasoning text.
     */
    fun sanitize(raw: String): SanitizedOutput {
        if (raw.isBlank()) return SanitizedOutput("", "")

        val extracted = mutableListOf<String>()
        val matches = REASONING_TAG_REGEX.findAll(raw)

        for (match in matches) {
            val content = match.groupValues[2].trim()
            if (content.isNotBlank()) {
                extracted.add(content)
            }
        }

        val clean = REASONING_TAG_REGEX.replace(raw, "").trim()
        val reasoning = extracted.joinToString("\n\n")

        return SanitizedOutput(cleanText = clean, reasoningText = reasoning)
    }

    /**
     * Strips all reasoning blocks from [raw], returning only clean clinical response text.
     */
    fun stripReasoning(raw: String): String {
        if (raw.isBlank()) return ""
        return REASONING_TAG_REGEX.replace(raw, "").trim()
    }

    /**
     * Extracts all reasoning blocks from [raw], joined by newlines.
     */
    fun extractReasoning(raw: String): String {
        return sanitize(raw).reasoningText
    }

    /**
     * Returns true if [raw] contains any reasoning tags.
     */
    fun containsReasoning(raw: String): Boolean {
        return REASONING_TAG_REGEX.containsMatchIn(raw)
    }
}
