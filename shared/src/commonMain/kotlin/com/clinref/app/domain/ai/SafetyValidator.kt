package com.clinref.app.domain.ai

import kotlin.math.floor
import kotlin.math.pow
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
        val graphicRefs: List<GraphicRef> = emptyList(),
        val patientProfile: PatientProfile? = null
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

        val rule4 = validatePatientProfileBounds(context)
        if (!rule4.passed) return rule4
        warnings.addAll(rule4.warnings)

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
             it.toolName == "getTopicOverview" || it.toolName == "get_topic_overview" ||
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

    companion object {
        val conversationalUserRegex = Regex(
            """(?i)^\s*(hi|hello|hey|greetings|good\s+(?:morning|afternoon|evening)|who are you|thanks|thank you|help|what can you do|what are your capabilities)\b.*"""
        )

        val clinicalQuantityRegex = Regex(
            """\b\d+(?:[\.,]\d+)?(?:\s*(?:[-–—]|to)\s*\d+(?:[\.,]\d+)?)?\s*(?:(?:mg|mcg|[μµ]g|g|kg|mL|L|dL|mcL|uL|[μµ]L|mmol|[uμµ]mol|nmol|pmol|mEq|IU|U|units?|bpm|mmHg|cm|mm|m2|mOsm(?:ol)?|mU|[uμµ]U|[uμµ]IU)\b(?:/(?:kg|g|mg|mcg|[μµ]g|mL|L|dL|m2|min|hr|hour|day|24h|[a-zA-Z0-9]+))*|(?!(?:and/or|w/o|s/p|r/o|c/o|times?|doses?|tabs?|tablets?|caps?|capsules?|puffs?|drops?)\b)[a-zA-Z]{1,6}/[a-zA-Z0-9]{1,10}(?:/[a-zA-Z0-9]{1,10})*|%|percent|percentage\b)""",
            RegexOption.IGNORE_CASE
        )

        fun isNonClinicalResponse(context: TurnContext): Boolean {
            // If response contains actual clinical quantities or percentages, it cannot bypass as non-clinical
            if (clinicalQuantityRegex.containsMatchIn(context.answer)) {
                return false
            }

            // Conversational user questions bypass tool requirement for short answers without quantities
            if (conversationalUserRegex.matches(context.userQuestion.trim()) && context.answer.length < 500) {
                return true
            }

            // General non-dosing response bypass
            return !clinicalQuantityRegex.containsMatchIn(context.answer)
        }

        private val THOUSAND_COMMA_REGEX = Regex("""\d+,\d{3}(?:\b|\D)""")
        private val thousandSeparatorOnly = Regex("""\d{1,3}(,\d{3})+""")
        private val DECIMAL_COMMA_REGEX = Regex("""\d+,\d{1,2}(?:\b|\D)""")
        private val HAS_LETTER_REGEX = Regex("""[a-zA-Z]""")
        private val UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")

        private val ONES_WORDS = arrayOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
        )
        private val TENS_WORDS = arrayOf(
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
        )

        private val NOISE_ALLERGY_WORDS = setOf(
            "allergy", "allergies", "allergic", "reaction", "reactions", "anaphylaxis",
            "severe", "mild", "moderate", "hives", "rash", "swelling", "induced",
            "drugs", "drug", "history", "known", "of", "to", "with", "and", "or", "patient", "has"
        )

        val ALLERGY_CROSS_REACTIVITY: Map<String, List<String>> = mapOf(
            "penicillin" to listOf(
                "penicillin", "amoxicillin", "ampicillin", "piperacillin", "ticarcillin",
                "oxacillin", "nafcillin", "dicloxacillin", "augmentin", "unasyn", "zosyn",
                "amoxil", "clavulanate"
            ),
            "penicillins" to listOf(
                "penicillin", "amoxicillin", "ampicillin", "piperacillin", "ticarcillin",
                "oxacillin", "nafcillin", "dicloxacillin", "augmentin", "unasyn", "zosyn",
                "amoxil", "clavulanate"
            ),
            "amoxicillin" to listOf(
                "amoxicillin", "penicillin", "ampicillin", "augmentin"
            ),
            "ampicillin" to listOf(
                "ampicillin", "amoxicillin", "penicillin", "unasyn"
            ),
            "sulfa" to listOf(
                "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
                "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole"
            ),
            "sulfonamide" to listOf(
                "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
                "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole"
            ),
            "sulfonamides" to listOf(
                "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
                "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole"
            ),
            "bactrim" to listOf(
                "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
                "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole"
            ),
            "septra" to listOf(
                "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
                "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole"
            ),
            "sulfamethoxazole" to listOf(
                "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
                "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole"
            ),
            "cephalosporin" to listOf(
                "cephalosporin", "cephalexin", "cefazolin", "ceftriaxone", "cefuroxime",
                "cefpodoxime", "cefepime", "cefaclor", "cefdinir"
            ),
            "cephalosporins" to listOf(
                "cephalosporin", "cephalexin", "cefazolin", "ceftriaxone", "cefuroxime",
                "cefpodoxime", "cefepime", "cefaclor", "cefdinir"
            ),
            "aspirin" to listOf(
                "aspirin", "acetylsalicylic", "asa", "ibuprofen", "naproxen", "indomethacin",
                "ketorolac", "meloxicam", "celecoxib", "diclofenac", "nsaid", "nsaids"
            ),
            "asa" to listOf(
                "aspirin", "acetylsalicylic", "asa", "ibuprofen", "naproxen", "indomethacin",
                "ketorolac", "meloxicam", "celecoxib", "diclofenac", "nsaid", "nsaids"
            ),
            "acetylsalicylic" to listOf(
                "aspirin", "acetylsalicylic", "asa", "ibuprofen", "naproxen", "indomethacin",
                "ketorolac", "meloxicam", "celecoxib", "diclofenac", "nsaid", "nsaids"
            ),
            "nsaid" to listOf(
                "nsaid", "nsaids", "ibuprofen", "naproxen", "indomethacin", "ketorolac",
                "meloxicam", "celecoxib", "diclofenac", "aspirin"
            ),
            "nsaids" to listOf(
                "nsaid", "nsaids", "ibuprofen", "naproxen", "indomethacin", "ketorolac",
                "meloxicam", "celecoxib", "diclofenac", "aspirin"
            ),
            "fluoroquinolone" to listOf(
                "ciprofloxacin", "levofloxacin", "moxifloxacin", "ofloxacin"
            ),
            "fluoroquinolones" to listOf(
                "ciprofloxacin", "levofloxacin", "moxifloxacin", "ofloxacin"
            ),
            "ciprofloxacin" to listOf(
                "ciprofloxacin", "levofloxacin", "moxifloxacin"
            ),
            "macrolide" to listOf(
                "azithromycin", "clarithromycin", "erythromycin"
            ),
            "macrolides" to listOf(
                "azithromycin", "clarithromycin", "erythromycin"
            ),
            "azithromycin" to listOf(
                "azithromycin", "clarithromycin", "erythromycin"
            ),
            "tetracycline" to listOf(
                "doxycycline", "minocycline", "tetracycline"
            ),
            "tetracyclines" to listOf(
                "doxycycline", "minocycline", "tetracycline"
            ),
            "doxycycline" to listOf(
                "doxycycline", "minocycline", "tetracycline"
            )
        )

        val RENAL_CONTRAINDICATED_DRUGS = listOf(
            "ibuprofen", "indomethacin", "naproxen", "ketorolac", "meloxicam", "celecoxib",
            "diclofenac", "sulindac", "piroxicam", "etodolac", "nsaid", "nsaids",
            "metformin", "nitrofurantoin"
        )

        val RENAL_ADJUSTMENT_REQUIRED_DRUGS = listOf(
            "colchicine", "apixaban", "enoxaparin", "gabapentin", "pregabalin",
            "allopurinol", "ciprofloxacin", "levofloxacin", "vancomycin", "digoxin", "spironolactone"
        )

        val RENAL_ADJUSTMENT_KEYWORDS_REGEX = Regex(
            """(?i)\b(?:renal (?:dose|dosing|adjustment|adjustments|impairment|clearance)|dose (?:adjustment|adjustments|reduction|reduced)|reduced? dose|decreased dose|adjusted (?:dose|dosing|for)|eGFR|CrCl|creatinine clearance|kidney function|reduced frequency)\b"""
        )
    }

    private val rangeDashOrToRegex = Regex("""(\d+[\.,]?\d*)\s*(?:[-–—]|to)\s*(\d+[\.,]?\d*)""", RegexOption.IGNORE_CASE)
    private val numericPartRegex = Regex("""^\d+[\.,]?\d*""")
    private val metricWithUnitRegex = Regex("""^(\d+(?:[\.,]\d+)?)\s*(mg|mcg|[μµ]g|g|kg|mL|L)\b""", RegexOption.IGNORE_CASE)

    private fun getMetricEquivalents(metric: String): List<String> {
        val match = metricWithUnitRegex.find(metric.trim()) ?: return emptyList()
        val numStr = match.groupValues[1].replace(",", ".")
        val unit = match.groupValues[2].lowercase()
        val dVal = numStr.toDoubleOrNull() ?: return emptyList()

        val equivalents = mutableListOf<String>()

        fun formatDouble(d: Double): String {
            if (d % 1.0 == 0.0) return d.toLong().toString()
            val asString = d.toString()
            if (!asString.contains('E') && !asString.contains('e')) return asString
            // Avoid scientific notation (e.g. "5.0E-4") so generated equivalents remain plain decimal strings
            // and remain comparable against evidence text (Python str(float) never yields scientific notation).
            val abs = kotlin.math.abs(d)
            var scaled = abs
            var decimals = 0
            while (scaled % 1.0 != 0.0 && decimals < 15) {
                scaled *= 10.0
                decimals++
            }
            val scaledLong = kotlin.math.round(scaled).toLong()
            val divisor = 10.0.pow(decimals)
            val whole = floor(scaledLong / divisor).toLong()
            val fraction = (scaledLong - whole * divisor).toLong().toString().padStart(decimals, '0').trimEnd('0')
            val sign = if (d < 0) "-" else ""
            if (fraction.isEmpty()) return "$sign$whole"
            return "$sign$whole.$fraction"
        }

        when (unit) {
            "g" -> {
                equivalents.add(formatDouble(dVal * 1000.0))
            }
            "mg" -> {
                equivalents.add(formatDouble(dVal / 1000.0))
                equivalents.add(formatDouble(dVal * 1000.0))
            }
            "mcg", "μg", "µg" -> {
                equivalents.add(formatDouble(dVal / 1000.0))
            }
            "l" -> {
                equivalents.add(formatDouble(dVal * 1000.0))
            }
            "ml" -> {
                equivalents.add(formatDouble(dVal / 1000.0))
            }
        }
        return equivalents
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

                // Only uncomma when the variant is a strict thousand-separator form (e.g. 1,200);
                // decimal commas (e.g. 1,5) must NOT collapse to ("15") — that would false-verify a 10x dose.
                val uncomma = if (variant.indexOf(',') < 0 || thousandSeparatorOnly.matches(variant)) {
                    variant.replace(",", "")
                } else {
                    ""
                }
                if (uncomma.isNotEmpty()) {
                    val uncommaRegex = Regex("""(?i)(?<![\d.])${Regex.escape(uncomma)}(?!\.\d)(?!\d)""")
                    if (uncommaRegex.containsMatchIn(textUncomma)) return true
                }
            }
        }
        return false
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
            if (isSingleNumberInText(numMatch.value, text)) return true
            for (eqNum in getMetricEquivalents(metric)) {
                if (isSingleNumberInText(eqNum, text)) return true
            }
        }
        return false
    }

    private fun validateNoInventedNumbers(context: TurnContext): ValidationResult {
        if (context.toolResults.isEmpty()) return ValidationResult(passed = true, warnings = emptyList())

        val rawToolText = context.toolResults.joinToString(separator = " ")
        val allToolText = UNICODE_ESCAPE_REGEX.replace(rawToolText) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
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

    private fun validatePatientProfileBounds(context: TurnContext): ValidationResult {
        val profile = context.patientProfile ?: return ValidationResult(passed = true, warnings = emptyList())
        val answer = context.answer

        // 1. Programmatic Allergy Cross-Referencing
        val allergyResult = checkPatientAllergies(profile, answer)
        if (!allergyResult.passed) return allergyResult

        // 2. Programmatic Renal Impairment & Dose Adjustments
        val renalResult = checkPatientRenalBounds(profile, answer, context.userQuestion)
        if (!renalResult.passed) return renalResult

        return ValidationResult(passed = true, warnings = emptyList())
    }

    private fun checkPatientAllergies(profile: PatientProfile, answer: String): ValidationResult {
        if (profile.allergies.isEmpty()) return ValidationResult(passed = true, warnings = emptyList())

        for (rawAllergy in profile.allergies) {
            if (rawAllergy.isBlank()) continue
            val tokens = extractAllergenTokens(rawAllergy)
            for (token in tokens) {
                val candidateDrugs = ALLERGY_CROSS_REACTIVITY[token] ?: listOf(token)
                for (drug in candidateDrugs) {
                    val drugRegex = Regex("""(?i)\b${Regex.escape(drug)}\b""")
                    if (drugRegex.containsMatchIn(answer)) {
                        if (!isDrugExplicitlyAvoided(drug, answer)) {
                            return ValidationResult(
                                passed = false,
                                warnings = emptyList(),
                                blockedReason = "Documented patient allergy contraindication: Patient has documented allergy to '$rawAllergy', but the response recommends '$drug'. Prescribe an alternative non-cross-reactive medication."
                            )
                        }
                    }
                }
            }
        }
        return ValidationResult(passed = true, warnings = emptyList())
    }

    private fun checkPatientRenalBounds(profile: PatientProfile, answer: String, userQuestion: String): ValidationResult {
        val combinedText = buildString {
            append(profile.conditions.joinToString(" "))
            append(" ")
            append(profile.notes)
            if (userQuestion.isNotBlank()) {
                append(" ")
                append(userQuestion)
            }
        }

        val renalInfo = detectRenalImpairment(combinedText) ?: return ValidationResult(passed = true, warnings = emptyList())

        // 1. Strictly contraindicated medications
        for (drug in RENAL_CONTRAINDICATED_DRUGS) {
            val drugRegex = Regex("""(?i)\b${Regex.escape(drug)}\b""")
            if (drugRegex.containsMatchIn(answer)) {
                if (!isDrugExplicitlyAvoided(drug, answer)) {
                    return ValidationResult(
                        passed = false,
                        warnings = emptyList(),
                        blockedReason = "Documented patient renal contraindication: Patient has documented ${renalInfo.description}, but the response recommends $drug. $drug is contraindicated in severe renal impairment."
                    )
                }
            }
        }

        // 2. Medications requiring explicit renal dose adjustment
        val mentionsAdjustment = RENAL_ADJUSTMENT_KEYWORDS_REGEX.containsMatchIn(answer)
        for (drug in RENAL_ADJUSTMENT_REQUIRED_DRUGS) {
            val drugRegex = Regex("""(?i)\b${Regex.escape(drug)}\b""")
            if (drugRegex.containsMatchIn(answer)) {
                if (!isDrugExplicitlyAvoided(drug, answer) && !mentionsAdjustment) {
                    return ValidationResult(
                        passed = false,
                        warnings = emptyList(),
                        blockedReason = "Documented patient renal contraindication: Patient has documented ${renalInfo.description}. $drug requires renal dosage adjustment or caution, but no renal adjustment was specified."
                    )
                }
            }
        }

        return ValidationResult(passed = true, warnings = emptyList())
    }

    private data class RenalImpairmentInfo(val description: String, val value: Double? = null)

    private fun detectRenalImpairment(text: String): RenalImpairmentInfo? {
        if (text.isBlank()) return null

        if (Regex("""(?i)\b(?:eGFR|CrCl|GFR|creatinine clearance)\s*<\s*30\b""").containsMatchIn(text)) {
            return RenalImpairmentInfo("severe renal impairment (eGFR/CrCl < 30 mL/min)")
        }

        val numMatch = Regex("""(?i)\b(?:eGFR|CrCl|GFR|creatinine clearance)\s*(?:of|is|:|=|<=|<)?\s*(\d+(?:\.\d+)?)\s*(?:mL/min)?\b""").find(text)
        if (numMatch != null) {
            val dVal = numMatch.groupValues[1].toDoubleOrNull()
            if (dVal != null && dVal < 30.0) {
                return RenalImpairmentInfo("severe renal impairment (eGFR/CrCl < 30 mL/min: $dVal mL/min)", dVal)
            }
        }

        if (Regex("""(?i)\b(?:severe (?:renal impairment|ckd|chronic kidney disease|renal failure|kidney disease)|ckd stage [45]|ckd [45]|stage [45] (?:ckd|chronic kidney disease)|esrd|end-stage renal disease)\b""").containsMatchIn(text)) {
            return RenalImpairmentInfo("severe renal impairment (CKD Stage 4/5 or ESRD)")
        }

        return null
    }

    private fun isDrugExplicitlyAvoided(drug: String, text: String): Boolean {
        val escaped = Regex.escape(drug)
        val p1 = Regex("""(?i)\b(?:avoid|avoided|avoiding|do not (?:use|prescribe|administer|give)|should not (?:be used|be prescribed|be given)|not recommended|hold|withhold|discontinue)\s+(?:\w+\s+){0,3}$escaped\b""")
        val p2 = Regex("""(?i)\b$escaped\s+(?:\w+\s+){0,4}(?:is\s+)?(?:contraindicated|avoided|not recommended|inappropriate)\b""")
        val p3 = Regex("""(?i)\b(?:contraindicated|contraindications?)\b[^.!?\n]*?\b$escaped\b""")
        val p4 = Regex("""(?i)\b$escaped\s+allerg(?:y|ies|ic)\b""")
        val p5 = Regex("""(?i)\ballerg(?:y|ies|ic)\s+(?:to\s+)?$escaped\b""")
        return p1.containsMatchIn(text) || p2.containsMatchIn(text) || p3.containsMatchIn(text) || p4.containsMatchIn(text) || p5.containsMatchIn(text)
    }

    private fun extractAllergenTokens(rawAllergy: String): List<String> {
        val clean = rawAllergy.lowercase().replace(Regex("""[^a-z0-9]"""), " ")
        return clean.split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 3 && it !in NOISE_ALLERGY_WORDS }
    }
}
