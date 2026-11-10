package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-stack behavior parity record (2026-10-26 reconciliation round).
 *
 * The canonical scenarios below are mirrored line-for-line in
 * `python_prototype/test_cross_stack_scenarios.py` (class
 * `TestCrossStackScenarioParity`). Each scenario drives the stack's PUBLIC entry
 * points — the entry router (`QuestionRouter.smallTalk` / `router.small_talk`),
 * the deterministic validator (`SafetyValidator.validate` /
 * `SafetyValidator.validate`), and the Phase-2 revision template
 * (`SystemPrompt.buildRevisionPrompt` / `system_prompt.build_revision_prompt`) —
 * never the agent graphs. Question strings, evidence strings, answers, and
 * expected verdicts MUST stay identical across the two files so any future drift
 * on either stack fails its own suite.
 */
class CrossStackScenarioParityTest {

    private val validator = SafetyValidator()

    // ── Helpers ──────────────────────────────────────────────────────

    private fun ctx(
        toolCalls: List<SafetyValidator.ToolCallRecord> = emptyList(),
        answer: String = "",
        toolResults: List<String> = emptyList(),
        userQuestion: String = "",
        patientProfile: PatientProfile? = null
    ) = SafetyValidator.TurnContext(
        toolCalls = toolCalls,
        answer = answer,
        toolResults = toolResults,
        userQuestion = userQuestion,
        patientProfile = patientProfile
    )

    private fun toolCall(
        name: String,
        args: Map<String, String>,
        result: String
    ) = SafetyValidator.ToolCallRecord(name, args, result, success = true)

    // ══════════════════════════════════════════════════════════════════
    // Scenario 0 — greeting followed by a clinical request
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 0 greeting with clinical tail routes clinical`() {
        // The question is clinical even though it opens with a greeting.
        for (question in listOf(
            "hello, pulmonary embolism treatment",
            "thanks, dose of apixaban",
            "hi there, metformin in ckd"
        )) {
            assertFalse(question, QuestionRouter.smallTalk(question))
        }
        // The bare greeting is the only form the entry router answers.
        assertTrue(QuestionRouter.smallTalk("hello"))
    }

    // ══════════════════════════════════════════════════════════════════
    // Scenario 1 — narration-only answer with no tool calls
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 1 narration-only answer routes clinical and blocks with NO_TOOLS`() {
        val question = "Tell me about hypertension management"
        val answer = "Hypertension is best managed with lifestyle changes and medication."

        // Entry router: the clinical question is NOT small talk (never bypasses Rule 0).
        assertFalse(QuestionRouter.smallTalk(question))

        val result = validator.validate(ctx(userQuestion = question, answer = answer))
        assertFalse(result.passed)
        assertEquals(1, result.violations.size)
        assertEquals(ViolationCodes.NO_TOOLS, result.violations[0].code)
        assertEquals(ViolationTarget.PHASE_1, result.violations[0].target)
        assertEquals(SafetyValidator.NO_TOOLS_REASON, result.blockedReason)
    }

    // ══════════════════════════════════════════════════════════════════
    // Scenario 2 — search-only turn (no section/table fetch)
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 2 search-only turn routes clinical and blocks with NO_SECTIONS`() {
        val question = "What is the apixaban dose?"

        assertFalse(QuestionRouter.smallTalk(question))

        val result = validator.validate(ctx(
            userQuestion = question,
            answer = "Apixaban is an oral anticoagulant.",
            toolCalls = listOf(toolCall(
                name = "searchTopics",
                args = mapOf("query" to "apixaban"),
                result = "{\"results\": []}"
            ))
        ))
        assertFalse(result.passed)
        assertEquals(1, result.violations.size)
        assertEquals(ViolationCodes.NO_SECTIONS, result.violations[0].code)
        assertEquals(ViolationTarget.PHASE_1, result.violations[0].target)
        assertEquals(SafetyValidator.NO_SECTIONS_REASON, result.blockedReason)
    }

    // ══════════════════════════════════════════════════════════════════
    // Scenario 3 — unverified "10 mg" against "1 mg" evidence
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `scenario 3 unverified 10 mg versus evidence 1 mg blocks UNVERIFIED_QTY and revises`() {
        val question = "What is the apixaban dose?"
        val evidence = "For atrial fibrillation, give apixaban 1 mg twice daily."
        val answer = "Give apixaban 10 mg twice daily."

        assertFalse(QuestionRouter.smallTalk(question))

        val result = validator.validate(ctx(
            userQuestion = question,
            answer = answer,
            toolResults = listOf(evidence),
            toolCalls = listOf(toolCall(
                name = "getTopicSectionsText",
                args = mapOf("topicId" to "1", "sectionIds" to "H1"),
                result = evidence
            ))
        ))
        assertFalse(result.passed)
        assertEquals(1, result.violations.size)
        assertEquals(ViolationCodes.UNVERIFIED_QTY, result.violations[0].code)
        assertEquals(ViolationTarget.PHASE_2, result.violations[0].target)
        assertEquals(
            "Unverified clinical quantities found in response: 10 mg. " +
                "Ensure every quantity or dosage matches the retrieved database section text exactly.",
            result.violations[0].detail
        )

        val prompt = SystemPrompt.buildRevisionPrompt(result.violations, answer, evidence)
        assertTrue(
            prompt.contains("[UNVERIFIED_QTY] Unverified clinical quantities found in response: 10 mg.")
        )
    }
}
