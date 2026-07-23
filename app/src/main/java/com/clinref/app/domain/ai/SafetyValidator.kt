package com.clinref.app.domain.ai

class SafetyValidator {

    data class ValidationResult(
        val passed: Boolean,
        val warnings: List<String>,
        val citations: List<Citation> = emptyList(),
        val blockedReason: String? = null
    )

    data class ToolCallRecord(
        val toolName: String,
        val arguments: Map<String, String>,
        val result: String,
        val success: Boolean
    )

    data class Citation(
        val topicId: String,
        val topicTitle: String,
        val sectionId: String,
        val sectionTitle: String
    )

    data class FetchedSection(
        val topicId: String,
        val topicTitle: String,
        val sectionId: String,
        val sectionTitle: String
    )

    data class TurnContext(
        val toolCalls: List<ToolCallRecord>,
        val answer: String,
        val citations: List<Citation>,
        val toolResults: List<String> = emptyList(),
        val fetchedSections: List<FetchedSection> = emptyList(),
        val graphicIds: Set<String> = emptySet(),
        val userQuestion: String = ""
    )

    fun validate(context: TurnContext): ValidationResult {
        val warnings = mutableListOf<String>()

        val rule1 = validateToolCallRequired(context)
        if (!rule1.passed) return rule1

        val rule1b = validateSectionContentRequired(context)
        if (!rule1b.passed) return rule1b

        val rule2 = validateCitationConsistency(context)
        if (!rule2.passed) return rule2
        warnings.addAll(rule2.warnings)

        val rule3 = validateNoInventedNumbers(context)
        if (!rule3.passed) return rule3
        warnings.addAll(rule3.warnings)

        val rule4 = validateCitationRequired(context)
        if (!rule4.passed) return rule4

        val rule5 = validateNoGraphicInterpretation(context)
        if (!rule5.passed) return rule5
        warnings.addAll(rule5.warnings)

        return ValidationResult(passed = true, warnings = warnings, citations = context.citations)
    }

    private fun validateToolCallRequired(context: TurnContext): ValidationResult {
        if (context.toolCalls.isEmpty()) {
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "No tool calls were made. Clinical answers require database retrieval."
            )
        }
        return ValidationResult(passed = true, warnings = emptyList())
    }

    private fun validateSectionContentRequired(context: TurnContext): ValidationResult {
        val hasSectionFetch = context.toolCalls.any {
            (it.toolName == "getTopicSectionText" || it.toolName == "getGraphicContent") && it.success
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

    private fun validateCitationConsistency(context: TurnContext): ValidationResult {
        val warnings = mutableListOf<String>()
        val fetchedIds = context.fetchedSections.map { it.sectionId }.toSet()

        for (citation in context.citations) {
            val idMatch = citation.sectionId != "unknown" && citation.sectionId in fetchedIds
            val titleMatch = context.fetchedSections.any { fetched ->
                fetched.sectionTitle.contains(citation.sectionTitle, ignoreCase = true) ||
                    citation.sectionTitle.contains(fetched.sectionTitle, ignoreCase = true)
            }

            if (!idMatch && !titleMatch && context.fetchedSections.isNotEmpty()) {
                return ValidationResult(
                    passed = false,
                    warnings = emptyList(),
                    blockedReason = "Citation references section '${citation.sectionTitle}' (ID: ${citation.sectionId}) which was not retrieved in this turn."
                )
            }

            if (!idMatch && titleMatch) {
                warnings.add("Citation section ID '${citation.sectionId}' matched via title fallback.")
            }
        }
        return ValidationResult(passed = true, warnings = warnings)
    }

    private val clinicalQuantityRegex = Regex(
        """\b\d+[\.,]?\d*\s*(?:mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg|g|mg/dL|mmol/L|mEq/L|IU|bpm|mcg/kg|mg/kg)\b""",
        RegexOption.IGNORE_CASE
    )

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

            val numPart = Regex("""^\d+[\.,]?\d*""").find(metric)?.value ?: metric
            val isNumInToolText = Regex("""(?<!\d)${Regex.escape(numPart)}(?!\d)""").containsMatchIn(allToolText)

            if (!isNumInToolText) {
                unverifiedMetrics.add(metric)
            }
        }

        if (unverifiedMetrics.isNotEmpty()) {
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "Answer contains clinical quantities not traceable to retrieved source data: ${unverifiedMetrics.take(5).joinToString(", ")}"
            )
        }

        return ValidationResult(passed = true, warnings = emptyList())
    }

    private fun validateCitationRequired(context: TurnContext): ValidationResult {
        if (context.citations.isEmpty()) {
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "No citations provided. Every clinical answer must cite its source section."
            )
        }

        if (context.fetchedSections.isNotEmpty()) {
            val fetchedSectionIds = context.fetchedSections.map { it.sectionId }.toSet()
            
            val validCitationFound = context.citations.any { citation ->
                citation.sectionId in fetchedSectionIds || context.fetchedSections.any { fetched ->
                    fetched.sectionTitle.contains(citation.sectionTitle, ignoreCase = true)
                }
            }

            if (!validCitationFound) {
                return ValidationResult(
                    passed = false,
                    warnings = emptyList(),
                    blockedReason = "None of the citations match the sections fetched during database retrieval."
                )
            }
        }

        return ValidationResult(passed = true, warnings = emptyList())
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
