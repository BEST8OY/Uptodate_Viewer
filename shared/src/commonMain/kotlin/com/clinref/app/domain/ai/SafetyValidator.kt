package com.clinref.app.domain.ai

import kotlinx.serialization.Serializable

/**
 * Deterministic evidence and answer verification.
 *
 * Two independent gates, both driven by the values a [TurnContext] accumulates:
 * - [validateEvidence]: the answer was produced from retrieved database content.
 * - [validateAnswer]: every clinical quantity in the answer exists in that content,
 *   and no visual/graphic findings were invented.
 *
 * [validate] is the composite used for the persisted/displayed verdict; the agent
 * graph calls the two halves separately so each phase remediates toward the right
 * correction (retrieval vs. rewriting).
 */
class SafetyValidator {

    @Serializable
    data class ValidationResult(
        val passed: Boolean,
        val warnings: List<String>,
        val topicRefs: List<TopicRef> = emptyList(),
        val graphicRefs: List<GraphicRef> = emptyList(),
        val blockedReason: String? = null,
        val violations: List<Violation> = emptyList()
    )

    @Serializable
    data class ToolCallRecord(
        val toolName: String,
        val arguments: Map<String, String>,
        val result: String,
        val success: Boolean
    )

    @Serializable
    data class FetchedSection(
        val topicId: String,
        val topicTitle: String,
        val sectionId: String,
        val sectionTitle: String,
        val contentSnippet: String = ""
    )

    @Serializable
    data class TopicRef(
        val topicId: String,
        val sectionId: String = "",
        val label: String,
        val topicTitle: String = ""
    )

    @Serializable
    data class GraphicRef(
        val graphicId: String,
        val label: String,
        val topicId: String? = null
    )

    data class TurnContext(
        val toolCalls: List<ToolCallRecord>,
        val answer: String,
        val toolResults: List<String> = emptyList(),
        val fetchedSections: List<FetchedSection> = emptyList(),
        val graphicIds: Set<String> = emptySet(),
        val userQuestion: String = "",
        val topicRefs: List<TopicRef> = emptyList(),
        val graphicRefs: List<GraphicRef> = emptyList(),
        val patientProfile: PatientProfile? = null
    )

    fun validate(context: TurnContext): ValidationResult {
        // Evidence failures own the verdict: remediation for them is the retrieval prompt,
        // which never renders answer-quality findings. Within a gate, all rules collect.
        val evidenceViolations = validateEvidence(context)
        val violations = if (evidenceViolations.isNotEmpty()) evidenceViolations else validateAnswer(context)
        return ValidationResult(
            passed = violations.isEmpty(),
            warnings = emptyList(),
            topicRefs = context.topicRefs,
            graphicRefs = context.graphicRefs,
            blockedReason = violations.joinToString(" | ") { it.detail }.ifEmpty { null },
            violations = violations
        )
    }

    /**
     * Gate 1 — the turn must have queried the database AND captured clinical text
     * (a section body or a table). A topic outline is not evidence: it lists section
     * titles, not clinical content.
     */
    fun validateEvidence(context: TurnContext): List<Violation> = when {
        context.toolCalls.isEmpty() -> listOf(
            Violation(ViolationCodes.NO_TOOLS, NO_TOOLS_REASON, ViolationTarget.PHASE_1)
        )

        !capturedContent(context) -> listOf(
            Violation(ViolationCodes.NO_SECTIONS, NO_SECTIONS_REASON, ViolationTarget.PHASE_1)
        )

        else -> emptyList()
    }

    /**
     * A search hit or a topic outline is not evidence. Only accumulated section text / table
     * content — or a successful call to a tool that returns exactly that — counts.
     */
    private fun capturedContent(context: TurnContext): Boolean {
        if (context.fetchedSections.isNotEmpty() || context.graphicIds.isNotEmpty()) return true
        return context.toolCalls.any { it.success && it.toolName in CONTENT_TOOL_NAMES }
    }

    /** Gate 2 — answer-content rules (verbatim quantities, no graphic interpretation). */
    fun validateAnswer(context: TurnContext): List<Violation> =
        unverifiedQuantityViolations(context) + graphicInterpretationViolations(context)

    companion object {
        const val NO_TOOLS_REASON = "Clinical recommendations require database verification. No database tools were executed."
        const val NO_SECTIONS_REASON = "Answer requires section or table content retrieval. Only topic outlines or searches were performed."
        const val BLANK_NO_EVIDENCE_REASON = "No clinical response was generated. Evidence retrieval is required."
        const val BLANK_WITH_EVIDENCE_REASON = "No final clinical answer was synthesized; render the gathered evidence"

        /**
         * A number, an optional range separator, and a measurement unit. Units are the
         * vocabulary that actually occurs in the UpToDate corpus (dose masses, concentrations,
         * electrolytes, pressures, activity units, percentages, and durations).
         */
        val clinicalQuantityRegex = Regex(
            """(?<![\w.])(\d+(?:[.,]\d+)?)(?:\s*(?:-|–|—|to)\s*(\d+(?:[.,]\d+)?))?\s*""" +
                """([μµ]g|mcg|mg|mEq|mmol|mOsm|mU|mL|m2|IU|ng|pg|kg|dL|mmHg|cmH2O|g|L|%|percent|units?|years?|months?|weeks?|days?|hours?|minutes?|q\d+h?)(?!\w)""",
            RegexOption.IGNORE_CASE
        )

        private val UNIT_ALIASES = mapOf(
            "percent" to "%",
            "unit" to "units",
            "day" to "days",
            "hour" to "hours",
            "week" to "weeks",
            "year" to "years",
            "month" to "months",
            "minute" to "minutes"
        )

        private val VARIANT_UNIT_ALIASES = mapOf(
            "µg" to "mcg",
            "μg" to "mcg",
            "ug" to "mcg",
            "m²" to "m2"
        )

        /** Tools whose contract is "return section/table text", in either naming convention. */
        private val CONTENT_TOOL_NAMES = setOf(
            "getTopicSectionsText", "get_topic_sections_text",
            "getGraphicContent", "get_graphic_content"
        )

        private val UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")

        /** "1,200" -> "1200", "2.0" -> "2", "10.50" -> "10.5". */
        internal fun canonicalNumber(raw: String): String {
            val noCommas = raw.replace(",", "")
            if (!noCommas.contains('.')) return noCommas
            return noCommas.trimEnd('0').trimEnd('.').ifEmpty { "0" }
        }

        internal fun canonicalUnit(raw: String): String {
            val lower = raw.trim().lowercase()
            VARIANT_UNIT_ALIASES[lower]?.let { return it }
            return UNIT_ALIASES[lower] ?: lower
        }

        /**
         * Canonical `"<number> <unit>"` value -> the form first encountered in the text.
         * A range yields both bounds, so "5-10 mg" and "5 to 10 mg" verify identically.
         */
        internal fun extractQuantities(text: String): Map<String, String> {
            if (text.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (match in clinicalQuantityRegex.findAll(text)) {
                val unit = canonicalUnit(match.groupValues[3])
                val display = match.value.trim()
                out.getOrPut("${canonicalNumber(match.groupValues[1])} $unit") { display }
                val upper = match.groupValues[2]
                if (upper.isNotEmpty()) out.getOrPut("${canonicalNumber(upper)} $unit") { display }
            }
            return out
        }
    }

    /**
     * Everything the answer is allowed to cite: retrieved tool output, section text and
     * titles, plus facts the clinician already supplied (their question and the active
     * patient profile). Restating a patient-stated dose is not invention.
     */
    private fun evidenceCorpus(context: TurnContext): String {
        val parts = mutableListOf<String>()
        parts += context.toolResults
        parts += context.fetchedSections.map { it.contentSnippet }.filter { it.isNotBlank() }
        parts += context.fetchedSections.map { it.topicTitle }.filter { it.isNotBlank() }
        parts += context.fetchedSections.map { it.sectionTitle }.filter { it.isNotBlank() }
        parts += context.graphicRefs.map { it.label }.filter { it.isNotBlank() }
        parts += context.topicRefs.map { it.topicTitle }.filter { it.isNotBlank() }
        if (context.userQuestion.isNotBlank()) parts += context.userQuestion
        context.patientProfile?.toSystemBlock()?.takeIf { it.isNotBlank() }?.let { parts += it }

        val raw = parts.joinToString(" ")
        return UNICODE_ESCAPE_REGEX.replace(raw) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
    }

    private fun unverifiedQuantityViolations(context: TurnContext): List<Violation> {
        val answerQuantities = extractQuantities(context.answer)
        if (answerQuantities.isEmpty()) return emptyList()

        val verified = extractQuantities(evidenceCorpus(context))
        val unverified = answerQuantities.filterKeys { it !in verified }.values.distinct()
        if (unverified.isEmpty()) return emptyList()

        return listOf(
            Violation(
                code = ViolationCodes.UNVERIFIED_QTY,
                detail = "Unverified clinical quantities found in response: ${unverified.take(10).joinToString(", ")}. " +
                    "Ensure every quantity or dosage matches the retrieved database section text exactly.",
                target = ViolationTarget.PHASE_2
            )
        )
    }

    private fun graphicInterpretationViolations(context: TurnContext): List<Violation> {
        if (!VISUAL_PATTERNS.any { it.containsMatchIn(context.answer) }) return emptyList()

        // Quoting the retrieved text is not interpretation.
        val toolText = context.toolResults.joinToString(separator = " ")
        if (VISUAL_PATTERNS.any { it.containsMatchIn(toolText) }) return emptyList()

        return listOf(
            Violation(
                code = ViolationCodes.VISUAL,
                detail = "Answer describes visual details of a graphic or scan that were not retrieved as text. " +
                    "Direct users to view the image source directly.",
                target = ViolationTarget.PHASE_2
            )
        )
    }

    private val VISUAL_PATTERNS = listOf(
        Regex("""(?i)the\s+(?:image|photo|x-?ray|ct|mri|ecg|ekg|ultrasound|scan|film)\s+(?:shows?|demonstrates?|reveals?|depicts?)"""),
        Regex("""(?i)(?:image|x-?ray|ct|mri|ecg|ekg|ultrasound|scan)\s+(?:findings?|abnormalities?|features?)"""),
        Regex("""(?i)the\s+(?:figure|diagram)\s+(?:shows?|demonstrates?|reveals?|depicts?)""")
    )
}
