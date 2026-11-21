package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {

    @Test
    fun `SYNTHESIS_SYSTEM_PROMPT resolves canonical abstention and formats correctly`() {
        val prompt = SystemPrompt.SYNTHESIS_SYSTEM_PROMPT

        assertTrue(prompt.contains("You are ClinRef AI, a point-of-care clinical reference for physicians."))
        assertTrue(prompt.contains(SystemPrompt.CANONICAL_ABSTENTION))
        assertFalse(prompt.contains("\$CANONICAL_ABSTENTION"))
        assertTrue(prompt.contains("### 1. Clinical Rules"))
        assertTrue(prompt.contains("### 2. Output Format"))
        assertTrue(prompt.contains("Do not perform arithmetic dose calculations"))
        assertTrue(prompt.contains("### [Concise Subheading]"))
        assertTrue(prompt.contains("### [Additional Subheading"))
        assertTrue(prompt.contains("Line 1 MUST start immediately with `**`"))
        assertTrue(prompt.contains("Keep a blank line before and after `---`."))
        assertTrue(prompt.contains("Use ONLY `###` for subheadings"))
        assertTrue(prompt.contains("contains no relevant information for the inquiry"))
        assertFalse(prompt.contains("state \"Not specified in retrieved sections\" for any uncovered part"))
    }

    @Test
    fun `formatSynthesisPrompt formats user prompt with evidence and inquiry`() {
        val sections = listOf(
            FetchedSection(
                topicId = "123",
                sectionId = "sec-1",
                topicTitle = "Atrial Fibrillation",
                sectionTitle = "Anticoagulation",
                contentSnippet = "Direct oral anticoagulants are preferred."
            )
        )
        val turnContext = TurnContext(
            fetchedSections = sections,
            userQuestion = "What anticoagulation is recommended?"
        )

        val formatted = SystemPrompt.formatSynthesisPrompt(
            userQuestion = "What anticoagulation is recommended?",
            turnContext = turnContext
        )

        assertTrue(formatted.startsWith("<retrieved_evidence>"))
        assertTrue(formatted.contains("Section: Anticoagulation [Topic: Atrial Fibrillation]:"))
        assertTrue(formatted.contains("Direct oral anticoagulants are preferred."))
        assertTrue(formatted.contains("</retrieved_evidence>"))
        assertFalse(formatted.contains("<active_patient_profile>"))
        assertTrue(formatted.contains("<clinical_inquiry>\nWhat anticoagulation is recommended?\n</clinical_inquiry>"))
        assertTrue(formatted.contains("Start Line 1 immediately with your `**1–2 sentence bold direct clinical conclusion.**`"))
    }

    @Test
    fun `formatSynthesisPrompt includes active patient profile when present`() {
        val sections = listOf(
            FetchedSection(
                topicId = "123",
                sectionId = "sec-1",
                topicTitle = "Atrial Fibrillation",
                sectionTitle = "Anticoagulation",
                contentSnippet = "Apixaban dose is reduced to 2.5 mg twice daily if criteria met."
            )
        )
        val profile = PatientProfile(
            age = "82",
            conditions = listOf("Atrial fibrillation", "CKD stage 3"),
            medications = listOf("Metoprolol")
        )
        val turnContext = TurnContext(
            fetchedSections = sections,
            userQuestion = "Dose of apixaban?",
            patientProfile = profile
        )

        val formatted = SystemPrompt.formatSynthesisPrompt(
            userQuestion = "Dose of apixaban?",
            turnContext = turnContext
        )

        assertTrue(formatted.contains("<retrieved_evidence>"))
        assertTrue(formatted.contains("<active_patient_profile>"))
        assertTrue(formatted.contains("- Age: 82"))
        assertTrue(formatted.contains("CKD stage 3"))
        assertTrue(formatted.contains("</active_patient_profile>"))
        assertTrue(formatted.contains("<clinical_inquiry>\nDose of apixaban?\n</clinical_inquiry>"))
        assertTrue(formatted.contains("Start Line 1 immediately with your `**1–2 sentence bold direct clinical conclusion.**`"))
    }

    @Test
    fun `formatSynthesisPrompt backwards-compatible overload works identically`() {
        val sections = listOf(
            FetchedSection(
                topicId = "123",
                sectionId = "sec-1",
                topicTitle = "Pneumonia",
                sectionTitle = "Treatment",
                contentSnippet = "Amoxicillin 1 g three times daily."
            )
        )
        val turnContext = TurnContext(
            fetchedSections = sections,
            userQuestion = "CAP outpatient treatment"
        )

        val fromNew = SystemPrompt.formatSynthesisPrompt(
            userQuestion = "CAP outpatient treatment",
            turnContext = turnContext
        )

        val fromLegacy = SystemPrompt.formatSynthesisPrompt(
            pendingInput = "CAP outpatient treatment",
            userQuestion = "CAP outpatient treatment",
            turnContext = turnContext
        )

        assertEquals(fromNew, fromLegacy)
    }

    @Test
    fun `evidenceSummaryFrom suppresses subsumed pointer stubs when ancestor content is present`() {
        val sections = listOf(
            FetchedSection(
                topicId = "7027",
                sectionId = "H8",
                topicTitle = "CAP",
                sectionTitle = "Initial Empiric Therapy",
                contentSnippet = "Empiric regimen includes ceftriaxone plus azithromycin."
            ),
            FetchedSection(
                topicId = "7027",
                sectionId = "H1595678546",
                topicTitle = "CAP",
                sectionTitle = "Indications",
                contentSnippet = "(Content included in section [H8] above)"
            )
        )
        val accumulator = TurnContextAccumulator()
        sections.forEach { sec ->
            accumulator.onToolCallStarting("call-${sec.sectionId}", """{"topicId":"${sec.topicId}","sectionIds":["${sec.sectionId}"]}""")
            accumulator.onToolCallCompleted(
                toolCallId = "call-${sec.sectionId}",
                toolName = "getTopicSectionsText",
                result = """{"topicId":"${sec.topicId}","topicTitle":"${sec.topicTitle}","sectionTitles":{"${sec.sectionId}":"${sec.sectionTitle}"},"markdown":"=== Section: ${sec.sectionId} ===\n${sec.contentSnippet}"}""",
                success = true
            )
        }
        val turnContext = accumulator.buildTurnContext("")
        val formatted = SystemPrompt.formatSynthesisPrompt("CAP treatment", turnContext)

        assertTrue(formatted.contains("Section: Initial Empiric Therapy [Topic: CAP]:"))
        assertTrue(formatted.contains("Empiric regimen includes ceftriaxone plus azithromycin."))
        assertFalse("Subsumed stub header must not be rendered in retrieved evidence", formatted.contains("Section: Indications"))
        assertFalse("Subsumed pointer text must not be rendered in retrieved evidence", formatted.contains("(Content included in section [H8] above)"))
        assertFalse("Raw sectionId must not be leaked into retrieved evidence", formatted.contains("Section [H8]"))
        assertFalse("Raw sectionId must not be leaked into retrieved evidence", formatted.contains("Section [H1595678546]"))

        // Verify that the fine-grained citation chip is still generated in TurnContext
        val topicRefs = turnContext.topicRefs
        assertTrue("UI chips must retain the specific subsection", topicRefs.any { it.sectionId == "H1595678546" })
    }
}
