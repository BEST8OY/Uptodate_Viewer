package com.clinref.app.e2e

import com.clinref.app.domain.ai.ClinicalSource
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.SystemPrompt
import com.clinref.app.domain.ai.TurnContextAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end opaque-box tests for clinical inquiry workflows.
 *
 * Verifies full multi-turn interaction cycles:
 * 1. Standard clinical inquiry with evidence retrieval -> validated response with auto-populated citations.
 * 2. Conversational bypass of database tool requirement.
 * 3. Interception and blocking of premature clinical assertions without evidence retrieval.
 * 4. Multi-turn self-correction remediation loop via [SystemPrompt.buildCorrectionPrompt].
 * 5. Terminal safety blocking when remediation budget is exhausted.
 */
class ClinicalInquiryE2ETest {

    @Test
    fun testFullClinicalInquiryFlowWithAccumulatedEvidenceAndCitations() {
        val topicTitleMap = mapOf(
            "128998" to "Atrial Fibrillation Management",
            "1" to "Practice Changing Updates"
        )
        val sectionTitleMap = mapOf(
            ("128998" to "H1") to "Dosing and Administration",
            ("128998" to "H2") to "Renal Impairment Adjustments"
        )

        val accumulator = TurnContextAccumulator(
            topicTitleResolver = { tid -> topicTitleMap[tid] },
            sectionTitleResolver = { tid, sid -> sectionTitleMap[tid to sid] }
        )
        val validator = SafetyValidator()

        // 1. User submits clinical question
        accumulator.setUserQuestion("What is the standard and renal-adjusted dosing of apixaban in atrial fibrillation?")

        // 2. Model searches topics
        accumulator.onToolCallStarting("call-1", """{"query":"apixaban"}""")
        accumulator.onToolCallCompleted(
            "call-1",
            "searchTopics",
            """{"results":[{"id":"128998","title":"Atrial Fibrillation Management"}]}""",
            true
        )

        // 3. Model retrieves topic outline
        accumulator.onToolCallStarting("call-2", """{"topicId":"128998"}""")
        accumulator.onToolCallCompleted(
            "call-2",
            "getTopicOverview",
            """{"topicId":"128998","title":"Atrial Fibrillation Management","sections":[{"id":"H1","title":"Dosing and Administration"},{"id":"H2","title":"Renal Impairment Adjustments"}]}""",
            true
        )

        // 4. Model retrieves sections in batch
        val evidenceSnippet = "Standard dose of apixaban is 5 mg twice daily. In patients with eGFR or CrCl < 30 mL/min, reduce dose to 2.5 mg twice daily."
        accumulator.onToolCallStarting("call-3", """{"topicId":"128998","sectionIds":["H1","H2"]}""")
        accumulator.onToolCallCompleted(
            "call-3",
            "getTopicSectionsText",
            """{"topicId":"128998","topicTitle":"Atrial Fibrillation Management","sectionTitles":{"H1":"Dosing and Administration","H2":"Renal Impairment Adjustments"},"markdown":"$evidenceSnippet"}""",
            true
        )

        // 5. Model generates synthesized clinical response adhering to retrieved numbers
        val clinicalResponse = "Standard apixaban dosing is 5 mg twice daily. For patients with eGFR or CrCl < 30 mL/min, reduce to 2.5 mg twice daily."
        val turnContext = accumulator.buildTurnContext(clinicalResponse)

        // 6. Safety validation verifies quantities and evidence grounding
        val validationResult = validator.validate(turnContext)
        assertTrue("Validation should pass with verified numbers", validationResult.passed)
        assertEquals(null, validationResult.blockedReason)

        // 7. Citations auto-populated without manual LLM citations
        assertEquals(2, turnContext.topicRefs.size)
        val h1Ref = turnContext.topicRefs.first { it.sectionId == "H1" }
        assertEquals("128998", h1Ref.topicId)
        assertEquals("Atrial Fibrillation Management", h1Ref.topicTitle)
        assertEquals("Dosing and Administration", h1Ref.label)

        // 8. Transform to ClinicalSource representations
        val (articles, tables) = ClinicalSource.fromResolved(
            topicRefs = turnContext.topicRefs.map {
                com.clinref.app.ui.chat.ResolvedTopicRef(it.topicId, it.sectionId, it.label, it.topicTitle)
            },
            graphicRefs = emptyList()
        )
        assertEquals(1, articles.size)
        val articleSource = articles[0]
        assertEquals("128998", articleSource.topicId)
        assertEquals("Atrial Fibrillation Management", articleSource.topicTitle)
        assertEquals(2, articleSource.sections.size)
    }

    @Test
    fun testConversationalGreetingBypassesToolRequirement() {
        val accumulator = TurnContextAccumulator()
        val validator = SafetyValidator()

        accumulator.setUserQuestion("Good morning, what are your capabilities?")
        val conversationalAnswer = "Good morning! I am ClinRef AI, your offline clinical evidence assistant. You can ask me questions about medical guidelines, drug dosages, and clinical topics."

        val turnContext = accumulator.buildTurnContext(conversationalAnswer)
        val validationResult = validator.validate(turnContext)

        assertTrue("Conversational greeting should pass Rule 0 without tool calls", validationResult.passed)
        assertEquals(0, turnContext.topicRefs.size)
    }

    @Test
    fun testPrematureClinicalResponseWithoutToolsIsBlocked() {
        val accumulator = TurnContextAccumulator()
        val validator = SafetyValidator()

        // Clinical query asked, but LLM responds immediately with specific medical advice without tools
        accumulator.setUserQuestion("What is the dose of amoxicillin for strep pharyngitis?")
        val prematureAnswer = "The recommended dose of amoxicillin is 500 mg orally every 12 hours for 10 days."

        val turnContext = accumulator.buildTurnContext(prematureAnswer)
        val validationResult = validator.validate(turnContext)

        assertFalse("Premature clinical response without tool execution must be blocked", validationResult.passed)
        assertTrue(
            "Blocked reason must indicate database verification requirement",
            validationResult.blockedReason?.contains("Clinical recommendations require database verification") == true
        )
    }

    @Test
    fun testInGraphSelfCorrectionRemediationCycle() {
        val accumulator = TurnContextAccumulator()
        val validator = SafetyValidator()

        accumulator.setUserQuestion("What is the starting dose of lisinopril?")

        // Evidence retrieved
        val evidence = "The usual initial dose of lisinopril is 10 mg once daily."
        accumulator.onToolCallStarting("call-1", """{"topicId":"100","sectionId":"H1"}""")
        accumulator.onToolCallCompleted(
            "call-1",
            "getTopicSectionsText",
            """{"topicId":"100","topicTitle":"Lisinopril","sectionTitles":{"H1":"Dosing"},"markdown":"$evidence"}""",
            true
        )

        // Turn 1: Model makes an unverified claim with invented number 40 mg
        val invalidTurn1 = "Initial starting dose of lisinopril is 40 mg once daily."
        val ctxTurn1 = accumulator.buildTurnContext(invalidTurn1)
        val valTurn1 = validator.validate(ctxTurn1)

        assertFalse("Turn 1 with invented dose must fail validation", valTurn1.passed)
        assertTrue(valTurn1.blockedReason?.contains("40") == true)

        // Prepare for remediation turn
        val snapshot = accumulator.snapshotForCorrection()
        accumulator.prepareForCorrection()

        val evidenceSummary = snapshot.buildTurnContext("").fetchedSections.joinToString("\n---\n") {
            "Section [${it.sectionId}] (${it.sectionTitle}):\n${it.contentSnippet}"
        }.ifBlank { "No section text was successfully fetched." }

        // Build context-aware correction prompt
        val correctionPrompt = SystemPrompt.buildCorrectionPrompt(
            blockedReason = valTurn1.blockedReason ?: "",
            evidenceSummary = evidenceSummary
        )

        assertTrue(correctionPrompt.contains("SYSTEM NOTICE"))
        assertTrue(correctionPrompt.contains("REMEDIAL INSTRUCTIONS"))
        assertTrue(correctionPrompt.contains("40"))

        // Turn 2: Model adheres to correction prompt and rectifies answer to verified 10 mg
        val rectifiedTurn2 = "Based on the retrieved evidence, the usual initial dose of lisinopril is 10 mg once daily."
        val ctxTurn2 = accumulator.buildTurnContext(rectifiedTurn2)
        val valTurn2 = validator.validate(ctxTurn2)

        assertTrue("Turn 2 with rectified dosage must pass validation", valTurn2.passed)
        assertEquals(null, valTurn2.blockedReason)
    }

    @Test
    fun testTerminalBlockWhenMaxRetriesExhausted() {
        val accumulator = TurnContextAccumulator()
        val validator = SafetyValidator()

        accumulator.setUserQuestion("What is the dose of drug X?")
        accumulator.onToolCallStarting("call-1", """{"topicId":"50","sectionId":"H1"}""")
        accumulator.onToolCallCompleted(
            "call-1",
            "getTopicSectionsText",
            """{"topicId":"50","topicTitle":"Drug X","sectionTitles":{"H1":"Dosing"},"markdown":"Evidence states 5 mg."}""",
            true
        )

        val maxRetries = 2
        var currentRetry = 0
        var terminalBlocked = false
        var terminalMessage = ""

        while (currentRetry <= maxRetries) {
            // Continually produces invalid number 99 mg
            val answer = "Drug X should be given at 99 mg daily."
            val ctx = accumulator.buildTurnContext(answer)
            val validation = validator.validate(ctx)

            if (!validation.passed) {
                if (currentRetry < maxRetries) {
                    currentRetry++
                    accumulator.prepareForCorrection()
                } else {
                    terminalBlocked = true
                    terminalMessage = "Clinical Response Verification Blocked: ${validation.blockedReason}"
                    break
                }
            }
        }

        assertTrue("Should reach terminal blocked state after exhausting retries", terminalBlocked)
        assertEquals(2, currentRetry)
        assertTrue(terminalMessage.startsWith("Clinical Response Verification Blocked:"))
        assertTrue(terminalMessage.contains("99"))
    }
}
