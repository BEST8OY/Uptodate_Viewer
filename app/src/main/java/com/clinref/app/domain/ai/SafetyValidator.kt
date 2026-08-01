package com.clinref.app.domain.ai

class SafetyValidator {

    data class ValidationResult(
        val passed: Boolean,
        val warnings: List<String>,
        val topicRefs: List<TopicRef> = emptyList(),
        val graphicRefs: List<GraphicRef> = emptyList(),
        val blockedReason: String? = null
    )

    data class ToolCallRecord(
        val toolName: String,
        val arguments: Map<String, String>,
        val result: String,
        val success: Boolean
    )

    data class FetchedSection(
        val topicId: String,
        val topicTitle: String,
        val sectionId: String,
        val sectionTitle: String,
        val contentSnippet: String = ""
    )

    data class TopicRef(
        val topicId: String,
        val sectionId: String = "",
        val label: String,
        val topicTitle: String = ""
    )

    data class GraphicRef(
        val graphicId: String,
        val label: String
    )

    data class TurnContext(
        val toolCalls: List<ToolCallRecord>,
        val answer: String,
        val toolResults: List<String> = emptyList(),
        val fetchedSections: List<FetchedSection> = emptyList(),
        val graphicIds: Set<String> = emptySet(),
        val userQuestion: String = "",
        val topicRefs: List<TopicRef> = emptyList(),
        val graphicRefs: List<GraphicRef> = emptyList()
    )

    fun validate(context: TurnContext): ValidationResult {
        val warnings = mutableListOf<String>()

        // Intent-aware: allow conversational responses without tool calls
        if (context.toolCalls.isEmpty()) {
            if (isNonClinicalResponse(context)) {
                return ValidationResult(passed = true, warnings = emptyList())
            }
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "Clinical recommendations require database verification. No database tools were executed."
            )
        }

        val rule1 = validateSectionContentRequired(context)
        if (!rule1.passed) return rule1

        val rule2 = validateNoInventedNumbers(context)
        if (!rule2.passed) return rule2
        warnings.addAll(rule2.warnings)

        val rule3 = validateNoGraphicInterpretation(context)
        if (!rule3.passed) return rule3
        warnings.addAll(rule3.warnings)

        return ValidationResult(
            passed = true,
            warnings = warnings,
            topicRefs = context.topicRefs,
            graphicRefs = context.graphicRefs
        )
    }

    private fun validateSectionContentRequired(context: TurnContext): ValidationResult {
        val hasSectionFetch = context.toolCalls.any {
            (it.toolName == "getTopicSectionsText" || it.toolName == "getGraphicContent") && it.success
        }
        if (!hasSectionFetch) {
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "Answer requires section or table content retrieval. Only topic outlines or searches were performed."
            )
        }
        return ValidationResult(passed = true, warnings = emptyList())
    }

    private val clinicalQuantityRegex = Regex(
        """\b\d+(?:[\.,]\d+)?\s*(?:(?:mg|mcg|g|kg|mL|L|mmol|mEq|IU|U|units?|bpm|mmHg|cm|mm|m2)\b(?:/(?:kg|g|mg|mcg|mL|L|dL|m2|min|hr|hour|day|24h|[a-zA-Z0-9]+))*|[a-zA-Z]{1,6}/[a-zA-Z0-9]{1,10}(?:/[a-zA-Z0-9]{1,10})*|%)""",
        RegexOption.IGNORE_CASE
    )

    private val numericPartRegex = Regex("""^\d+[\.,]?\d*""")
    private val rangeDashRegex = Regex("""(\d+[\.,]?\d*)\s*[-–—]\s*(\d+[\.,]?\d*)""")

    private fun normalizeQuantity(metric: String): List<String> {
        val expanded = mutableListOf<String>()
        // Expand ranges: "5-10 mg" → ["5", "10"]
        val rangeMatch = rangeDashRegex.find(metric)
        if (rangeMatch != null) {
            expanded.add(rangeMatch.groupValues[1])
            expanded.add(rangeMatch.groupValues[2])
        }
        // Also extract the numeric part for direct match
        val numPart = numericPartRegex.find(metric)?.value
        if (numPart != null) {
            expanded.add(numPart)
            val uncomma = numPart.replace(",", "")
            if (uncomma != numPart) {
                expanded.add(uncomma)
            }
        }
        return expanded.ifEmpty { listOf(metric) }
    }

    private fun isQuantityInText(metric: String, text: String): Boolean {
        val variants = normalizeQuantity(metric)
        val textUncomma = text.replace(",", "")
        val textUnspace = text.replace(Regex("\\s+"), "")

        for (variant in variants) {
            val escaped = Regex.escape(variant)
            val boundaryRegex = Regex("""(?<!\d)$escaped(?!\d)""")
            if (boundaryRegex.containsMatchIn(text)) return true

            val uncomma = variant.replace(",", "")
            if (uncomma.isNotEmpty()) {
                val uncommaRegex = Regex("""(?<!\d)${Regex.escape(uncomma)}(?!\d)""")
                if (uncommaRegex.containsMatchIn(textUncomma)) return true
            }

            // Also try without space before unit (e.g., "10mg" in text when metric is "10 mg")
            val compact = metric.replace(Regex("\\s+"), "")
            if (compact != metric && boundaryRegex.containsMatchIn(textUnspace)) return true
        }
        return false
    }

    private fun validateNoInventedNumbers(context: TurnContext): ValidationResult {
        if (context.toolResults.isEmpty()) return ValidationResult(passed = true, warnings = emptyList())

        val allToolText = context.toolResults.joinToString(separator = " ")
        val userQuestionText = context.userQuestion

        val answerMetrics = clinicalQuantityRegex.findAll(context.answer)
            .map { it.value.trim() }
            .toSet()

        if (answerMetrics.isEmpty()) return ValidationResult(passed = true, warnings = emptyList())

        val unverifiedMetrics = mutableListOf<String>()

        for (metric in answerMetrics) {
            if (userQuestionText.contains(metric, ignoreCase = true)) continue
            if (isQuantityInText(metric, allToolText)) continue
            unverifiedMetrics.add(metric)
        }

        if (unverifiedMetrics.isNotEmpty()) {
            val unverifiedStr = unverifiedMetrics.take(5).joinToString(", ")
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "Unverified clinical quantities found in response: $unverifiedStr. Ensure every quantity or dosage matches the retrieved database section text exactly."
            )
        }

        return ValidationResult(passed = true, warnings = emptyList())
    }

    private val conversationalUserRegex = Regex(
        """(?i)^\s*(hi|hello|hey|greetings|who are you|thanks|thank you|help|what can you do)\b.*"""
    )

    private fun isNonClinicalResponse(context: TurnContext): Boolean {
        // If user question is conversational and response is short, bypass
        if (conversationalUserRegex.matches(context.userQuestion.trim()) && context.answer.length < 500) {
            return true
        }

        // Check if response contains actual dosing quantities (not just clinical words)
        return !clinicalQuantityRegex.containsMatchIn(context.answer)
    }

    private val explicitGraphicInterpretationPatterns = listOf(
        Regex("""(?i)the (?:image|photo|x-?ray|ct|mri|ecg|ekg|ultrasound|scan|film) (?:shows?|demonstrates?|reveals?|depicts?)"""),
        Regex("""(?i)(?:image|x-?ray|ct|mri|ecg|ekg|ultrasound|scan) (?:findings?|abnormalities?|features?)"""),
        Regex("""(?i)the (?:figure|diagram) (?:shows?|demonstrates?|reveals?|depicts?)""")
    )

    private fun validateNoGraphicInterpretation(context: TurnContext): ValidationResult {
        val answer = context.answer
        val hasExplicitVisualDesc = explicitGraphicInterpretationPatterns.any { it.containsMatchIn(answer) }
        
        if (!hasExplicitVisualDesc) return ValidationResult(passed = true, warnings = emptyList())

        val toolText = context.toolResults.joinToString(separator = " ")
        val visualInToolText = explicitGraphicInterpretationPatterns.any { it.containsMatchIn(toolText) }

        if (visualInToolText) return ValidationResult(passed = true, warnings = emptyList())

        return ValidationResult(
            passed = false,
            warnings = emptyList(),
            blockedReason = "Answer describes visual details of a graphic or scan that were not retrieved as text. " +
                "Direct users to view the image source directly."
        )
    }
}
