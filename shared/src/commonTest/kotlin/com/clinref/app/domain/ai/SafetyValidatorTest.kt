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
    fun `rule 2 allows lab, vital and anthropometric quantities from user question`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Patient weighs 70 kg and eGFR is 25 ml/min.",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
            userQuestion = "Patient weighs 70 kg, eGFR is 25 ml/min.",
        ))
        assertTrue(result.passed)
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
    fun `rule 2 requires both bounds for ranges`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Give 5-10 mg daily.",
            toolResults = listOf("Dose range: 5 to 10 mg daily."),
            fetchedSections = listOf(section()),
        ))
        assertTrue(result.passed)
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

    @Test
    fun `rule 3 detects graphic interpretation across newline whitespace by Python parity`() {
        // Python (safety_validator.py) uses \s+ between subject and triggering verb; Kotlin
        // previously required a single space. Both directions must now detect the newline form:
        // (1) answer-side detection blocks, (2) evidence-side detection satisfies Rule 3.
        val blocked = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The CT\nshows a mass.",
            toolResults = listOf("Normal lung fields."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(blocked.passed)
        assertTrue(blocked.blockedReason!!.contains("visual details"))

        val satisfied = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The CT\nshows a mass.",
            toolResults = listOf("The CT\nshows a mass with irregular borders."),
            fetchedSections = listOf(section()),
        ))
        assertTrue(
            "Rule 3 must stay satisfied when the newline phrase also exists in tool text, got: ${satisfied.blockedReason}",
            satisfied.passed
        )
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
    fun `rule 2 blocks metric prefix mismatch between grams and milligrams without explicit evidence`() {
        // Change 6: unit-equivalent generation is deleted. Value and unit must match evidence text.
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer ceftriaxone 1 g IV daily.",
            toolResults = listOf("Ceftriaxone dosage is 1,000 mg intravenously once daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("1 g"))
    }

    @Test
    fun `rule 2 blocks microgram metric equivalence with fractional milligrams without explicit evidence`() {
        // Change 6: unit-equivalent generation is deleted.
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Initial dose is 500 mcg daily.",
            toolResults = listOf("Recommended starting dose is 0.5 mg daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("500 mcg"))
    }

    @Test
    fun `rule 2 does not flag natural administration frequencies with times or tablets per day`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Take 1 tablet/day or 2 times/day with water.",
            toolResults = listOf("Take 1 tablet/day or 2 times/day with water."),
            fetchedSections = listOf(section())
        ))
        assertTrue(result.passed)
    }





    // ══════════════════════════════════════════════════════════════════
    // SystemPrompt Directives & Non-FTS Correction Verification
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `system prompt pairs closed-world grounding with question-shaped answers`() {
        val profile = PatientProfile(
            age = "65",
            sex = "female",
            conditions = listOf("Atrial Fibrillation"),
            medications = listOf("Metoprolol 25 mg"),
            allergies = listOf("Penicillin"),
            notes = "eGFR 45 mL/min"
        )
        val prompt = SystemPrompt.STATIC_CLINICAL_GUIDANCE + "\n\n" + SystemPrompt.patientContextBlock(profile)

        // Retrieval protocol and closed-world grounding come before formatting
        assertTrue(prompt.contains("RETRIEVE FIRST"))
        assertTrue(prompt.contains("FIDELITY"))
        assertTrue(prompt.contains("BLUF:"))
        assertTrue(prompt.contains(SystemPrompt.CANONICAL_ABSTENTION))
        assertTrue(
            "Grounding rules must precede the format rules",
            prompt.indexOf("RETRIEVE FIRST") < prompt.indexOf("FIDELITY") &&
                prompt.indexOf("FIDELITY") < prompt.indexOf("ANSWER STRUCTURE") &&
                prompt.indexOf("ANSWER STRUCTURE") < prompt.indexOf("OUTPUT MECHANICS")
        )
        assertTrue("Whole-answer abstention must be stated", prompt.contains("If nothing relevant was retrieved"))

        // Structure follows the question; headings are named by the question or the source,
        // never by a closed list of shapes baked into the prompt.
        assertTrue(prompt.contains("ANSWER STRUCTURE"))
        assertTrue("Source headings are preferred", prompt.contains("retrieved section's own title"))
        assertTrue("No heading may be forced", prompt.contains("Never force a heading"))
        assertTrue("A header may never be padded with invention", prompt.contains("instead of filling it"))
        assertTrue("Preamble is banned", prompt.contains("No preamble"))
        assertFalse("No fixed shape taxonomy", prompt.contains("When to Escalate"))
        assertFalse("No fixed dosing template", prompt.contains("First-Line Regimen & Dosing"))

        // Patient profile fields
        assertTrue(prompt.contains("Age: 65"))
        assertTrue(prompt.contains("Sex: female"))
        assertTrue(prompt.contains("Atrial Fibrillation"))
        assertTrue(prompt.contains("Metoprolol 25 mg"))
        assertTrue(prompt.contains("Penicillin"))
        assertTrue(prompt.contains("eGFR 45 mL/min"))
    }

    @Test
    fun `synthesis directive defers format to the system prompt`() {
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = listOf(SafetyValidator.ToolCallRecord("getTopicSectionsText", mapOf("topicId" to "1"), "content", true)),
            answer = "",
            fetchedSections = listOf(SafetyValidator.FetchedSection("1", "Topic", "H1", "Dosing", "Give 5 mg."))
        )
        val prompt = ClinicalAgentStrategy.formatSynthesisPrompt("", "What is the dose?", turnContext)

        assertTrue("The directive restates the one-line contract", prompt.contains(SystemPrompt.SYNTHESIS_CONTRACT))
        assertTrue(prompt.contains("<retrieved_evidence>"))
        assertFalse(
            "Format rules must live in one place, not be duplicated per turn",
            prompt.contains("Organ Function & Adjustments")
        )
    }

    @Test
    fun `system prompt buildCorrectionPrompt uses non-FTS tools and handles contraindication`() {
        // Missing sections branch (Gate 1 — Phase 1 tools active)
        val missingPrompt = SystemPrompt.buildCorrectionPrompt(
            violations = listOf(Violation(ViolationCodes.NO_SECTIONS, "Answer requires section or table content retrieval.", ViolationTarget.PHASE_1)),
            evidenceSummary = "No section text was successfully fetched in the prior turn."
        )
        assertFalse(missingPrompt.contains("searchTopicSections"))
        assertTrue(missingPrompt.contains("EVIDENCE GATE PAUSE"))
        assertTrue(missingPrompt.contains("MANDATORY NEXT STEP"))
        assertTrue(missingPrompt.contains("getTopicSectionsText"))

        // Modality guard branch (Gate 2 — Phase 2 tool-free)
        val visualPrompt = SystemPrompt.buildCorrectionPrompt(
            violations = listOf(Violation(ViolationCodes.VISUAL, "Answer describes visual details of a graphic or scan that were not retrieved as text.", ViolationTarget.PHASE_2)),
            evidenceSummary = "Section [H1]: Amoxicillin 500 mg TID."
        )
        assertFalse(visualPrompt.contains("searchTopicSections"))
        assertTrue(visualPrompt.contains("SAFETY REVISION"))
        assertTrue(visualPrompt.contains("VISUAL"))
        assertTrue(visualPrompt.contains("REVISION RULES"))
        assertFalse("Tool-free Phase 2 prompt must not command getTopicOverview", visualPrompt.contains("getTopicOverview("))
        assertFalse("Tool-free Phase 2 prompt must not command getTopicSectionsText", visualPrompt.contains("getTopicSectionsText("))

        // Unverified quantities branch (Gate 2 — Phase 2 tool-free)
        val unverifiedPrompt = SystemPrompt.buildCorrectionPrompt(
            violations = listOf(Violation(ViolationCodes.UNVERIFIED_QTY, "Unverified clinical quantities found in response: 750 mg.", ViolationTarget.PHASE_2)),
            evidenceSummary = "Section [H1]: 500 mg."
        )
        assertFalse(unverifiedPrompt.contains("searchTopicSections"))
        assertTrue(unverifiedPrompt.contains("SAFETY REVISION"))
        assertTrue(unverifiedPrompt.contains("UNVERIFIED_QTY"))
        assertTrue(unverifiedPrompt.contains("750 mg"))
        assertFalse("Tool-free Phase 2 prompt must not command getTopicOverview", unverifiedPrompt.contains("getTopicOverview("))
        assertFalse("Tool-free Phase 2 prompt must not command getTopicSectionsText", unverifiedPrompt.contains("getTopicSectionsText("))
    }

    // ══════════════════════════════════════════════════════════════════
    // Change 6 decimal-comma & cross-unit magnitude probes (BEHAVIORS PINS)
    // These tests pin the CURRENT unit-equivalence + bare-number behavior
    // (g×1000, mg ÷1000 AND ×1000, mcg ÷1000, L ×1000, mL ÷1000 with
    // unit-less magnitude matching anywhere in the corpus). Lines marked
    // `// Change 6 target: expect blocked (value+unit matching)` document
    // actual safety holes that the Change 6 rewrite will deliberately flip.
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `probe a1 - comma-thousand mg answer does not verify from a 1-dot-5 percent corpus`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer 1,500 mg daily.",
            toolResults = listOf("The horse serum concentration is 1.5% before dilution."),
            fetchedSections = listOf(section())
        ))
        // Current behavior: blocked.
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("1,500 mg"))
    }

    @Test
    fun `probe a2 - comma-thousand mg answer does not verify from a 1-dot-5 mg corpus`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer 1,500 mg daily.",
            toolResults = listOf("The dose is 1.5 mg once daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("1,500 mg"))
    }

    @Test
    fun `probe b1 - mg answer is blocked from a bare mcg magnitude anywhere in the corpus`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer 0.002 mg daily.",
            toolResults = listOf("The dose is 2 mcg once daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("0.002 mg"))
    }

    @Test
    fun `probe b2 - mg answer is blocked from a mg corpus at 1000x magnitude`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer 0.002 mg daily.",
            toolResults = listOf("The dose is 2 mg once daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("0.002 mg"))
    }

    @Test
    fun `probe c1 - comma-thousand mcg answer does not verify from a bare 1-dot-5 candidate`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer 1,500 mcg daily.",
            toolResults = listOf("The dose is 1.5 mg once daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("1,500 mcg"))
    }

    @Test
    fun `probe c2 - plain mcg answer is blocked without explicit evidence matching mcg`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer 1500 mcg daily.",
            toolResults = listOf("The dose is 1.5 mg once daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("1500 mcg"))
    }

    @Test
    fun `probe c3 - plain mg answer is blocked from an unrelated 1-dot-5 percent candidate`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer 1500 mg daily.",
            toolResults = listOf("The anesthetic concentration is 1.5% before dilution."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("1500 mg"))
    }

    @Test
    fun `probe c4 - comma-thousand mg answer is blocked from a bare cross-unit 1500 candidate`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer 1,500 mg daily.",
            toolResults = listOf("The patient received 1500 mcg of famotidine."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("1,500 mg"))
    }

    // ══════════════════════════════════════════════════════════════════
    // Change 2: typed violations, deleted answer-side bypass, collect-all rules
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 0 never bypasses non-clinical answers and emits the typed NO_TOOLS violation`() {
        // Change 1/2: the former isNonClinicalResponse greeting bypass is deleted; small
        // talk is owned by the entry router and ANY no-tool turn fails with the exact
        // Phase-1 shape, regardless of how non-clinical the answer reads.
        val result = validator.validate(ctx(answer = "Hello! How can I assist you today?", userQuestion = "Hello"))
        assertFalse(result.passed)
        assertEquals(
            listOf(Violation(ViolationCodes.NO_TOOLS, SafetyValidator.NO_TOOLS_REASON, ViolationTarget.PHASE_1)),
            result.violations
        )
        assertEquals(SafetyValidator.NO_TOOLS_REASON, result.blockedReason)
    }

    @Test
    fun `rules 2 and 3 collect simultaneously with the joined reason in rule order`() {
        // Change 2 collect-all: a draft can breach two Phase-2 rules in one turn; the
        // blocked reason carries BOTH details joined with " | " in rule order.
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Give 999 mg daily and note the image shows a mass.",
            toolResults = listOf("Standard dose is 250 mg daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertEquals(listOf(ViolationCodes.UNVERIFIED_QTY, ViolationCodes.VISUAL), result.violations.map { it.code })
        assertTrue(result.violations.all { it.target == ViolationTarget.PHASE_2 })
        val reason = result.blockedReason!!
        val qtyIdx = reason.indexOf("Unverified clinical quantities")
        val visualIdx = reason.indexOf("Answer describes visual details")
        assertTrue("Rule 2 must precede Rule 3 in the joined reason: $reason", qtyIdx in 0 until visualIdx)
        assertTrue("Joined reason must use the ' | ' separator", reason.contains(" | "))
    }



    // ══════════════════════════════════════════════════════════════════
    // Change 6: Rule-2 boundary fix + report cap + SHADOW semantics
    // (production stays the legacy lenient matcher)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 2 boundary fix blocks 12 mg from a q12h corpus`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Give 12 mg daily.",
            toolResults = listOf("Dose: 50 mg q12h."),
            fetchedSections = listOf(section()),
        ))
        assertFalse("12 must never verify from the '12' inside q12h", result.passed)
        assertTrue(result.blockedReason!!.contains("12 mg"))
    }

    @Test
    fun `rule 2 boundary fix blocks 12 mg from an H12 corpus`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Give 12 mg daily.",
            toolResults = listOf("Room H12 holds 50 mg vials."),
            fetchedSections = listOf(section()),
        ))
        assertFalse("12 must never verify from the '12' inside H12", result.passed)
        assertTrue(result.blockedReason!!.contains("12 mg"))
    }

    @Test
    fun `rule 2 report cap raised to ten unverified quantities`() {
        val quantities = (1..12).joinToString(" ") { "$it mg" }
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Take $quantities daily.",
            toolResults = listOf("No dosing text available."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
        val reason = result.blockedReason!!
        assertTrue("the 10th metric must be reported: $reason", reason.contains("10 mg"))
        assertFalse("metrics past the cap must be truncated: $reason", reason.contains("11 mg"))
        assertFalse("metrics past the cap must be truncated: $reason", reason.contains("12 mg"))
    }

    @Test
    fun `rule 2 enforces deny-by-default and blocks unverified quantities in production`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Administer 0.002 mg daily.",
            toolResults = listOf("The dose is 2 mg once daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("0.002 mg"))
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 2 notation equivalence — the forms UpToDate actually writes
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 2 verifies both bounds of a range written with a dash against a to-range`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Titrate 5-10 mg daily.",
            toolResults = listOf("Usual range: 5 to 10 mg daily."),
            fetchedSections = listOf(section())
        ))
        assertTrue("5-10 mg must verify against '5 to 10 mg'", result.passed)
    }

    @Test
    fun `rule 2 blocks a range whose lower bound was never retrieved`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Titrate 2-10 mg daily.",
            toolResults = listOf("Usual range: 5 to 10 mg daily."),
            fetchedSections = listOf(section())
        ))
        assertFalse("The invented lower bound must block", result.passed)
        assertTrue(result.blockedReason!!.contains("2-10 mg"))
    }

    @Test
    fun `rule 2 treats thousand separators as notation, not a different value`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Give 1,200 mg daily.",
            toolResults = listOf("Give 1200 mg daily."),
            fetchedSections = listOf(section())
        ))
        assertTrue("1,200 mg == 1200 mg", result.passed)
    }

    @Test
    fun `rule 2 treats percent and the word percent as one unit`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Apply a 5% solution.",
            toolResults = listOf("Apply a 5 percent solution."),
            fetchedSections = listOf(section())
        ))
        assertTrue("5% == 5 percent", result.passed)
    }

    @Test
    fun `rule 2 treats micro sign and mcg as one unit`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Give 25 µg daily.",
            toolResults = listOf("Give 25 mcg daily."),
            fetchedSections = listOf(section())
        ))
        assertTrue("25 µg == 25 mcg", result.passed)
    }

    @Test
    fun `rule 2 covers the electrolyte, pressure, and activity units present in the corpus`() {
        val unverified = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Give 40 mEq of potassium chloride; target 120 mmHg; give 10 units of insulin.",
            toolResults = listOf("Give 20 mEq of potassium chloride; target 130 mmHg; give 10 units of insulin."),
            fetchedSections = listOf(section())
        ))
        assertFalse(unverified.passed)
        val reason = unverified.blockedReason!!
        assertTrue("40 mEq must be reported: $reason", reason.contains("40 mEq"))
        assertTrue("120 mmHg must be reported: $reason", reason.contains("120 mmHg"))
        assertFalse("10 units is verified: $reason", reason.contains("10 units"))
    }

    @Test
    fun `rule 2 verifies age thresholds, which decide pediatric eligibility`() {
        // "≥1 year" for honey, "≥2 years" for cough suppressants: these are the values that
        // matter most in a pediatric answer, and "year" was outside the quantity vocabulary.
        val verified = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Honey is an option for children 1 year and older.",
            toolResults = listOf("In children 1 year of age, we use honey to treat cough."),
            fetchedSections = listOf(section())
        ))
        assertTrue("1 year must verify against '1 year of age'", verified.passed)

        val invented = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Honey is an option for children 2 years and older.",
            toolResults = listOf("In children 1 year of age, we use honey to treat cough."),
            fetchedSections = listOf(section())
        ))
        assertFalse("An invented age cutoff must block", invented.passed)
        assertTrue(invented.blockedReason!!.contains("2 years"))
    }

    @Test
    fun `rule 2 treats hyphenated and spaced year forms as one value`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "This is a 10-year risk estimate.",
            toolResults = listOf("Reported as a 10 year risk estimate."),
            fetchedSections = listOf(section())
        ))
        assertTrue("10-year == 10 years", result.passed)
    }

    @Test
    fun `rule 2 accepts a patient-profile value restated in the answer`() {
        val profile = PatientProfile(age = "72", sex = "male", notes = "Weight 70 kg, eGFR 28 mL/min")
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "At 70 kg the renally adjusted dose applies.",
            toolResults = listOf("Use the renal dosing table."),
            fetchedSections = listOf(section()),
            patientProfile = profile
        ))
        assertTrue("Restating a profile value is not invention: ${result.blockedReason}", result.passed)
    }
}