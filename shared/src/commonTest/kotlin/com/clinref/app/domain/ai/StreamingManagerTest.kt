package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingManagerTest {

    private lateinit var manager: StreamingManager

    @Before
    fun setUp() {
        manager = StreamingManager(flushIntervalMs = 0L)
    }

    @Test
    fun `routes native reasoning deltas to reasoningText StateFlow`() {
        manager.onStreamingReasoningDelta("Reasoning step 1: check allergies.")
        manager.onStreamingReasoningDelta(" Step 2: verify CrCl.")
        manager.onStreamingTextDelta("The recommended antibiotic is Ceftriaxone 1 g IV daily.")

        assertEquals("Reasoning step 1: check allergies. Step 2: verify CrCl.", manager.uiState.value.reasoningText)
        assertEquals("The recommended antibiotic is Ceftriaxone 1 g IV daily.", manager.uiState.value.streamingText)
        assertEquals("The recommended antibiotic is Ceftriaxone 1 g IV daily.", manager.streamingText.value)
    }

    @Test
    fun `demuxes inline thought tags across fragmented stream chunks`() {
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
            manager.uiState.value.reasoningText
        )
        assertEquals("Amoxicillin 250 mg every 12 hours.", manager.uiState.value.streamingText)
    }

    @Test
    fun `preserves clinical mathematical comparisons in streaming text`() {
        manager.onStreamingTextDelta("Hemoglobin < 7 g/dL or platelet count < 50,000/mcL.")
        manager.onStreamingEnd()

        assertEquals("Hemoglobin < 7 g/dL or platelet count < 50,000/mcL.", manager.uiState.value.streamingText)
        assertEquals("", manager.uiState.value.reasoningText)
    }

    @Test
    fun `clears both streaming and reasoning buffers on tool call start and reset`() {
        manager.onStreamingReasoningDelta("Intermediate thinking...")
        manager.onStreamingTextDelta("Partial answer...")

        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"100"}""")
        assertEquals("", manager.uiState.value.streamingText)
        assertEquals("", manager.uiState.value.reasoningText)

        manager.onStreamingTextDelta("Fresh clinical recommendation.")
        manager.reset()
        assertEquals("", manager.uiState.value.streamingText)
        assertEquals("", manager.uiState.value.reasoningText)
    }

    @Test
    fun `manages synthesis step lifecycle in orchestration indicator`() {
        manager.onToolCallStarting("searchTopics", """{"query":"common cold"}""", "call_1")
        assertEquals(StreamingManager.StepStatus.IN_PROGRESS, manager.uiState.value.steps.first().status)

        // Starting synthesis completes previous in-progress steps and adds step_synthesis
        manager.onSynthesisStarting()

        val steps = manager.uiState.value.steps
        assertEquals(2, steps.size)
        assertEquals(StreamingManager.StepStatus.COMPLETED, steps[0].status)
        assertEquals("step_synthesis", steps[1].id)
        assertEquals("Synthesize Clinical Guidance", steps[1].title)
        assertEquals("Drafting evidence-grounded guidance", steps[1].detail)
        assertEquals(StreamingManager.StepStatus.IN_PROGRESS, steps[1].status)
        assertEquals("Synthesizing point-of-care guidance…", manager.uiState.value.statusText)

        // Waiting for LLM during synthesis preserves synthesis status text
        manager.onWaitingForLlm()
        assertEquals("Synthesizing point-of-care guidance…", manager.uiState.value.statusText)

        // Completion transitions synthesis step to COMPLETED
        manager.onCompleted("Final clinical recommendations.", emptyList(), emptyList())
        val completedSteps = manager.uiState.value.steps
        assertEquals(StreamingManager.StepStatus.COMPLETED, completedSteps[1].status)
        assertEquals("Complete", manager.uiState.value.statusText)
    }

    @Test
    fun `resolves raw topic IDs to titles from search results cache`() {
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

        val overviewStep = manager.uiState.value.steps.find { it.id == "call_overview" }
        assertEquals(
            "The common cold in adults: Treatment and prevention",
            overviewStep?.detail
        )
    }

    @Test
    fun `does not fall back to raw topic ID when title is unknown`() {
        manager.onToolCallStarting("getTopicOverview", """{"topicId":"99999"}""", "call_unknown")
        val step = manager.uiState.value.steps.find { it.id == "call_unknown" }
        assertNull(step?.detail)
    }

    @Test
    fun `correlates tool call completions accurately by callId`() {
        manager.onToolCallStarting("getTopicOverview", """{"topicId":"16629"}""", "call_1")
        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"16629"}""", "call_2")

        val stepsInitial = manager.uiState.value.steps
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

        val stepsAfterFirst = manager.uiState.value.steps
        assertEquals(StreamingManager.StepStatus.COMPLETED, stepsAfterFirst.find { it.id == "call_1" }?.status)
        assertEquals(StreamingManager.StepStatus.IN_PROGRESS, stepsAfterFirst.find { it.id == "call_2" }?.status)
    }

    @Test
    fun `onCompleted preserves streamingText while onError and reset clear it`() {
        manager.onStreamingTextDelta("Partial recommendation")
        assertEquals("Partial recommendation", manager.uiState.value.streamingText)

        manager.onCompleted("Final clinical guidance", emptyList(), emptyList())
        assertEquals("Final clinical guidance", manager.uiState.value.streamingText)

        manager.onError("Network failure")
        assertEquals("", manager.uiState.value.streamingText)

        manager.onStreamingTextDelta("New partial")
        assertEquals("New partial", manager.uiState.value.streamingText)

        manager.reset()
        assertEquals("", manager.uiState.value.streamingText)
    }

    @Test
    fun `onSynthesisStarting clears streaming and reasoning buffers for clean synthesis or revision pass`() {
        manager.onStreamingReasoningDelta("Pre-synthesis reasoning")
        manager.onStreamingTextDelta("Draft text with unverified quantity")
        assertEquals("Draft text with unverified quantity", manager.uiState.value.streamingText)
        assertEquals("Pre-synthesis reasoning", manager.uiState.value.reasoningText)

        manager.onSynthesisStarting()

        assertEquals("streamingText must be cleared on synthesis start/restart", "", manager.uiState.value.streamingText)
        assertEquals("reasoningText must be cleared on synthesis start/restart", "", manager.uiState.value.reasoningText)
        assertFalse(manager.uiState.value.isReasoning)

        // Verifying revision pass starts clean
        manager.onStreamingTextDelta("Revised text without quantities")
        assertEquals("Revised text without quantities", manager.uiState.value.streamingText)
    }

    @Test
    fun `transitions orchestration phases through evidence retrieval, synthesis, and completion`() {
        assertEquals(StreamingManager.OrchestrationPhase.IDLE, manager.uiState.value.phase)

        // Stage 1: Evidence Retrieval starts on tool call
        manager.onToolCallStarting("searchTopics", """{"query":"pneumonia"}""", "call_1")
        assertEquals(StreamingManager.OrchestrationPhase.EVIDENCE_RETRIEVAL, manager.uiState.value.phase)

        // Stage 2: Synthesis starts
        manager.onSynthesisStarting()
        assertEquals(StreamingManager.OrchestrationPhase.SYNTHESIS, manager.uiState.value.phase)

        // Completed
        manager.onCompleted("Final answer", emptyList(), emptyList())
        assertEquals(StreamingManager.OrchestrationPhase.COMPLETED, manager.uiState.value.phase)

        // Reset returns to IDLE
        manager.reset()
        assertEquals(StreamingManager.OrchestrationPhase.IDLE, manager.uiState.value.phase)
    }

    @Test
    fun `corrects 0 sections detail and avoids getting stuck in fetching evidence state`() {
        // Topic setup
        val searchJson = """{"query":"moxifloxacin","results":[{"id":"101","title":"Moxifloxacin: Drug information"}],"message":""}"""
        manager.onToolCallStarting("searchTopics", """{"query":"moxifloxacin"}""", "call_search")
        manager.onToolCallCompleted("searchTopics", searchJson, "call_search")

        // Start fetching sections
        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"101","sectionIds":["nonexistent"]}""", "call_sections")
        val initialStep = manager.uiState.value.steps.find { it.id == "call_sections" }
        assertEquals("Moxifloxacin: Drug information", initialStep?.detail)

        // Complete with 0 sections matched
        val emptySectionsJson = """{"topicId":"101","topicTitle":"Moxifloxacin: Drug information","sectionTitles":{},"markdown":""}"""
        manager.onToolCallCompleted("getTopicSectionsText", emptySectionsJson, "call_sections")

        val completedStep = manager.uiState.value.steps.find { it.id == "call_sections" }
        assertEquals(StreamingManager.StepStatus.COMPLETED, completedStep?.status)
        assertEquals("No sections matched in Moxifloxacin: Drug information", completedStep?.detail)
    }

    @Test
    fun `tracks accumulated section count and singularizes section labels`() {
        assertEquals(0, manager.uiState.value.sectionsReadCount)

        // 1 section response
        val oneSectionJson = """{"topicId":"101","topicTitle":"Amoxicillin","sectionTitles":{"s1":"Dosing"},"markdown":"Text"}"""
        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"101"}""", "call_1")
        manager.onToolCallCompleted("getTopicSectionsText", oneSectionJson, "call_1")

        assertEquals(1, manager.uiState.value.sectionsReadCount)
        val step1 = manager.uiState.value.steps.find { it.id == "call_1" }
        assertEquals("1 section from Amoxicillin", step1?.detail)

        // 3 sections response
        val threeSectionsJson = """{"topicId":"102","topicTitle":"Doxycycline","sectionTitles":{"s1":"Dosing","s2":"Adverse","s3":"Renal"},"markdown":"Text"}"""
        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"102"}""", "call_2")
        manager.onToolCallCompleted("getTopicSectionsText", threeSectionsJson, "call_2")

        assertEquals(4, manager.uiState.value.sectionsReadCount)
        val step2 = manager.uiState.value.steps.find { it.id == "call_2" }
        assertEquals("3 sections from Doxycycline", step2?.detail)
    }

    @Test
    fun `classifyError detects socket timeouts and client timeouts across exception causes`() {
        val rootTimeout = Exception("Connect timed out")
        val clientEx = Exception("[Error from client: GoogleLLMClient timeout]", rootTimeout)

        assertEquals(
            StreamingManager.ErrorType.TIMEOUT,
            manager.classifyError(clientEx.message.orEmpty(), clientEx)
        )

        // Traversal of nested cause
        val socketTimeout = Exception("java.net.SocketTimeoutException: Read timed out")
        val wrapper = RuntimeException("Model invocation failed", socketTimeout)
        assertEquals(
            StreamingManager.ErrorType.TIMEOUT,
            manager.classifyError("Generic failure", wrapper)
        )

        // Rate limit detection
        val rateLimitEx = RuntimeException("Resource has been exhausted (e.g. check quota)", null)
        assertEquals(
            StreamingManager.ErrorType.RATE_LIMIT,
            manager.classifyError(rateLimitEx.message.orEmpty(), rateLimitEx)
        )

        // Network detection
        val netEx = RuntimeException("java.net.UnknownHostException: Unable to resolve host", null)
        assertEquals(
            StreamingManager.ErrorType.NO_NETWORK,
            manager.classifyError(netEx.message.orEmpty(), netEx)
        )
    }

    @Test
    fun `getGraphicContent tool completion populates liveDiscoveredSources with isGraphic true`() {
        val graphicJson = """{"graphicId":"12345","title":"Table 1: Empiric Antibiotic Regimens","markdown":"| Drug | Dose |\n|---|---|\n| Ceftriaxone | 1 g IV |"}"""
        manager.onToolCallStarting("getGraphicContent", """{"graphicId":"12345"}""", "call_graphic")
        manager.onToolCallCompleted("getGraphicContent", graphicJson, "call_graphic")

        val sources = manager.uiState.value.discoveredSources
        assertEquals(1, sources.size)
        val source = sources.first()
        assertEquals("12345", source.topicId)
        assertEquals("Table 1: Empiric Antibiotic Regimens", source.title)
        assertTrue(source.isGraphic)
    }

    @Test
    fun `getTopicSectionsText tool completion populates liveDiscoveredSources with isGraphic false`() {
        val sectionsJson = """{"topicId":"101","topicTitle":"Pneumonia Treatment","sectionTitles":{"s1":"Overview"},"markdown":"Text"}"""
        manager.onToolCallStarting("getTopicSectionsText", """{"topicId":"101"}""", "call_sec")
        manager.onToolCallCompleted("getTopicSectionsText", sectionsJson, "call_sec")

        val sources = manager.uiState.value.discoveredSources
        assertEquals(1, sources.size)
        val source = sources.first()
        assertEquals("101", source.topicId)
        assertEquals("Pneumonia Treatment", source.title)
        assertFalse(source.isGraphic)
    }

    @Test
    fun `TopicRef serializes and deserializes with isGraphic backwards compatibility`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        // Older JSON without isGraphic field
        val legacyJson = """{"topicId":"101","sectionId":"s1","label":"Overview","topicTitle":"Pneumonia"}"""
        val decodedLegacy = json.decodeFromString<TopicRef>(legacyJson)
        assertFalse(decodedLegacy.isGraphic)

        // New JSON with isGraphic true
        val graphicJson = """{"topicId":"12345","sectionId":"","label":"Table 1","topicTitle":"Table 1","isGraphic":true}"""
        val decodedGraphic = json.decodeFromString<TopicRef>(graphicJson)
        assertTrue(decodedGraphic.isGraphic)
    }

    @Test
    fun `epoch turnId prevents late callbacks from cancelled turn polluting a new turn`() {
        // Start Turn 1
        manager.startTurn("conv-1", "turn-1")
        manager.onToolCallStarting("searchTopics", """{"query":"asthma"}""", "call_t1", turnId = "turn-1")
        assertEquals(1, manager.uiState.value.steps.size)

        // Start Turn 2
        manager.startTurn("conv-1", "turn-2")
        assertEquals(0, manager.uiState.value.steps.size)
        assertEquals("turn-2", manager.uiState.value.turnId)

        // Late callback from Turn 1 arrives
        manager.onStreamingTextDelta("Late tokens from turn-1", turnId = "turn-1")
        manager.onToolCallCompleted("searchTopics", "{}", "call_t1", turnId = "turn-1")

        // Turn 2 state remains completely clean
        assertEquals("", manager.uiState.value.streamingText)
        assertEquals(0, manager.uiState.value.steps.size)

        // Turn 2 callback succeeds
        manager.onStreamingTextDelta("Turn 2 tokens", turnId = "turn-2")
        assertEquals("Turn 2 tokens", manager.uiState.value.streamingText)

        // Null turnId callback is rejected when a turn is active
        manager.onStreamingTextDelta("Untracked tokens", turnId = null)
        assertEquals("Turn 2 tokens", manager.uiState.value.streamingText)
    }
}
