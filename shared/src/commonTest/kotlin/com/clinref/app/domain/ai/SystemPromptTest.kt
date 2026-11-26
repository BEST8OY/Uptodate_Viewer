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

    @Test
    fun `evidenceSummaryFrom orders sections and graphics by relevanceScore descending without truncating content`() {
        val secLow = FetchedSection(
            topicId = "1",
            sectionId = "S_LOW",
            topicTitle = "Cardiology",
            sectionTitle = "General Background",
            contentSnippet = "Background epidemiology information.",
            relevanceScore = 1.2
        )
        val secHigh = FetchedSection(
            topicId = "1",
            sectionId = "S_HIGH",
            topicTitle = "Cardiology",
            sectionTitle = "Dosing and Administration",
            contentSnippet = "Primary first-line dosing is 5 mg daily.",
            relevanceScore = 2.9
        )
        val gfxLow = FetchedGraphic(
            graphicId = "G_LOW",
            title = "Secondary monitoring table",
            markdown = "| Param | Value |\n| --- | --- |\n| Lab | Normal |",
            relevanceScore = 1.5
        )
        val gfxHigh = FetchedGraphic(
            graphicId = "G_HIGH",
            title = "Primary Dosing Table",
            markdown = "| Drug | Dose |\n| --- | --- |\n| DrugA | 100 mg |",
            relevanceScore = 2.8
        )

        val summary = SystemPrompt.evidenceSummaryFrom(
            sections = listOf(secLow, secHigh),
            graphics = listOf(gfxLow, gfxHigh)
        )

        // Verify high-relevance section appears before low-relevance section
        val posHighSec = summary.indexOf("Dosing and Administration")
        val posLowSec = summary.indexOf("General Background")
        assertTrue("High relevance section must appear before low relevance section", posHighSec in 0 until posLowSec)

        // Verify high-relevance graphic appears before low-relevance graphic
        val posHighGfx = summary.indexOf("Primary Dosing Table")
        val posLowGfx = summary.indexOf("Secondary monitoring table")
        assertTrue("High relevance graphic must appear before low relevance graphic", posHighGfx in 0 until posLowGfx)

        // Verify full untruncated content is preserved (no context cap)
        assertTrue(summary.contains("Primary first-line dosing is 5 mg daily."))
        assertTrue(summary.contains("Background epidemiology information."))
        assertTrue(summary.contains("| Drug | Dose |"))
        assertTrue(summary.contains("| Lab | Normal |"))
    }

    @Test
    fun `evidenceSummaryFrom strips redundant Graphic Table markdown header to eliminate duplicate title`() {
        val gfx = FetchedGraphic(
            graphicId = "118967",
            title = "Empiric Antibiotic Regimens",
            markdown = "### Graphic Table: Empiric Antibiotic Regimens\n\n| Antibiotic | Dose |\n|---|---|\n| Amoxicillin | 1 g TID |",
            topicId = "7027",
            topicTitle = "CAP",
            relevanceScore = 2.8
        )

        val summary = SystemPrompt.evidenceSummaryFrom(
            sections = emptyList(),
            graphics = listOf(gfx)
        )

        // Must contain the wrapped provenance header
        assertTrue(summary.contains("Table: Empiric Antibiotic Regimens [Topic: CAP]:"))
        // Must NOT duplicate ### Graphic Table: Empiric Antibiotic Regimens
        assertFalse(summary.contains("### Graphic Table:"))
        // Must retain table content
        assertTrue(summary.contains("| Amoxicillin | 1 g TID |"))
    }

    @Test
    fun `evidenceSummaryFrom orders calculator section ahead of lower-scored sections`() {
        val calcSec = FetchedSection(
            topicId = "calc-crcl",
            topicTitle = "CrCl Cockcroft-Gault Calculator",
            sectionId = "FULL",
            sectionTitle = "Calculator Tool Content",
            contentSnippet = "CrCl = [(140 - age) * weight] / (72 * SCr)",
            relevanceScore = 3.0
        )
        val medSec = FetchedSection(
            topicId = "med-afib",
            topicTitle = "Atrial Fibrillation Management",
            sectionId = "H1234",
            sectionTitle = "Anticoagulation",
            contentSnippet = "Oral anticoagulation recommended if CHA2DS2-VASc >= 2.",
            relevanceScore = 2.8
        )

        val summary = SystemPrompt.evidenceSummaryFrom(
            sections = listOf(medSec, calcSec),
            graphics = emptyList()
        )

        val posCalc = summary.indexOf("Cockcroft-Gault")
        val posMed = summary.indexOf("Atrial Fibrillation")
        assertTrue("Calculator with score 3.0 must appear before section with score 2.8", posCalc in 0 until posMed)
    }
}
