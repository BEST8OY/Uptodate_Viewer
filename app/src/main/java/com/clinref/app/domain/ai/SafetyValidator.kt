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
        val sectionWarnings: Map<String, Boolean> = emptyMap(),
        val toolResults: List<String> = emptyList(),
        val fetchedSections: List<FetchedSection> = emptyList(),
        val graphicIds: Set<String> = emptySet()
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

        val rule3 = validateComplexDataWarning(context)
        warnings.addAll(rule3.warnings)

        val rule4 = validateNoInventedNumbers(context)
        if (!rule4.passed) return rule4
        warnings.addAll(rule4.warnings)

        val rule5 = validateCitationRequired(context)
        if (!rule5.passed) return rule5

        val rule6 = validateNoGraphicInterpretation(context)
        if (!rule6.passed) return rule6
        warnings.addAll(rule6.warnings)

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
            it.toolName == "getTopicSectionText" && it.success
        }
        if (!hasSectionFetch) {
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "Answer requires actual section content. Only topic titles or outlines were retrieved."
            )
        }
        return ValidationResult(passed = true, warnings = emptyList())
    }

    private fun validateCitationConsistency(context: TurnContext): ValidationResult {
        val warnings = mutableListOf<String>()
        val fetchedIds = context.fetchedSections.map { it.sectionId }.toSet()

        for (citation in context.citations) {
            val idMatch = citation.sectionId in fetchedIds
            val titleMatch = context.fetchedSections.any { fetched ->
                fetched.sectionTitle.contains(citation.sectionTitle, ignoreCase = true) ||
                    citation.sectionTitle.contains(fetched.sectionTitle, ignoreCase = true)
            }

            if (!idMatch && !titleMatch) {
                return ValidationResult(
                    passed = false,
                    warnings = emptyList(),
                    blockedReason = "Citation references section '${citation.sectionTitle}' (ID: ${citation.sectionId}) which was not retrieved in this turn."
                )
            }

            if (!idMatch && titleMatch) {
                warnings.add("Citation section ID '${citation.sectionId}' not exactly matched; title match used as fallback.")
            }
        }
        return ValidationResult(passed = true, warnings = warnings)
    }

    private fun validateComplexDataWarning(context: TurnContext): ValidationResult {
        val warnings = mutableListOf<String>()
        for ((sectionId, hasComplex) in context.sectionWarnings) {
            if (hasComplex) {
                val answerMentionsWarning = context.answer.contains("[WARNING", ignoreCase = true) ||
                                           context.answer.contains("complex dosing", ignoreCase = true) ||
                                           context.answer.contains("verify the raw details", ignoreCase = true)
                if (!answerMentionsWarning) {
                    warnings.add(
                        "Section $sectionId contains complex clinical data. " +
                            "The answer should include a warning directing users to view the source directly."
                    )
                }
            }
        }
        return ValidationResult(passed = true, warnings = warnings)
    }

    private val numericTokenRegex = Regex(
        """\d+[\.,]?\d*\s*(?:mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg)?""",
        RegexOption.IGNORE_CASE
    )

    private fun normalizeNumericSpaces(text: String): String {
        val unitPattern = Regex("""(\d+[\.,]?\d*)\s*(mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg)""", RegexOption.IGNORE_CASE)
        return unitPattern.replace(text) { match ->
            match.groupValues[1] + match.groupValues[2].lowercase()
        }
    }

    private fun validateNoInventedNumbers(context: TurnContext): ValidationResult {
        if (context.toolResults.isEmpty()) return ValidationResult(passed = true, warnings = emptyList())

        val normalizedAnswer = normalizeNumericSpaces(context.answer)
        val allToolText = context.toolResults.joinToString(separator = " ")
        val normalizedToolText = normalizeNumericSpaces(allToolText)

        val answerNumerics = numericTokenRegex.findAll(normalizedAnswer)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()

        if (answerNumerics.isEmpty()) return ValidationResult(passed = true, warnings = emptyList())

        val invented = answerNumerics.filter { numeric ->
            val escaped = Regex.escape(numeric)
            val boundaryPattern = Regex("(?<![\\d.])$escaped(?![\\d.])", RegexOption.IGNORE_CASE)
            !boundaryPattern.containsMatchIn(normalizedToolText)
        }

        if (invented.isNotEmpty()) {
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "Answer contains numbers not traceable to retrieved source data: ${invented.take(5).joinToString(", ")}"
            )
        }
        return ValidationResult(passed = true, warnings = emptyList())
    }

    private fun validateCitationRequired(context: TurnContext): ValidationResult {
        if (context.citations.isEmpty()) {
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "No citations provided. Every clinical answer must cite its source."
            )
        }
        return ValidationResult(passed = true, warnings = emptyList())
    }

    private val visualInterpretationPatterns = listOf(
        Regex("""(?i)the (?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|pathology|slide|specimen|scan|film|rogram) (?:shows?|demonstrates?|reveals?|suggests?|indicates?|displays?|depicts?|illustrates?)"""),
        Regex("""(?i)(?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|pathology|slide|specimen|scan|film) (?:findings?|abnormalities?|results?|features?|characteristics?)"""),
        Regex("""(?i)(?:visual|visualized?|visible|appears? to show|can be seen)"""),
        Regex("""(?i)(?:the (?:figure|table|algorithm|diagram) (?:shows?|demonstrates?|reveals?|depicts?))""")
    )

    private val GRAPHIC_REF_REGEX = Regex("""Graphic-\d+""", RegexOption.IGNORE_CASE)

    private fun validateNoGraphicInterpretation(context: TurnContext): ValidationResult {
        val answer = context.answer
        val violatingPatterns = visualInterpretationPatterns.filter { it.containsMatchIn(answer) }

        val hasGraphicToolCalls = context.graphicIds.isNotEmpty()
        val hasGraphicRefsInAnswer = GRAPHIC_REF_REGEX.containsMatchIn(answer)
        val hasVisualLanguage = violatingPatterns.isNotEmpty()

        if (hasVisualLanguage && (hasGraphicToolCalls || hasGraphicRefsInAnswer)) {
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "Answer contains language suggesting visual interpretation of a graphic. " +
                    "You may reference the graphic title and type, but you may not describe visual details that were not retrieved as text. " +
                    "Direct users to view the source directly."
            )
        }
        return ValidationResult(passed = true, warnings = emptyList())
    }
}
