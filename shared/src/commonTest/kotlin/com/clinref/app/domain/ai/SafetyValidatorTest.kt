package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyValidatorTest {

    private val validator = SafetyValidator()

    // ── Helpers ──────────────────────────────────────────────────────

    private fun ctx(
        toolCalls: List<SafetyValidator.ToolCallRecord> = emptyList(),
        answer: String = "",
        toolResults: List<String> = emptyList(),
        fetchedSections: List<SafetyValidator.FetchedSection> = emptyList(),
        graphicIds: Set<String> = emptySet(),
        userQuestion: String = "",
        topicRefs: List<SafetyValidator.TopicRef> = emptyList(),
        graphicRefs: List<SafetyValidator.GraphicRef> = emptyList(),
        patientProfile: PatientProfile? = null
    ) = SafetyValidator.TurnContext(
        toolCalls = toolCalls,
        answer = answer,
        toolResults = toolResults,
        fetchedSections = fetchedSections,
        graphicIds = graphicIds,
        userQuestion = userQuestion,
        topicRefs = topicRefs,
        graphicRefs = graphicRefs,
        patientProfile = patientProfile
    )

    private fun toolCall(
        name: String = "getTopicSectionsText",
        args: Map<String, String> = mapOf("sectionId" to "H1"),
        result: String = "content",
        success: Boolean = true,
    ) = SafetyValidator.ToolCallRecord(name, args, result, success)

    private fun section(
        topicId: String = "1",
        sectionId: String = "H1",
        title: String = "Section 1",
    ) = SafetyValidator.FetchedSection(topicId, "Topic Title", sectionId, title)

    // ══════════════════════════════════════════════════════════════════
    // Rule 0: Intent-aware tool call check
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 0 blocks when no tool calls and clinical answer`() {
        val result = validator.validate(ctx(answer = "The recommended dose is 5 mg twice daily."))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("Clinical recommendations require database verification"))
    }

    @Test
    fun `rule 0 passes with tool calls`() {
        val result = validator.validate(ctx(toolCalls = listOf(toolCall())))
        // May fail on later rules, but not rule 0
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Clinical recommendations require database verification"))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 1: Section content required
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 1 blocks when only search was performed`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "searchTopics")),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("section or table content"))
    }

    @Test
    fun `rule 1 passes for getTopicSectionsText`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getTopicSectionsText")),
            answer = "test",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        assertTrue("Section text fetch via getTopicSectionsText satisfies rule 1, got: ${result.blockedReason}", result.passed)
    }

    @Test
    fun `rule 1 passes for getGraphicContent`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getGraphicContent")),
            answer = "test",
            toolResults = listOf("table content"),
            fetchedSections = listOf(section()),
        ))
        assertTrue("Graphic table fetch satisfies rule 1, got: ${result.blockedReason}", result.passed)
    }

    @Test
    fun `rule 1 passes for getTopicOverview`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getTopicOverview")),
            answer = "test",
            toolResults = listOf("overview content"),
            fetchedSections = listOf(section()),
        ))
        assertTrue("Compound overview fetch satisfies rule 1, got: ${result.blockedReason}", result.passed)
    }

    @Test
    fun `rule 1 blocks for failed section fetch`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getTopicSectionsText", success = false, result = "Section not found.")),
        ))
        assertFalse(result.passed)
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 2: No invented clinical quantities
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 2 passes when quantities match tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 5 mg.",
            toolResults = listOf("The dose is 5 mg."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("clinical quantities"))
        }
    }

    @Test
    fun `rule 2 blocks when quantity not in tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 750 mg.",
            toolResults = listOf("The dose is 500 mg."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("750 mg"))
    }

    @Test
    fun `rule 2 allows quantities from user question`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 250 mg.",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
            userQuestion = "Is 250 mg safe?",
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("250 mg"))
        }
    }

    @Test
    fun `rule 2 does not flag structural numbers`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "1. Introduction\n2. Methods\nSee section 3.1.\nYear 2024.",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("clinical quantities"))
        }
    }

    @Test
    fun `rule 2 boundary check prevents partial matches`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The value is 12 mg.",
            toolResults = listOf("Value: 123 mg."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
    }

    @Test
    fun `rule 2 handles compound slashed units`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Recommended rates are 5 u/x, 10 U/L, and 0.5 mcg/kg/min.",
            toolResults = listOf("Give 5 u/x IV, 10 U/L, and 0.5 mcg/kg/min."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    @Test
    fun `rule 2 normalizes thousand separator commas`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 1200 mg.",
            toolResults = listOf("The dose is 1,200 mg daily."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    @Test
    fun `rule 2 matches spelled out number words`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Approximately 70% of patients were normal.",
            toolResults = listOf("Seventy percent of the older group with a TSH greater than 4.5 mU/L were normal."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    @Test
    fun `rule 2 matches spelled out single digit words`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Start with 5 mg daily.",
            toolResults = listOf("Five mg daily is the recommended initial dose."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    @Test
    fun `rule 2 requires both bounds for ranges`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Give 5-10 mg daily.",
            toolResults = listOf("Dose range: 5 to 10 mg daily."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    @Test
    fun `rule 2 blocks range when lower bound is invented`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Give 500-10 mg daily.",
            toolResults = listOf("Dose range: 5 to 10 mg daily."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("500-10 mg"))
    }

    @Test
    fun `rule 2 decimal boundary prevents subdecimal matches`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 2 mg.",
            toolResults = listOf("The dose is 0.2 mg daily."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("2 mg"))
    }

    @Test
    fun `rule 2 normalizes trailing decimal zero to integer`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Target TSH is 2.0 mU/L.",
            toolResults = listOf("Target TSH is 2 mU/L in this group."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    @Test
    fun `rule 2 ignores english slashes like and or`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Follow step 1 and/or step 2.",
            toolResults = listOf("Guidelines outline specific clinical protocols."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    @Test
    fun `rule 2 supports greek microgram symbol`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Dose is 50 μg.",
            toolResults = listOf("Recommended dose is 50 μg daily."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 3: No graphic interpretation
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 3 passes when no visual language`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The recommended treatment is aspirin.",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("visual details"))
        }
    }

    @Test
    fun `rule 3 passes when visual language is in tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The x-ray shows opacity.",
            toolResults = listOf("The x-ray shows opacity in the lower lobe."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("visual details"))
        }
    }

    @Test
    fun `rule 3 blocks when visual language not in tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The image shows a mass.",
            toolResults = listOf("Normal lung fields."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("visual details"))
    }

    // ══════════════════════════════════════════════════════════════════
    // Full validation integration
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `full validation passes on realistic successful turn`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(args = mapOf("sectionId" to "H1"))),
            answer = "The dose is 5 mg.",
            toolResults = listOf("The dose is 5 mg."),
            fetchedSections = listOf(section(sectionId = "H1", title = "Dosing")),
            topicRefs = listOf(SafetyValidator.TopicRef(topicId = "1", sectionId = "H1", label = "Dosing", topicTitle = "Topic Title"))
        ))
        assertTrue(result.passed)
        assertEquals(1, result.topicRefs.size)
    }

    @Test
    fun `full validation short-circuits on first failure`() {
        // No tool calls → fails rule 0 immediately
        val result = validator.validate(ctx(answer = "The recommended dose is 5 mg twice daily."))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("Clinical recommendations require database verification"))
    }

    // ══════════════════════════════════════════════════════════════════
    // Metric Equivalence & Frequency Normalization
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 2 verifies metric prefix equivalence between grams and milligrams`() {
        // Answer uses 1 g, tool evidence contains 1,000 mg
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer ceftriaxone 1 g IV daily.",
            toolResults = listOf("Ceftriaxone dosage is 1,000 mg intravenously once daily."),
            fetchedSections = listOf(section())
        ))
        assertTrue(result.passed)
    }

    @Test
    fun `rule 2 verifies microgram metric equivalence with fractional milligrams`() {
        // Answer uses 500 mcg, tool evidence contains 0.5 mg
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Initial dose is 500 mcg daily.",
            toolResults = listOf("Recommended starting dose is 0.5 mg daily."),
            fetchedSections = listOf(section())
        ))
        assertTrue(result.passed)
    }

    @Test
    fun `rule 2 verifies volume metric equivalence between liters and milliliters`() {
        // Answer uses 1 L, tool evidence contains 1000 mL
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Infuse 1 L of normal saline over 2 hours.",
            toolResults = listOf("Normal saline 1000 mL bolus over 2 hours."),
            fetchedSections = listOf(section())
        ))
        assertTrue(result.passed)
    }

    @Test
    fun `rule 2 does not flag natural administration frequencies with times or tablets per day`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Take 1 tablet/day or 2 times/day with water.",
            toolResults = listOf("Take with water once or twice daily."),
            fetchedSections = listOf(section())
        ))
        assertTrue(result.passed)
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 4: Patient Profile Bounds (Renal & Allergy Cross-Referencing)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 4 blocks affirmative recommendation of allergenic drug (penicillin to amoxicillin)`() {
        val profile = PatientProfile(
            allergies = listOf("penicillin (anaphylaxis)")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen & Dosing: Amoxicillin 500 mg orally three times daily for 10 days.",
            toolResults = listOf("Amoxicillin 500 mg three times daily is recommended."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("Documented patient allergy contraindication"))
        assertTrue(result.blockedReason!!.contains("penicillin (anaphylaxis)"))
        assertTrue(result.blockedReason!!.contains("amoxicillin"))
    }

    @Test
    fun `rule 4 passes when non-cross-reactive alternative is recommended for penicillin allergy`() {
        val profile = PatientProfile(
            allergies = listOf("penicillin (anaphylaxis)")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen & Dosing: Azithromycin 500 mg on day 1, then 250 mg daily for 4 days.",
            toolResults = listOf("Azithromycin 500 mg on day 1, then 250 mg daily."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertTrue(result.passed)
    }

    @Test
    fun `rule 4 passes when allergenic drug is explicitly stated as avoided or contraindicated`() {
        val profile = PatientProfile(
            allergies = listOf("penicillin")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Avoid amoxicillin due to documented penicillin allergy. First-line therapy: Azithromycin 500 mg daily.",
            toolResults = listOf("Azithromycin 500 mg daily. Amoxicillin is commonly used."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertTrue(result.passed)
    }

    @Test
    fun `rule 4 blocks cross-reactive sulfa drug (sulfa allergy to Bactrim)`() {
        val profile = PatientProfile(
            allergies = listOf("sulfa drugs")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen: Prescribe Bactrim DS 1 tablet twice daily.",
            toolResults = listOf("Bactrim DS 1 tablet twice daily."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("allergy contraindication"))
        assertTrue(result.blockedReason!!.contains("bactrim"))
    }

    @Test
    fun `rule 4 blocks cross-reactive NSAID (aspirin allergy to ibuprofen)`() {
        val profile = PatientProfile(
            allergies = listOf("aspirin")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer ibuprofen 400 mg every 6 hours.",
            toolResults = listOf("Ibuprofen 400 mg every 6 hours."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("allergy contraindication"))
        assertTrue(result.blockedReason!!.contains("ibuprofen"))
    }

    @Test
    fun `rule 4 blocks NSAIDs in severe renal impairment (eGFR 22 mL per min)`() {
        val profile = PatientProfile(
            conditions = listOf("Severe CKD", "eGFR 22 mL/min")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen & Dosing: Indomethacin 50 mg three times daily.",
            toolResults = listOf("Indomethacin 50 mg three times daily is first-line for acute gout."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("Documented patient renal contraindication"))
        assertTrue(result.blockedReason!!.contains("indomethacin"))
    }

    @Test
    fun `rule 4 blocks metformin in severe renal impairment (eGFR under 30)`() {
        val profile = PatientProfile(
            notes = "Patient has chronic kidney disease with eGFR of 24 mL/min"
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen & Dosing: Metformin 500 mg twice daily with meals.",
            toolResults = listOf("Metformin 500 mg twice daily with meals."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("metformin"))
        assertTrue(result.blockedReason!!.contains("contraindicated"))
    }

    @Test
    fun `rule 4 blocks colchicine in severe renal impairment when no adjustment specified`() {
        val profile = PatientProfile(
            conditions = listOf("CKD stage 4 (eGFR 20)")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen & Dosing: Colchicine 1.2 mg followed by 0.6 mg one hour later.",
            toolResults = listOf("Colchicine 1.2 mg followed by 0.6 mg one hour later."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("colchicine requires renal dosage adjustment"))
    }

    @Test
    fun `rule 4 passes for colchicine in severe renal impairment when renal adjustment is specified`() {
        val profile = PatientProfile(
            conditions = listOf("CKD stage 4 (eGFR 20)")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen & Dosing: Colchicine 0.3 mg daily with renal dose adjustment for CKD.",
            toolResults = listOf("Colchicine 0.3 mg daily with renal dose adjustment for CKD."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertTrue(result.passed)
    }

    @Test
    fun `rule 4 passes for glucocorticoids when NSAID is explicitly noted as contraindicated in severe renal impairment`() {
        val profile = PatientProfile(
            conditions = listOf("Severe renal impairment (eGFR 18 mL/min)")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "NSAIDs such as indomethacin are contraindicated in severe renal impairment. First-Line Regimen & Dosing: Intra-articular triamcinolone 20 mg.",
            toolResults = listOf("Intra-articular triamcinolone 20 mg. NSAIDs are first line."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertTrue(result.passed)
    }

    @Test
    fun `rule 4 passes when patientProfile has no renal impairment and no allergies`() {
        val profile = PatientProfile(
            age = "45",
            sex = "male",
            conditions = listOf("Hypertension")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen & Dosing: Lisinopril 10 mg daily.",
            toolResults = listOf("Lisinopril 10 mg daily is recommended."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertTrue(result.passed)
    }

    @Test
    fun `rule 4 blocks affirmative recommendation of ibuprofen for compound Aspirin NSAIDs allergy`() {
        val profile = PatientProfile(
            allergies = listOf("Aspirin/NSAIDs")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Recommend ibuprofen 400 mg for acute pain.",
            toolResults = listOf("Ibuprofen 400 mg for acute pain."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason?.contains("allergy contraindication", ignoreCase = true) == true)
        assertTrue(result.blockedReason?.contains("ibuprofen", ignoreCase = true) == true)
    }

    @Test
    fun `rule 4 blocks affirmative recommendation of amoxicillin for trailing dot penicillin allergy`() {
        val profile = PatientProfile(
            allergies = listOf("penicillin.")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Prescribe amoxicillin 500 mg orally.",
            toolResults = listOf("Amoxicillin 500 mg orally."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason?.contains("allergy contraindication", ignoreCase = true) == true)
        assertTrue(result.blockedReason?.contains("amoxicillin", ignoreCase = true) == true)
    }

    @Test
    fun `rule 4 blocks affirmative recommendation of ibuprofen for plural NSAIDs allergy`() {
        val profile = PatientProfile(
            allergies = listOf("NSAIDs")
        )
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Recommend ibuprofen 400 mg.",
            toolResults = listOf("Ibuprofen 400 mg."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason?.contains("allergy contraindication", ignoreCase = true) == true)
        assertTrue(result.blockedReason?.contains("ibuprofen", ignoreCase = true) == true)
    }

    @Test
    fun `rule 4 blocks ibuprofen and metformin for Stage 4 CKD`() {
        val profile = PatientProfile(
            conditions = listOf("Stage 4 CKD")
        )
        val resultIbu = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen: Ibuprofen 600 mg TID.",
            toolResults = listOf("Ibuprofen 600 mg TID."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(resultIbu.passed)
        assertTrue(resultIbu.blockedReason?.contains("renal contraindication", ignoreCase = true) == true)
        assertTrue(resultIbu.blockedReason?.contains("ibuprofen", ignoreCase = true) == true)

        val resultMet = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen: Metformin 500 mg twice daily.",
            toolResults = listOf("Metformin 500 mg twice daily."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(resultMet.passed)
        assertTrue(resultMet.blockedReason?.contains("renal contraindication", ignoreCase = true) == true)
        assertTrue(resultMet.blockedReason?.contains("metformin", ignoreCase = true) == true)
    }

    @Test
    fun `rule 4 blocks ibuprofen and metformin for Stage 5 CKD`() {
        val profile = PatientProfile(
            conditions = listOf("Stage 5 CKD")
        )
        val resultIbu = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen: Ibuprofen 600 mg TID.",
            toolResults = listOf("Ibuprofen 600 mg TID."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(resultIbu.passed)
        assertTrue(resultIbu.blockedReason?.contains("renal contraindication", ignoreCase = true) == true)
        assertTrue(resultIbu.blockedReason?.contains("ibuprofen", ignoreCase = true) == true)

        val resultMet = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "First-Line Regimen: Metformin 500 mg twice daily.",
            toolResults = listOf("Metformin 500 mg twice daily."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertFalse(resultMet.passed)
        assertTrue(resultMet.blockedReason?.contains("renal contraindication", ignoreCase = true) == true)
        assertTrue(resultMet.blockedReason?.contains("metformin", ignoreCase = true) == true)
    }

    @Test
    fun `rule 4 passes for colchicine with reduce dose and reduced dose phrasings in severe renal impairment`() {
        val profile = PatientProfile(
            conditions = listOf("eGFR 22 mL/min")
        )
        val resultReduce = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Colchicine 0.3 mg daily, reduce dose due to low GFR.",
            toolResults = listOf("Colchicine 0.3 mg daily, reduce dose due to low GFR."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertTrue(resultReduce.passed)

        val resultReduced = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Colchicine 0.3 mg daily, reduced dose due to renal function.",
            toolResults = listOf("Colchicine 0.3 mg daily, reduced dose due to renal function."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertTrue(resultReduced.passed)
    }

    // ══════════════════════════════════════════════════════════════════
    // SystemPrompt Directives & Non-FTS Correction Verification
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `system prompt build includes PICO, SOAP, and point-of-care category output directives`() {
        val profile = PatientProfile(
            age = "65",
            sex = "female",
            conditions = listOf("Atrial Fibrillation"),
            medications = listOf("Metoprolol 25 mg"),
            allergies = listOf("Penicillin"),
            notes = "eGFR 45 mL/min"
        )
        val prompt = SystemPrompt.build(profile, AiProvider.GOOGLE)

        // PICO & SOAP architecture
        assertTrue(prompt.contains("PICO"))
        assertTrue(prompt.contains("SOAP"))
        assertTrue(prompt.contains("Patient/Problem"))
        assertTrue(prompt.contains("Intervention"))
        assertTrue(prompt.contains("Comparison"))
        assertTrue(prompt.contains("Outcome"))

        // Point-of-care category output structure
        assertTrue(prompt.contains("First-Line Regimen & Dosing"))
        assertTrue(prompt.contains("Organ Function & Adjustments"))
        assertTrue(prompt.contains("Contraindications & Critical Warnings"))

        // Patient profile fields
        assertTrue(prompt.contains("Age: 65"))
        assertTrue(prompt.contains("Sex: female"))
        assertTrue(prompt.contains("Atrial Fibrillation"))
        assertTrue(prompt.contains("Metoprolol 25 mg"))
        assertTrue(prompt.contains("Penicillin"))
        assertTrue(prompt.contains("eGFR 45 mL/min"))
    }

    @Test
    fun `system prompt buildCorrectionPrompt uses non-FTS tools and handles contraindication`() {
        // Missing sections branch
        val missingPrompt = SystemPrompt.buildCorrectionPrompt(
            blockedReason = "Answer requires section or table content retrieval.",
            evidenceSummary = "No section text was successfully fetched in the prior turn."
        )
        assertFalse(missingPrompt.contains("searchTopicSections"))
        assertTrue(missingPrompt.contains("getTopicOverview"))
        assertTrue(missingPrompt.contains("getTopicSectionsText"))

        // Contraindication branch
        val contraPrompt = SystemPrompt.buildCorrectionPrompt(
            blockedReason = "Documented patient allergy contraindication: amoxicillin recommended for penicillin allergy",
            evidenceSummary = "Section [H1]: Amoxicillin 500 mg TID."
        )
        assertFalse(contraPrompt.contains("searchTopicSections"))
        assertTrue(contraPrompt.contains("REMEDIAL INSTRUCTIONS"))
        assertTrue(contraPrompt.contains("violated a documented patient safety constraint"))
        assertTrue(contraPrompt.contains("getTopicOverview"))
        assertTrue(contraPrompt.contains("getTopicSectionsText"))

        // Unverified quantities branch
        val unverifiedPrompt = SystemPrompt.buildCorrectionPrompt(
            blockedReason = "Unverified clinical quantities found in response: 750 mg.",
            evidenceSummary = "Section [H1]: 500 mg."
        )
        assertFalse(unverifiedPrompt.contains("searchTopicSections"))
        assertTrue(unverifiedPrompt.contains("getTopicOverview"))
        assertTrue(unverifiedPrompt.contains("getTopicSectionsText"))
    }
}