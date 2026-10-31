package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningTokenSanitizerTest {

    @Test
    fun `strips thought tags cleanly`() {
        val input = "<thought>Check eGFR 22 mL/min and dose 500mg</thought>Amoxicillin 250 mg orally every 12 hours."
        val sanitized = ReasoningTokenSanitizer.sanitize(input)
        assertEquals("Amoxicillin 250 mg orally every 12 hours.", sanitized.cleanText)
        assertEquals("Check eGFR 22 mL/min and dose 500mg", sanitized.reasoningText)
    }

    @Test
    fun `strips think tags (DeepSeek format)`() {
        val input = "<think>Patient is 70yo, CrCl is 45 mL/min</think>The recommended dosage is 50 mg daily."
        val clean = ReasoningTokenSanitizer.stripReasoning(input)
        assertEquals("The recommended dosage is 50 mg daily.", clean)
    }

    @Test
    fun `strips reasoning tags with attributes`() {
        val input = """<thought type="clinical_step">Consider penicillin allergy.</thought>Azithromycin 500 mg day 1."""
        val sanitized = ReasoningTokenSanitizer.sanitize(input)
        assertEquals("Azithromycin 500 mg day 1.", sanitized.cleanText)
        assertEquals("Consider penicillin allergy.", sanitized.reasoningText)
    }

    @Test
    fun `strips unclosed thought block at end of truncated stream`() {
        val input = "<thought>Evaluating patient symptoms: fever, cough..."
        val clean = ReasoningTokenSanitizer.stripReasoning(input)
        assertEquals("", clean)
        assertEquals("Evaluating patient symptoms: fever, cough...", ReasoningTokenSanitizer.extractReasoning(input))
    }

    @Test
    fun `preserves clinical mathematical less-than comparisons`() {
        val input = "eGFR < 30 mL/min requires dose reduction to 250 mg. Hemoglobin < 7 g/dL indicates transfusion."
        val clean = ReasoningTokenSanitizer.stripReasoning(input)
        assertEquals(input, clean)
        assertFalse(ReasoningTokenSanitizer.containsReasoning(input))
    }

    @Test
    fun `prevents number validation false positives in SafetyValidator`() {
        val validator = SafetyValidator()
        // Evidence text only has 250 mg
        val toolResult = "Dosing in renal impairment: 250 mg orally every 12 hours."
        val toolCall = SafetyValidator.ToolCallRecord(
            toolName = "getTopicSectionsText",
            arguments = mapOf("topicId" to "123"),
            result = toolResult,
            success = true
        )

        // Raw output has hypothetical 1000 mg in thought
        val rawLlmAnswer = "<thought>Standard dose is 1000 mg, but patient eGFR is 20 mL/min. Adjust to 250 mg.</thought>Give 250 mg orally."

        // Without sanitization: Fails because 1000 mg is not in toolResult
        val unhygienicContext = SafetyValidator.TurnContext(
            toolCalls = listOf(toolCall),
            answer = rawLlmAnswer,
            toolResults = listOf(toolResult)
        )
        val unhygienicResult = validator.validate(unhygienicContext)
        assertFalse("Unhygienic output must fail invented numbers", unhygienicResult.passed)
        assertTrue(unhygienicResult.blockedReason?.let { it.contains("1000 mg") || it.contains("Unverified") } == true)

        // With sanitization: Passes because 1000 mg is stripped from answer
        val cleanAnswer = ReasoningTokenSanitizer.stripReasoning(rawLlmAnswer)
        val hygienicContext = SafetyValidator.TurnContext(
            toolCalls = listOf(toolCall),
            answer = cleanAnswer,
            toolResults = listOf(toolResult)
        )
        val hygienicResult = validator.validate(hygienicContext)
        assertTrue("Hygienic output must pass safety validation: ${hygienicResult.blockedReason}", hygienicResult.passed)
    }

    @Test
    fun `greeting without tools is a NO_TOOLS violation no matter how the answer reads`() {
        // Change 1/2: the former isNonClinicalResponse bypass is deleted — a soap-safe
        // small-talk draft with zero tool calls NEVER passes Rule 0 (the entry router
        // owns small talk; the validator only ever sees clinical-graph turns).
        val validator = SafetyValidator()
        // Model thinks with numbers/% and then writes a pure greeting.
        val rawLlmAnswer = "<thought>Friendly greeting. 100% confidence. No dosing needed.</thought>Hello! How can I assist you today?"

        val cleanAnswer = ReasoningTokenSanitizer.stripReasoning(rawLlmAnswer)
        assertEquals("Hello! How can I assist you today?", cleanAnswer)

        val cleanResult = validator.validate(
            SafetyValidator.TurnContext(
                toolCalls = emptyList(),
                answer = cleanAnswer,
                userQuestion = "Hello"
            )
        )
        assertFalse("Non-clinical replies no longer bypass Rule 0", cleanResult.passed)
        assertEquals(ViolationCodes.NO_TOOLS, cleanResult.violations.single().code)
        assertEquals(SafetyValidator.NO_TOOLS_REASON, cleanResult.blockedReason)
    }
}
