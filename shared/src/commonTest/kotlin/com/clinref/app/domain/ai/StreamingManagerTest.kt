package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingManagerTest {

    private lateinit var manager: StreamingManager

    @Before
    fun setUp() {
        manager = StreamingManager()
    }

    @Test
    fun `routes native reasoning deltas to reasoningText StateFlow`() {
        manager.onStreamingReasoningDelta("Reasoning step 1: check allergies.")
        manager.onStreamingReasoningDelta(" Step 2: verify CrCl.")
        manager.onStreamingTextDelta("The recommended antibiotic is Ceftriaxone 1 g IV daily.")

        assertEquals("Reasoning step 1: check allergies. Step 2: verify CrCl.", manager.reasoningText.value)
        assertEquals("The recommended antibiotic is Ceftriaxone 1 g IV daily.", manager.streamingText.value)
    }

    @Test
    fun `demuxes inline thought tags across fragmented stream chunks`() {
        // Simulating realistic token chunks from LLM
        val chunks = listOf(
            "<tho",
            "ught>",
            "Checking contraindications for patient with eGFR < 30.",
            " Dose should be reduced.",
            "</tho",
            "ught>",
            "\n\nAmoxicillin ",
            "250 mg ",
            "every 12 hours."
        )

        for (chunk in chunks) {
            manager.onStreamingTextDelta(chunk)
        }
        manager.onStreamingEnd()

        assertEquals(
            "Checking contraindications for patient with eGFR < 30. Dose should be reduced.",
            manager.reasoningText.value
        )
        assertEquals("Amoxicillin 250 mg every 12 hours.", manager.streamingText.value)
    }

    @Test
    fun `preserves clinical mathematical comparisons in streaming text`() {
        manager.onStreamingTextDelta("Hemoglobin < 7 g/dL or platelet count < 50,000/mcL.")
        manager.onStreamingEnd()

        assertEquals("Hemoglobin < 7 g/dL or platelet count < 50,000/mcL.", manager.streamingText.value)
        assertEquals("", manager.reasoningText.value)
    }

    @Test
    fun `clears both streaming and reasoning buffers on tool call start and reset`() {
        manager.onStreamingReasoningDelta("Intermediate thinking...")
        manager.onStreamingTextDelta("Partial answer...")

        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"100"}""")
        assertEquals("", manager.streamingText.value)
        assertEquals("", manager.reasoningText.value)

        manager.onStreamingTextDelta("Fresh clinical recommendation.")
        manager.reset()
        assertEquals("", manager.streamingText.value)
        assertEquals("", manager.reasoningText.value)
    }

    @Test
    fun `manages synthesis step lifecycle in orchestration indicator`() {
        manager.onToolCallStarting("searchTopics", """{"query":"common cold"}""", "call_1")
        assertEquals(StreamingManager.StepStatus.IN_PROGRESS, manager.orchestrationSteps.value.first().status)

        // Starting synthesis completes previous in-progress steps and adds step_synthesis
        manager.onSynthesisStarting()

        val steps = manager.orchestrationSteps.value
        assertEquals(2, steps.size)
        assertEquals(StreamingManager.StepStatus.COMPLETED, steps[0].status)
        assertEquals("step_synthesis", steps[1].id)
        assertEquals("Synthesize Clinical Guidance", steps[1].title)
        assertEquals("Drafting evidence-grounded clinical recommendations", steps[1].detail)
        assertEquals(StreamingManager.StepStatus.IN_PROGRESS, steps[1].status)
        assertEquals("Synthesizing point-of-care guidance…", manager.currentStatusText.value)

        // Waiting for LLM during synthesis preserves synthesis status text
        manager.onWaitingForLlm()
        assertEquals("Synthesizing point-of-care guidance…", manager.currentStatusText.value)

        // Completion transitions synthesis step to COMPLETED
        manager.onCompleted("Final clinical recommendations.", SafetyValidator.ValidationResult(passed = true, warnings = emptyList()))
        val completedSteps = manager.orchestrationSteps.value
        assertEquals(StreamingManager.StepStatus.COMPLETED, completedSteps[1].status)
        assertEquals("Complete", manager.currentStatusText.value)
    }

    @Test
    fun `resolves raw topic IDs to titles from search results cache`() {
        // Step 1: searchTopics returns candidates with titles
        val searchJson = """
            {
                "query": "common cold",
                "results": [
                    {"id": "16629", "title": "The common cold in adults: Treatment and prevention"}
                ],
                "message": ""
            }
        """.trimIndent()
        manager.onToolCallStarting("searchTopics", """{"query":"common cold"}""", "call_search")
        manager.onToolCallCompleted("searchTopics", searchJson, "call_search")

        // Step 2: getTopicOverview uses raw ID "16629" in args
        manager.onToolCallStarting("getTopicOverview", """{"topicId":"16629"}""", "call_overview")

        val overviewStep = manager.orchestrationSteps.value.find { it.id == "call_overview" }
        // Should resolve to human-readable title, NOT "Topic ID: 16629"
        assertEquals(
            "Overview: \"The common cold in adults: Treatment and prevention\"",
            overviewStep?.detail
        )
    }

    @Test
    fun `does not fall back to raw topic ID when title is unknown`() {
        manager.onToolCallStarting("getTopicOverview", """{"topicId":"99999"}""", "call_unknown")
        val step = manager.orchestrationSteps.value.find { it.id == "call_unknown" }
        // Should be null, never raw "Topic ID: 99999"
        assertEquals(null, step?.detail)
    }

    @Test
    fun `correlates tool call completions accurately by callId`() {
        manager.onToolCallStarting("getTopicOverview", """{"topicId":"16629"}""", "call_1")
        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"16629"}""", "call_2")

        val stepsInitial = manager.orchestrationSteps.value
        assertEquals(2, stepsInitial.size)
        assertTrue(stepsInitial.all { it.status == StreamingManager.StepStatus.IN_PROGRESS })

        // Complete call_1
        val overviewJson = """
            {
                "topicId": "16629",
                "title": "The common cold in adults: Treatment and prevention",
                "sections": [{"secId": "s1", "title": "Clinical presentation", "anchor": "s1"}],
                "relatedTopics": []
            }
        """.trimIndent()
        manager.onToolCallCompleted("getTopicOverview", overviewJson, "call_1")

        val stepsAfterFirst = manager.orchestrationSteps.value
        assertEquals(StreamingManager.StepStatus.COMPLETED, stepsAfterFirst.find { it.id == "call_1" }?.status)
        assertEquals(StreamingManager.StepStatus.IN_PROGRESS, stepsAfterFirst.find { it.id == "call_2" }?.status)
    }

    @Test
    fun `onCompleted preserves streamingText while onError and reset clear it`() {
        manager.onStreamingTextDelta("Partial recommendation")
        assertEquals("Partial recommendation", manager.streamingText.value)

        manager.onCompleted("Final clinical guidance", SafetyValidator.ValidationResult(passed = true, warnings = emptyList()))
        assertEquals("Final clinical guidance", manager.streamingText.value)

        manager.onError("Network failure")
        assertEquals("", manager.streamingText.value)

        manager.onStreamingTextDelta("New partial")
        assertEquals("New partial", manager.streamingText.value)

        manager.reset()
        assertEquals("", manager.streamingText.value)
    }

    @Test
    fun `onSynthesisStarting clears streaming and reasoning buffers for clean synthesis or revision pass`() {
        manager.onStreamingReasoningDelta("Pre-synthesis reasoning")
        manager.onStreamingTextDelta("Draft text with unverified quantity")
        assertEquals("Draft text with unverified quantity", manager.streamingText.value)
        assertEquals("Pre-synthesis reasoning", manager.reasoningText.value)

        manager.onSynthesisStarting()

        assertEquals("streamingText must be cleared on synthesis start/restart", "", manager.streamingText.value)
        assertEquals("reasoningText must be cleared on synthesis start/restart", "", manager.reasoningText.value)
        assertFalse(manager.isReasoning.value)

        // Verifying revision pass starts clean
        manager.onStreamingTextDelta("Revised text without quantities")
        assertEquals("Revised text without quantities", manager.streamingText.value)
    }

    @Test
    fun `transitions orchestration phases through evidence retrieval, synthesis, verification, and completion`() {
        assertEquals(StreamingManager.OrchestrationPhase.IDLE, manager.orchestrationPhase.value)
        assertFalse(manager.isDraft.value)
        assertFalse(manager.isRevised.value)

        // Stage 1: Evidence Retrieval starts on tool call
        manager.onToolCallStarting("searchTopics", """{"query":"pneumonia"}""", "call_1")
        assertEquals(StreamingManager.OrchestrationPhase.EVIDENCE_RETRIEVAL, manager.orchestrationPhase.value)
        assertFalse(manager.isDraft.value)

        // Stage 2: Synthesis starts
        manager.onSynthesisStarting()
        assertEquals(StreamingManager.OrchestrationPhase.SYNTHESIS, manager.orchestrationPhase.value)
        assertTrue(manager.isDraft.value)

        // Gate 2: Verification starts
        manager.onVerificationStarting()
        assertEquals(StreamingManager.OrchestrationPhase.VERIFICATION, manager.orchestrationPhase.value)
        assertTrue(manager.isDraft.value)
        assertEquals("Verifying clinical quantities against sources…", manager.currentStatusText.value)

        // Gate 2 Revision pass
        manager.onRevisionStarting()
        assertTrue(manager.isRevised.value)
        assertEquals("Rechecking values against evidence…", manager.currentStatusText.value)

        // Completed
        manager.onCompleted("Final answer", SafetyValidator.ValidationResult(passed = true, warnings = emptyList()))
        assertEquals(StreamingManager.OrchestrationPhase.COMPLETED, manager.orchestrationPhase.value)
        assertFalse(manager.isDraft.value)

        // Reset returns to IDLE
        manager.reset()
        assertEquals(StreamingManager.OrchestrationPhase.IDLE, manager.orchestrationPhase.value)
        assertFalse(manager.isDraft.value)
        assertFalse(manager.isRevised.value)
    }

    @Test
    fun `corrects 0 sections detail and avoids getting stuck in fetching evidence state`() {
        // Topic setup
        val searchJson = """{"query":"moxifloxacin","results":[{"id":"101","title":"Moxifloxacin: Drug information"}],"message":""}"""
        manager.onToolCallStarting("searchTopics", """{"query":"moxifloxacin"}""", "call_search")
        manager.onToolCallCompleted("searchTopics", searchJson, "call_search")

        // Start fetching sections
        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"101","sectionIds":["nonexistent"]}""", "call_sections")
        val initialStep = manager.orchestrationSteps.value.find { it.id == "call_sections" }
        assertEquals("Fetching evidence from \"Moxifloxacin: Drug information\"", initialStep?.detail)

        // Complete with 0 sections matched
        val emptySectionsJson = """{"topicId":"101","topicTitle":"Moxifloxacin: Drug information","sectionTitles":{},"markdown":""}"""
        manager.onToolCallCompleted("getTopicSectionsText", emptySectionsJson, "call_sections")

        val completedStep = manager.orchestrationSteps.value.find { it.id == "call_sections" }
        assertEquals(StreamingManager.StepStatus.COMPLETED, completedStep?.status)
        // Must NOT remain stuck as "Fetching evidence from..."!
        assertEquals("0 sections matched in \"Moxifloxacin: Drug information\"", completedStep?.detail)
    }

    @Test
    fun `tracks accumulated section count and singularizes section labels`() {
        assertEquals(0, manager.sectionsReadCount.value)

        // 1 section response
        val oneSectionJson = """{"topicId":"101","topicTitle":"Amoxicillin","sectionTitles":{"s1":"Dosing"},"markdown":"Text"}"""
        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"101"}""", "call_1")
        manager.onToolCallCompleted("getTopicSectionsText", oneSectionJson, "call_1")

        assertEquals(1, manager.sectionsReadCount.value)
        val step1 = manager.orchestrationSteps.value.find { it.id == "call_1" }
        assertEquals("1 section from \"Amoxicillin\"", step1?.detail)

        // 3 sections response
        val threeSectionsJson = """{"topicId":"102","topicTitle":"Doxycycline","sectionTitles":{"s1":"Dosing","s2":"Adverse","s3":"Renal"},"markdown":"Text"}"""
        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"102"}""", "call_2")
        manager.onToolCallCompleted("getTopicSectionsText", threeSectionsJson, "call_2")

        assertEquals(4, manager.sectionsReadCount.value)
        val step2 = manager.orchestrationSteps.value.find { it.id == "call_2" }
        assertEquals("3 sections from \"Doxycycline\"", step2?.detail)
    }
}
