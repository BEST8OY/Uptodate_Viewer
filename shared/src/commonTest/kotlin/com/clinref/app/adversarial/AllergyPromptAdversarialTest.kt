package com.clinref.app.adversarial

import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.SystemPrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllergyPromptAdversarialTest {

    private val validator = SafetyValidator()

    private fun ctx(
        answer: String,
        profile: PatientProfile? = null,
        toolResultText: String = "acetaminophen 500 mg, azithromycin 250 mg, amoxicillin 500 mg, ibuprofen 400 mg, naproxen 500 mg, ketorolac 15 mg, Bactrim 800 mg, ciprofloxacin 500 mg."
    ): SafetyValidator.TurnContext {
        return SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = "getTopicSectionsText",
                    arguments = mapOf("topicId" to "1", "sectionIds" to "s1"),
                    result = toolResultText,
                    success = true
                )
            ),
            toolResults = listOf(toolResultText),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection(
                    topicId = "1",
                    topicTitle = "Test Topic",
                    sectionId = "s1",
                    sectionTitle = "Treatment",
                    contentSnippet = "Evidence"
                )
            ),
            answer = answer,
            userQuestion = "What should I prescribe?",
            patientProfile = profile
        )
    }

    @Test
    fun penicillin_allergy_blocks_all_cross_reactive_penicillins() {
        val penicillinDrugs = listOf(
            "penicillin", "amoxicillin", "ampicillin", "piperacillin",
            "ticarcillin", "oxacillin", "nafcillin", "dicloxacillin",
            "augmentin", "unasyn", "zosyn", "amoxil", "clavulanate"
        )
        val profile = PatientProfile(allergies = listOf("penicillin"))

        for (drug in penicillinDrugs) {
            val res = validator.validate(ctx(answer = "Prescribe $drug for this infection.", profile = profile))
            assertFalse("Expected $drug to be BLOCKED for penicillin allergy, but passed", res.passed)
            assertTrue("Expected allergy contraindication in blockedReason: ${res.blockedReason}", res.blockedReason!!.contains("allergy contraindication"))
        }
    }

    @Test
    fun penicillin_allergy_passes_when_drug_is_explicitly_avoided_or_contraindicated() {
        val profile = PatientProfile(allergies = listOf("penicillin"))
        val avoidancePhrases = listOf(
            "Avoid amoxicillin due to documented penicillin allergy.",
            "Amoxicillin is contraindicated in patients with penicillin allergy.",
            "Patient is allergic to penicillin; do not use ampicillin.",
            "Avoid augmentin in penicillin-allergic patients.",
            "Amoxicillin avoided; prescribe azithromycin 250 mg instead.",
            "Augmentin is not recommended due to allergy to penicillin.",
            "Hold amoxicillin and give alternative therapy.",
            "Discontinue amoxicillin immediately if started.",
            "Do not prescribe ampicillin due to penicillin allergy.",
            "Contraindicated: amoxicillin and augmentin."
        )
        for (phrase in avoidancePhrases) {
            val res = validator.validate(ctx(answer = phrase, profile = profile))
            assertTrue("Expected avoidance phrase '$phrase' to PASS, but got blocked: ${res.blockedReason}", res.passed)
        }
    }

    @Test
    fun sulfa_allergy_blocks_all_cross_reactive_sulfa_drugs() {
        val sulfaDrugs = listOf(
            "bactrim", "septra", "sulfamethoxazole", "sulfadiazine",
            "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole"
        )
        for (allergyName in listOf("sulfa", "sulfonamide", "Sulfa allergy")) {
            val profile = PatientProfile(allergies = listOf(allergyName))
            for (drug in sulfaDrugs) {
                val res = validator.validate(ctx(answer = "First-line treatment is $drug.", profile = profile))
                assertFalse("Expected $drug to be BLOCKED for $allergyName, but passed", res.passed)
                assertTrue(res.blockedReason!!.contains("allergy contraindication"))
            }
        }
    }

    @Test
    fun aspirin_and_nsaid_allergy_blocks_ibuprofen_naproxen_ketorolac() {
        val nsaidDrugs = listOf("ibuprofen", "naproxen", "ketorolac")
        for (allergyName in listOf("aspirin", "nsaid")) {
            val profile = PatientProfile(allergies = listOf(allergyName))
            for (drug in nsaidDrugs) {
                val res = validator.validate(ctx(answer = "Recommend $drug for pain control.", profile = profile))
                assertFalse("Expected $drug to be BLOCKED for $allergyName, but passed", res.passed)
                assertTrue(res.blockedReason!!.contains("allergy contraindication"))
            }
        }
    }

    @Test
    fun non_FTS_prompt_generation_never_contains_searchTopicSections() {
        val testCases = listOf(
            "Answer requires section or table content retrieval." to "No section text was successfully fetched.",
            "Clinical recommendations require database verification." to "Only outline fetched.",
            "Documented patient allergy contraindication: Patient has documented allergy to penicillin." to "Topic overview",
            "Documented patient renal contraindication: eGFR < 30." to "Topic overview",
            "Unverified clinical quantities found in response: 99.4%." to "Topic overview"
        )
        for ((reason, evidence) in testCases) {
            val prompt = SystemPrompt.buildCorrectionPrompt(reason, evidence)
            assertFalse("searchTopicSections found in prompt for reason: $reason", prompt.contains("searchTopicSections"))
            assertTrue("Missing getTopicOverview in prompt for reason: $reason", prompt.contains("getTopicOverview"))
            assertTrue("Missing getTopicSectionsText in prompt for reason: $reason", prompt.contains("getTopicSectionsText"))
        }
    }

    @Test
    fun adversarial_punctuation_and_slash_delimiters_empirical_challenge() {
        // Test slash in allergy string (e.g. Aspirin/NSAIDs)
        val profileSlash = PatientProfile(allergies = listOf("Aspirin/NSAIDs"))
        val resSlash = validator.validate(ctx(answer = "Recommend ibuprofen.", profile = profileSlash))
        assertFalse(
            "Slash-delimited 'Aspirin/NSAIDs' tokens must cross-reference ibuprofen",
            resSlash.passed
        )
        assertTrue(
            resSlash.blockedReason!!.contains("allergy contraindication", ignoreCase = true) &&
                resSlash.blockedReason!!.contains("ibuprofen", ignoreCase = true)
        )

        // Test trailing dot (e.g. penicillin.)
        val profileDot = PatientProfile(allergies = listOf("penicillin."))
        val resDot = validator.validate(ctx(answer = "Prescribe amoxicillin.", profile = profileDot))
        assertFalse(
            "Trailing-dot 'penicillin.' must still cross-reference amoxicillin",
            resDot.passed
        )
        assertTrue(
            resDot.blockedReason!!.contains("allergy contraindication", ignoreCase = true) &&
                resDot.blockedReason!!.contains("amoxicillin", ignoreCase = true)
        )

        // Test plural NSAIDs
        val profilePlural = PatientProfile(allergies = listOf("NSAIDs"))
        val resPlural = validator.validate(ctx(answer = "Recommend ibuprofen.", profile = profilePlural))
        assertFalse(
            "Plural 'NSAIDs' must cross-reference ibuprofen",
            resPlural.passed
        )
        assertTrue(
            resPlural.blockedReason!!.contains("allergy contraindication", ignoreCase = true) &&
                resPlural.blockedReason!!.contains("ibuprofen", ignoreCase = true)
        )
    }
}
