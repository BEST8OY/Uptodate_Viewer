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
        assertEquals("Drafting evidence-grounded recommendations & BLUF", steps[1].detail)
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
}
