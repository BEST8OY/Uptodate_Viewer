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

    private fun stripMarkdownFormatting(text: String): String {
        var result = text
        // Strip citation lines
        result = result.replace(Regex("^\\s*Topic:.*$", RegexOption.MULTILINE), "")
        // Strip markdown list markers: "- ", "* ", "  - "
        result = result.replace(Regex("^\\s*[-*]\\s+", RegexOption.MULTILINE), "")
        // Strip numbered list markers: "1. ", "2. "
        result = result.replace(Regex("^\\s*\\d+\\.\\s+", RegexOption.MULTILINE), "")
        // Strip markdown headers: "### Title"
        result = result.replace(Regex("^#{1,6}\\s+.*$", RegexOption.MULTILINE), "")
        // Strip section dividers: "---"
        result = result.replace(Regex("^---\\s*$", RegexOption.MULTILINE), "")
        // Strip bold markers around numbers: **500mg** -> 500mg
        result = result.replace(Regex("\\*\\*(\\d)"), "$1")
        result = result.replace(Regex("(\\d)\\*\\*"), "$1")
        // Strip italic markers around numbers
        result = result.replace(Regex("\\*(\\d)"), "$1")
        result = result.replace(Regex("(\\d)\\*"), "$1")
        return result
    }

    private fun validateNoInventedNumbers(context: TurnContext): ValidationResult {
        if (context.toolResults.isEmpty()) return ValidationResult(passed = true, warnings = emptyList())

        val strippedAnswer = stripMarkdownFormatting(context.answer)
        val normalizedAnswer = normalizeNumericSpaces(strippedAnswer)
        val allToolText = context.toolResults.joinToString(separator = " ")
        val normalizedToolText = normalizeNumericSpaces(allToolText)

        // Extract numbers from user's question — these are patient-specific values, not invented
        val questionNumerics = if (context.userQuestion.isNotEmpty()) {
            numericTokenRegex.findAll(normalizeNumericSpaces(context.userQuestion))
                .map { it.value.trim() }
                .filter { it.isNotBlank() }
                .toSet()
        } else emptySet()

        val answerNumerics = numericTokenRegex.findAll(normalizedAnswer)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .filter { !Regex("^\\d+[,.]$").matches(it) }  // Filter list markers like "1,", "2,"
            .toList()

        if (answerNumerics.isEmpty()) return ValidationResult(passed = true, warnings = emptyList())

        val invented = answerNumerics.filter { numeric ->
            // Skip numbers that appear in the user's question (patient-specific values)
            if (numeric in questionNumerics) return@filter false

            val escaped = Regex.escape(numeric)
            // Lookbehind rejects preceding digits; lookahead rejects trailing digits only
            // (trailing periods are sentence punctuation, not part of a number)
            val boundaryPattern = Regex("(?<!\\d)$escaped(?!\\d)", RegexOption.IGNORE_CASE)
            val fullMatch = boundaryPattern.containsMatchIn(normalizedToolText)

            // Also try matching just the numeric part (strip units)
            // e.g., "60 mL" -> check if "60" appears in tool text
            val numOnly = Regex("^(\\d[\\d.,]*)").find(numeric)
            val numOnlyMatch = if (numOnly != null) {
                val numPart = Regex.escape(numOnly.groupValues[1])
                val numOnlyPattern = Regex("(?<!\\d)$numPart(?!\\d)", RegexOption.IGNORE_CASE)
                numOnlyPattern.containsMatchIn(normalizedToolText)
            } else false

            // Also check if numeric part matches any question number
            val inQuestion = if (numOnly != null) {
                val numPart = numOnly.groupValues[1]
                questionNumerics.any { qNum ->
                    val qPart = Regex("^(\\d[\\d.,]*)").find(qNum)
                    qPart != null && qPart.groupValues[1] == numPart
                }
            } else false

            !fullMatch && !numOnlyMatch && !inQuestion
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

        // Citation density: require that most fetched sections are cited
        if (context.fetchedSections.isNotEmpty()) {
            val citedSectionIds = context.citations.map { it.sectionId }.toSet()
            val fetchedSectionIds = context.fetchedSections.map { it.sectionId }.toSet()
            val uncited = fetchedSectionIds - citedSectionIds

            // Allow up to 1 uncited section (supporting context)
            if (uncited.size > 1) {
                val uncitedTitles = context.fetchedSections
                    .filter { it.sectionId in uncited }
                    .map { it.sectionTitle }
                    .take(3)
                return ValidationResult(
                    passed = false,
                    warnings = emptyList(),
                    blockedReason = "Fetched sections not cited in answer: ${uncitedTitles.joinToString(", ")}. " +
                        "Every section used to compose the answer must be cited."
                )
            }
        }
        return ValidationResult(passed = true, warnings = emptyList())
    }

    // NOTE: "table" intentionally excluded — system prompt rule 11 permits interpreting
    // table data, and graphicIds is only populated by getGraphicInfo (non-table metadata),
    // never by getGraphicContent (table content).
    private val visualInterpretationPatterns = listOf(
        Regex("""(?i)the (?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|pathology|slide|specimen|scan|film|rogram) (?:shows?|demonstrates?|reveals?|suggests?|indicates?|displays?|depicts?|illustrates?)"""),
        Regex("""(?i)(?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|pathology|slide|specimen|scan|film) (?:findings?|abnormalities?|results?|features?|characteristics?)"""),
        Regex("""(?i)(?:visual|visualized?|visible|appears? to show|can be seen)"""),
        Regex("""(?i)(?:the (?:figure|algorithm|diagram|picture) (?:shows?|demonstrates?|reveals?|depicts?))""")
    )

    private val GRAPHIC_REF_REGEX = Regex("""Graphic-[a-zA-Z0-9_-]+""", RegexOption.IGNORE_CASE)

    private fun validateNoGraphicInterpretation(context: TurnContext): ValidationResult {
        val answer = context.answer
        val hasVisualLanguage = visualInterpretationPatterns.any { it.containsMatchIn(answer) }
        if (!hasVisualLanguage) return ValidationResult(passed = true, warnings = emptyList())

        val hasGraphicToolCalls = context.graphicIds.isNotEmpty()
        val hasGraphicRefsInAnswer = GRAPHIC_REF_REGEX.containsMatchIn(answer)

        // Hard block when there's concrete evidence the model touched a non-table graphic
        if (hasGraphicToolCalls || hasGraphicRefsInAnswer) {
            return ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = "Answer contains language suggesting visual interpretation of a graphic. " +
                    "You may reference the graphic title and type, but you may not describe visual details that were not retrieved as text. " +
                    "Direct users to view the source directly."
            )
        }

        // Advisory: visual language with NO graphic-tool evidence is the more dangerous
        // case (fully ungrounded claim), but also the one most likely to false-positive
        // on ordinary prose ("the data appears to show a trend") — surface as warning.
        val touchedAnyGraphicTool = context.toolCalls.any {
            it.toolName == "getGraphicInfo" || it.toolName == "getGraphicContent"
        }
        val warnings = if (!touchedAnyGraphicTool) {
            listOf(
                "Answer uses visual-interpretation language but no graphic tool was called " +
                    "this turn — verify this isn't a fabricated visual finding."
            )
        } else emptyList()

        return ValidationResult(passed = true, warnings = warnings)
    }
}
