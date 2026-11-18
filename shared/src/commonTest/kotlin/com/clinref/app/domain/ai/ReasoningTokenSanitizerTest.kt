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
    fun `strips intermediate thought numbers from output so final response contains only clean clinical answer`() {
        val rawLlmAnswer = "<thought>Standard dose is 1000 mg, but patient eGFR is 20 mL/min. Adjust to 250 mg.</thought>Give 250 mg orally."
        val cleanAnswer = ReasoningTokenSanitizer.stripReasoning(rawLlmAnswer)
        assertEquals("Give 250 mg orally.", cleanAnswer)
        assertFalse(cleanAnswer.contains("1000 mg"))
        assertFalse(cleanAnswer.contains("<thought>"))
    }
}
