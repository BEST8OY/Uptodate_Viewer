package com.clinref.app.domain.ai

import kotlinx.serialization.Serializable

class SafetyValidator {

    @Serializable
    data class ValidationResult(
        val passed: Boolean,
        val warnings: List<String>,
        val topicRefs: List<TopicRef> = emptyList(),
        val graphicRefs: List<GraphicRef> = emptyList(),
        val blockedReason: String? = null
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
            (it.toolName == "getTopicSectionsText" || it.toolName == "get_topic_sections_text" ||
             it.toolName == "getGraphicContent" || it.toolName == "get_graphic_content") && it.success
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
        """\b\d+(?:[\.,]\d+)?(?:\s*(?:[-–—]|to)\s*\d+(?:[\.,]\d+)?)?\s*(?:(?:mg|mcg|[μµ]g|g|kg|mL|L|dL|mcL|uL|[μµ]L|mmol|[uμµ]mol|nmol|pmol|mEq|IU|U|units?|bpm|mmHg|cm|mm|m2|mOsm(?:ol)?|mU|[uμµ]U|[uμµ]IU)\b(?:/(?:kg|g|mg|mcg|[μµ]g|mL|L|dL|m2|min|hr|hour|day|24h|[a-zA-Z0-9]+))*|(?!(?:and/or|w/o|s/p|r/o|c/o)\b)[a-zA-Z]{1,6}/[a-zA-Z0-9]{1,10}(?:/[a-zA-Z0-9]{1,10})*|%|percent|percentage\b)""",
        RegexOption.IGNORE_CASE
    )

    private val rangeDashOrToRegex = Regex("""(\d+[\.,]?\d*)\s*(?:[-–—]|to)\s*(\d+[\.,]?\d*)""", RegexOption.IGNORE_CASE)
    private val numericPartRegex = Regex("""^\d+[\.,]?\d*""")

    private companion object {
        private val THOUSAND_COMMA_REGEX = Regex("""\d+,\d{3}(?:\b|\D)""")
        private val DECIMAL_COMMA_REGEX = Regex("""\d+,\d{1,2}(?:\b|\D)""")
        private val HAS_LETTER_REGEX = Regex("""[a-zA-Z]""")

        private val ONES_WORDS = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
        )
        private val TENS_WORDS = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
        )
    }

    private fun intToWords(n: Int): List<String> {
        if (n in 0..19) return listOf(ONES_WORDS[n])
        if (n in 20..99) {
            val ten = n / 10
            val rem = n % 10
            val tWord = TENS_WORDS[ten]
            return if (rem == 0) listOf(tWord) else listOf("$tWord-${ONES_WORDS[rem]}", "$tWord ${ONES_WORDS[rem]}")
        }
        if (n == 100) return listOf("one hundred", "hundred")
        return emptyList()
    }

    private fun getNumberVariants(numStr: String): List<String> {
        val variants = mutableListOf(numStr)
        val cleanCandidates = mutableListOf<String>()

        if (THOUSAND_COMMA_REGEX.containsMatchIn(numStr)) {
            val uncomma = numStr.replace(",", "")
            variants.add(uncomma)
            cleanCandidates.add(uncomma)
        } else if (DECIMAL_COMMA_REGEX.containsMatchIn(numStr)) {
            val dotDecimal = numStr.replace(",", ".")
            variants.add(dotDecimal)
            cleanCandidates.add(dotDecimal)
        } else {
            cleanCandidates.add(numStr)
        }

        for (cand in cleanCandidates) {
            try {
                val dVal = cand.toDouble()
                if (dVal % 1.0 == 0.0) {
                    val intVal = dVal.toInt()
                    val intStr = intVal.toString()
                    if (intStr !in variants) {
                        variants.add(intStr)
                    }
                    if (intVal in 0..100) {
                        for (w in intToWords(intVal)) {
                            if (w !in variants) variants.add(w)
                        }
                    }
                } else if (dVal == 0.5) {
                    for (f in listOf("half", "one-half", "one half")) {
                        if (f !in variants) variants.add(f)
                    }
                } else if (dVal == 0.25) {
                    for (f in listOf("quarter", "one-quarter", "one quarter")) {
                        if (f !in variants) variants.add(f)
                    }
                }
            } catch (_: NumberFormatException) {
            }
        }
        return variants
    }

    private fun isSingleNumberInText(numStr: String, text: String): Boolean {
        val variants = getNumberVariants(numStr)
        val textUncomma = text.replace(",", "")
        for (variant in variants) {
            if (HAS_LETTER_REGEX.containsMatchIn(variant)) {
                val wordRegex = Regex("""(?i)\b${Regex.escape(variant)}\b""")
                if (wordRegex.containsMatchIn(text)) return true
            } else {
                val escaped = Regex.escape(variant)
                val boundaryRegex = Regex("""(?i)(?<![\d.])$escaped(?!\.\d)(?!\d)""")
                if (boundaryRegex.containsMatchIn(text)) return true

                val uncomma = variant.replace(",", "")
                if (uncomma.isNotEmpty()) {
                    val uncommaRegex = Regex("""(?i)(?<![\d.])${Regex.escape(uncomma)}(?!\.\d)(?!\d)""")
                    if (uncommaRegex.containsMatchIn(textUncomma)) return true
                }
            }
        }
        return false
    }

    private fun normalizeQuantity(metric: String): List<String> {
        val expanded = mutableListOf<String>()
        val rangeMatch = rangeDashOrToRegex.find(metric)
        if (rangeMatch != null) {
            expanded.addAll(getNumberVariants(rangeMatch.groupValues[1]))
            expanded.addAll(getNumberVariants(rangeMatch.groupValues[2]))
            return expanded
        }
        val numMatch = numericPartRegex.find(metric)
        if (numMatch != null) {
            expanded.addAll(getNumberVariants(numMatch.value))
        }
        return expanded.ifEmpty { listOf(metric) }
    }

    private fun isQuantityInText(metric: String, text: String): Boolean {
        val rangeMatch = rangeDashOrToRegex.find(metric)
        if (rangeMatch != null) {
            val b1 = rangeMatch.groupValues[1]
            val b2 = rangeMatch.groupValues[2]
            return isSingleNumberInText(b1, text) && isSingleNumberInText(b2, text)
        }
        val numMatch = numericPartRegex.find(metric)
        if (numMatch != null) {
            return isSingleNumberInText(numMatch.value, text)
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
            if (userQuestionText.isNotBlank()) {
                if (userQuestionText.contains(metric, ignoreCase = true)) continue
                if (isQuantityInText(metric, userQuestionText)) continue
            }
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
