package com.clinref.app.domain.ai

import com.clinref.app.ui.chat.ResolvedGraphicRef
import com.clinref.app.ui.chat.ResolvedTopicRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TurnContextAccumulatorTest {

    private lateinit var accumulator: TurnContextAccumulator

    @Before
    fun setup() {
        accumulator = TurnContextAccumulator()
    }

    // ══════════════════════════════════════════════════════════════════
    // Tool call tracking
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `tracks tool call start and completion`() {
        accumulator.onToolCallStarting("call-1", """{"section_id":"H1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.toolCalls.size)
        assertEquals("getTopicSectionsText", ctx.toolCalls[0].toolName)
        assertTrue(ctx.toolCalls[0].success)
        assertEquals("content", ctx.toolCalls[0].result)
    }

    @Test
    fun `detects logical failure in tool result`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "Topic not found", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertFalse(ctx.toolCalls[0].success)
    }

    @Test
    fun `detects section not found`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "Section not found.", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertFalse(ctx.toolCalls[0].success)
    }

    @Test
    fun `detects topic not found with prefix`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "Topic not found: 123", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertFalse(ctx.toolCalls[0].success)
    }

    @Test
    fun `physical failure stays failed`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "content", false)

        val ctx = accumulator.buildTurnContext("answer")
        assertFalse(ctx.toolCalls[0].success)
    }

    // ══════════════════════════════════════════════════════════════════
    // Patient profile wiring
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `patient profile set on accumulator flows into built turn context`() {
        val profile = PatientProfile(
            age = "24",
            sex = "male",
            conditions = listOf("pharyngitis"),
            allergies = listOf("penicillin (anaphylaxis)")
        )
        accumulator.setPatientProfile(profile)
        val ctx = accumulator.buildTurnContext("Prescribe amoxicillin.")
        assertEquals(profile, ctx.patientProfile)
    }

    // ══════════════════════════════════════════════════════════════════
    // Section tracking
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `tracks fetched sections from getTopicSectionsText`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"1","sectionId":"H1","sectionTitle":"Dosing"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.fetchedSections.size)
        assertEquals("H1", ctx.fetchedSections[0].sectionId)
        assertEquals("Dosing", ctx.fetchedSections[0].sectionTitle)
    }

    @Test
    fun `does not track sections for failed calls`() {
        accumulator.onToolCallStarting("call-1", """{"sectionId":"H1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "Section not found.", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(0, ctx.fetchedSections.size)
    }

    @Test
    fun `stores outline sections from getTopicOverview`() {
        val outlineResult = """{"topicId":"128998","title":"Atrial Fibrillation","sections":[{"id":"H1","title":"Dosing"},{"id":"H2","title":"Monitoring"}]}"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"128998"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", outlineResult, true)

        accumulator.onToolCallStarting("call-2", """{"topicId":"128998","sectionId":"H1"}""")
        accumulator.onToolCallCompleted("call-2", "getTopicSectionsText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals("Atrial Fibrillation", ctx.fetchedSections.firstOrNull()?.topicTitle ?: "")
    }

    @Test
    fun `batch sections use stored outline titles`() {
        // First call getTopicOverview to store titles
        val outlineResult = """{"topicId":"1","title":"Drug X","sections":[{"id":"H1","title":"Dosing"},{"id":"H2","title":"Safety"}]}"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", outlineResult, true)

        // Then call batch sections (returns JSON with sectionTitles)
        val batchResult = """{"topicTitle":"Drug X","sectionTitles":{"H1":"Dosing","H2":"Safety"},"markdown":"content"}"""
        accumulator.onToolCallStarting("call-2", """{"topicId":"1","sectionIds":["H1","H2"]}""")
        accumulator.onToolCallCompleted("call-2", "getTopicSectionsText", batchResult, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(2, ctx.fetchedSections.size)
        assertEquals("Dosing", ctx.fetchedSections[0].sectionTitle)
        assertEquals("Safety", ctx.fetchedSections[1].sectionTitle)
        assertEquals("Drug X", ctx.fetchedSections[0].topicTitle)
    }

    @Test
    fun `batch sections parse JSON when no outline called`() {
        // Batch sections returns JSON with topicTitle and sectionTitles
        val batchResult = """{"topicTitle":"Atrial Fibrillation","sectionTitles":{"H1":"Dosing","H2":"Monitoring"},"markdown":"content"}"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"128998","sectionIds":["H1","H2"]}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", batchResult, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(2, ctx.fetchedSections.size)
        assertEquals("Dosing", ctx.fetchedSections[0].sectionTitle)
        assertEquals("Monitoring", ctx.fetchedSections[1].sectionTitle)
        assertEquals("Atrial Fibrillation", ctx.fetchedSections[0].topicTitle)
    }

    @Test
    fun `single section uses stored outline title`() {
        // Store outline
        val outlineResult = """{"topicId":"1","title":"Drug X","sections":[{"id":"H1","title":"Dosing"}]}"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", outlineResult, true)

        // Call single section
        accumulator.onToolCallStarting("call-2", """{"topicId":"1","sectionId":"H1","sectionTitle":""}""")
        accumulator.onToolCallCompleted("call-2", "getTopicSectionsText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals("Dosing", ctx.fetchedSections[0].sectionTitle)
    }

    // ══════════════════════════════════════════════════════════════════
    // Graphic ID tracking
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `tracks graphic IDs from getGraphicContent`() {
        accumulator.onToolCallStarting("call-1", """{"graphicId":"12345"}""")
        accumulator.onToolCallCompleted("call-1", "getGraphicContent", "table markdown", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(setOf("12345"), ctx.graphicIds)
    }

    @Test
    fun `does not track graphic IDs for other tools`() {
        accumulator.onToolCallStarting("call-1", """{"graphicId":"12345"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertTrue(ctx.graphicIds.isEmpty())
    }

    @Test
    fun `extracts graphic title from tool result`() {
        val graphicResult = "### Graphic Table: INR Monitoring Table\n\n| Dose | Rate |"
        accumulator.onToolCallStarting("call-1", """{"graphicId":"12345"}""")
        accumulator.onToolCallCompleted("call-1", "getGraphicContent", graphicResult, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.graphicRefs.size)
        assertEquals("INR Monitoring Table", ctx.graphicRefs[0].label)
    }

    // ══════════════════════════════════════════════════════════════════
    // Topic refs from submitted answer
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `auto-populates topic refs from fetched sections`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"1","sectionId":"H1","sectionTitle":"Dosing"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertTrue(ctx.topicRefs.isNotEmpty())
        assertEquals("H1", ctx.topicRefs[0].sectionId)
    }

    @Test
    fun `auto-populates graphic refs from graphic IDs`() {
        accumulator.onToolCallStarting("call-1", """{"graphicId":"12345"}""")
        accumulator.onToolCallCompleted("call-1", "getGraphicContent", "table", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.graphicRefs.size)
        assertEquals("12345", ctx.graphicRefs[0].graphicId)
    }

    // ══════════════════════════════════════════════════════════════════
    // User question
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `stores user question`() {
        accumulator.setUserQuestion("What is the dose?")
        val ctx = accumulator.buildTurnContext("answer")
        assertEquals("What is the dose?", ctx.userQuestion)
    }

    // ══════════════════════════════════════════════════════════════════
    // Reset
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `reset clears all state`() {
        accumulator.setUserQuestion("question")
        accumulator.onToolCallStarting("call-1", """{"sectionId":"H1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "content", true)
        accumulator.onToolCallStarting("call-2", """{"graphicId":"123"}""")
        accumulator.onToolCallCompleted("call-2", "getGraphicContent", "table", true)

        accumulator.reset()

        val ctx = accumulator.buildTurnContext("answer")
        assertTrue(ctx.toolCalls.isEmpty())
        assertTrue(ctx.toolResults.isEmpty())
        assertTrue(ctx.fetchedSections.isEmpty())
        assertTrue(ctx.graphicIds.isEmpty())
        assertEquals("", ctx.userQuestion)
    }

    // ══════════════════════════════════════════════════════════════════
    // Argument parsing
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `parses JSON arguments`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"123","sectionId":"H5"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals("123", ctx.toolCalls[0].arguments["topicId"])
        assertEquals("H5", ctx.toolCalls[0].arguments["sectionId"])
    }

    @Test
    fun `handles empty arguments`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "searchTopics", "results", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertTrue(ctx.toolCalls[0].arguments.isEmpty())
    }

    @Test
    fun `handles malformed arguments gracefully`() {
        accumulator.onToolCallStarting("call-1", "not json")
        accumulator.onToolCallCompleted("call-1", "searchTopics", "results", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertTrue(ctx.toolCalls[0].arguments.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════
    // Ref auto-population: empty section titles excluded
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `empty section titles are excluded from topic refs`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"1","sectionIds":["H1","H2","H3"]}""")
        accumulator.onToolCallCompleted(
            "call-1", "getTopicSectionsText",
            """{"topicTitle":"Drug X","sectionTitles":{"H1":"Dosing","H2":"","H3":""},"markdown":"content"}""",
            true
        )

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("H1", ctx.topicRefs[0].sectionId)
        assertEquals("Dosing", ctx.topicRefs[0].label)
    }

    // ══════════════════════════════════════════════════════════════════
    // Outline dash stripping
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `strips leading dashes from outline section titles`() {
        val outlineResult = """{"topicId":"1","title":"Drug X","sections":[{"id":"H1","title":"Dosing"},{"id":"H2","title":"-Antiplatelet therapy"}]}"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", outlineResult, true)

        accumulator.onToolCallStarting("call-2", """{"topicId":"1","sectionIds":["H1","H2"]}""")
        accumulator.onToolCallCompleted(
            "call-2", "getTopicSectionsText",
            """{"topicTitle":"Drug X","sectionTitles":{"H1":"Dosing","H2":"-Antiplatelet therapy"},"markdown":"content"}""",
            true
        )

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals("Dosing", ctx.fetchedSections[0].sectionTitle)
        assertEquals("Antiplatelet therapy", ctx.fetchedSections[1].sectionTitle)
    }

    @Test
    fun `strips leading dashes from outline parsed titles`() {
        val outlineResult = """{"topicId":"1","title":"Drug X","sections":[{"id":"H1","title":"Dosing"},{"id":"H2","title":"—Anticoagulation"}]}"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", outlineResult, true)

        accumulator.onToolCallStarting("call-2", """{"topicId":"1","sectionIds":["H2"]}""")
        accumulator.onToolCallCompleted(
            "call-2", "getTopicSectionsText",
            """{"topicTitle":"Drug X","sectionTitles":{"H2":""},"markdown":"content"}""",
            true
        )

        val ctx = accumulator.buildTurnContext("answer")
        // H2 title should come from outline (stored with dashes stripped)
        assertEquals("Anticoagulation", ctx.fetchedSections[0].sectionTitle)
    }

    // ══════════════════════════════════════════════════════════════════
    // Tool results
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `accumulates tool results in order`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "searchTopics", "results1", true)
        accumulator.onToolCallStarting("call-2", "{}")
        accumulator.onToolCallCompleted("call-2", "getTopicOverview", "outline", true)
        accumulator.onToolCallStarting("call-3", "{}")
        accumulator.onToolCallCompleted("call-3", "getTopicSectionsText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(3, ctx.toolResults.size)
        assertEquals("results1", ctx.toolResults[0])
        assertEquals("outline", ctx.toolResults[1])
        assertEquals("content", ctx.toolResults[2])
    }

    // ══════════════════════════════════════════════════════════════════
    // Deduplication
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `deduplicates identical tool calls`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"1","sectionId":"H1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "content", true)
        // Same tool+args — should be deduplicated
        accumulator.onToolCallStarting("call-2", """{"topicId":"1","sectionId":"H1"}""")
        accumulator.onToolCallCompleted("call-2", "getTopicSectionsText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        // Only one tool call should be recorded (deduplication)
        assertEquals(1, ctx.toolCalls.size)
    }

    @Test
    fun `does not deduplicate different args`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"1","sectionId":"H1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", "content1", true)
        accumulator.onToolCallStarting("call-2", """{"topicId":"1","sectionId":"H2"}""")
        accumulator.onToolCallCompleted("call-2", "getTopicSectionsText", "content2", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(2, ctx.toolCalls.size)
    }

    // ══════════════════════════════════════════════════════════════════
    // Blank toolCallId & Direct toolArgs
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `tracks tool call when toolCallId is blank using direct toolArgs`() {
        accumulator.onToolCallStarting("", "")
        accumulator.onToolCallCompleted(
            toolCallId = "",
            toolName = "getTopicSectionsText",
            result = """{"topicTitle":"TSH Measurement","sectionTitles":{"H1":"Reference Ranges"},"markdown":"content"}""",
            success = true,
            toolArgs = """{"topicId":"123","sectionIds":["H1"]}"""
        )

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.toolCalls.size)
        assertEquals(1, ctx.fetchedSections.size)
        assertEquals("TSH Measurement", ctx.fetchedSections[0].topicTitle)
        assertEquals("Reference Ranges", ctx.fetchedSections[0].sectionTitle)
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("TSH Measurement", ctx.topicRefs[0].topicTitle)
        assertEquals("Reference Ranges", ctx.topicRefs[0].label)
    }

    @Test
    fun `recovers sectionIds from response sectionTitles when args sectionIds empty`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"123"}""")
        accumulator.onToolCallCompleted(
            "call-1", "getTopicSectionsText",
            """{"topicTitle":"Thyroid Function","sectionTitles":{"H1":"Normal Levels","H2":"Abnormal Levels"},"markdown":"content"}""",
            true
        )

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(2, ctx.fetchedSections.size)
        assertEquals(2, ctx.topicRefs.size)
        assertEquals("Thyroid Function", ctx.topicRefs[0].topicTitle)
        assertEquals("Thyroid Function", ctx.topicRefs[1].topicTitle)
    }

    // ══════════════════════════════════════════════════════════════════
    // Search & Related Topics Caching
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `caches topic titles from searchTopics results`() {
        val searchResult = """{"query":"liver enzymes","results":[{"id":"3576","title":"Approach to the patient with abnormal liver tests"},{"id":"3573","title":"Overview of liver biochemical tests"}]}"""
        accumulator.onToolCallStarting("call-1", """{"query":"liver enzymes"}""")
        accumulator.onToolCallCompleted("call-1", "searchTopics", searchResult, true)

        // Then model fetches sections from topic 3576 where response topicTitle is empty or raw id
        accumulator.onToolCallStarting("call-2", """{"topicId":"3576","sectionIds":["H1"]}""")
        accumulator.onToolCallCompleted(
            "call-2", "getTopicSectionsText",
            """{"topicTitle":"","sectionTitles":{"H1":"Initial Evaluation"},"markdown":"content"}""",
            true
        )

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("Approach to the patient with abnormal liver tests", ctx.topicRefs[0].topicTitle)
        assertEquals("Initial Evaluation", ctx.topicRefs[0].label)
    }

    @Test
    fun `does not overwrite cached topic title with all-digit id`() {
        val searchResult = """{"query":"liver","results":[{"id":"3576","title":"Approach to the patient with abnormal liver tests"}]}"""
        accumulator.onToolCallStarting("call-1", """{"query":"liver"}""")
        accumulator.onToolCallCompleted("call-1", "searchTopics", searchResult, true)

        // Model calls getTopicOverview which returns topicId as title
        val outlineResult = """{"topicId":"3576","title":"3576","sections":[{"id":"H1","title":"Intro"}]}"""
        accumulator.onToolCallStarting("call-2", """{"topicId":"3576"}""")
        accumulator.onToolCallCompleted("call-2", "getTopicOverview", outlineResult, true)

        // Then fetches sections
        accumulator.onToolCallStarting("call-3", """{"topicId":"3576","sectionIds":["H1"]}""")
        accumulator.onToolCallCompleted(
            "call-3", "getTopicSectionsText",
            """{"topicTitle":"3576","sectionTitles":{"H1":"Intro"},"markdown":"content"}""",
            true
        )

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("Approach to the patient with abnormal liver tests", ctx.topicRefs[0].topicTitle)
    }

    @Test
    fun `handles escaped quoted JSON string from Koog serialization`() {
        // Double-encoded JSON string primitive as produced by Koog when serializing string tool returns
        val escapedOutlineResult = "\"{\\\"topicId\\\":\\\"7891\\\",\\\"title\\\":\\\"Laboratory assessment of thyroid function\\\",\\\"sections\\\":[{\\\"id\\\":\\\"sec_1\\\",\\\"title\\\":\\\"Serum TSH Tests\\\"}],\\\"graphics\\\":[{\\\"id\\\":\\\"67935\\\",\\\"title\\\":\\\"Assessment of thyroid function\\\"}]}\""
        accumulator.onToolCallStarting("call-1", """{"topicId":"7891"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", escapedOutlineResult, true)

        // Then model retrieves graphic table 67935
        val escapedGraphicResult = "\"### Graphic Table: Assessment of thyroid function\\n\\n| Test | Normal Range |\""
        accumulator.onToolCallStarting("call-2", """{"graphicId":"67935"}""")
        accumulator.onToolCallCompleted("call-2", "getGraphicContent", escapedGraphicResult, true)

        val ctx = accumulator.buildTurnContext("Normal TSH is 0.4 to 4.0 mIU/L.")

        // 1. Graphic ref should be populated with clean label
        assertEquals(1, ctx.graphicRefs.size)
        assertEquals("67935", ctx.graphicRefs[0].graphicId)
        assertEquals("Assessment of thyroid function", ctx.graphicRefs[0].label)

        // 2. Parent topic 7891 should be attributed with real title
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("7891", ctx.topicRefs[0].topicId)
        assertEquals("Laboratory assessment of thyroid function", ctx.topicRefs[0].topicTitle)
    }

    @Test
    fun `falls back to topicTitleResolver when tool outputs lack title`() {
        val resolverAccumulator = TurnContextAccumulator(
            topicTitleResolver = { tid ->
                if (tid == "7891") "Laboratory assessment of thyroid function" else null
            }
        )
        // Fetch section with only topicId, no outline and no topicTitle in result
        resolverAccumulator.onToolCallStarting("call-1", """{"topicId":"7891","sectionIds":["sec_1"]}""")
        resolverAccumulator.onToolCallCompleted(
            "call-1", "getTopicSectionsText",
            """{"topicTitle":"","sectionTitles":{"sec_1":"TSH measurement"},"markdown":"content"}""",
            true
        )

        val ctx = resolverAccumulator.buildTurnContext("TSH is 2.5 mIU/L.")
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("Laboratory assessment of thyroid function", ctx.topicRefs[0].topicTitle)
        assertEquals("TSH measurement", ctx.topicRefs[0].label)
    }

    @Test
    fun `falls back to sectionTitleResolver when section title is missing`() {
        val resolverAccumulator = TurnContextAccumulator(
            topicTitleResolver = { tid -> "Asthma Management" },
            sectionTitleResolver = { tid, sid ->
                if (tid == "1234" && sid == "sec_inhalers") "Inhaled Corticosteroids" else null
            }
        )
        // Outline doesn't have sec_inhalers title, tool result doesn't have it
        resolverAccumulator.onToolCallStarting("call-1", """{"topicId":"1234","sectionIds":["sec_inhalers"]}""")
        resolverAccumulator.onToolCallCompleted(
            "call-1", "getTopicSectionsText",
            """{"topicTitle":"Asthma Management","sectionTitles":{},"markdown":"content"}""",
            true
        )

        val ctx = resolverAccumulator.buildTurnContext("Use ICS daily.")
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("Inhaled Corticosteroids", ctx.topicRefs[0].label)
        assertEquals("Asthma Management", ctx.topicRefs[0].topicTitle)
    }

    @Test
    fun `supports snake_case tool name dispatching`() {
        accumulator.onToolCallStarting("call-1", """{"query":"gout"}""")
        accumulator.onToolCallCompleted(
            "call-1", "search_topics",
            """{"results":[{"id":"5678","title":"Treatment of acute gout"}]}""",
            true
        )

        accumulator.onToolCallStarting("call-2", """{"topic_id":"5678"}""")
        accumulator.onToolCallCompleted(
            "call-2", "get_topic_overview",
            """{"topicId":"5678","title":"Treatment of acute gout","sections":[{"id":"sec_colchicine","title":"Colchicine dosing"}]}""",
            true
        )

        accumulator.onToolCallStarting("call-3", """{"topic_id":"5678","section_ids":["sec_colchicine"]}""")
        accumulator.onToolCallCompleted(
            "call-3", "get_topic_sections_text",
            """{"topicTitle":"Treatment of acute gout","sectionTitles":{"sec_colchicine":"Colchicine dosing"},"markdown":"Colchicine dose is 1.2 mg."}""",
            true
        )

        val ctx = accumulator.buildTurnContext("Colchicine dose is 1.2 mg.")
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("Treatment of acute gout", ctx.topicRefs[0].topicTitle)
        assertEquals("Colchicine dosing", ctx.topicRefs[0].label)
    }

    @Test
    fun `AiJsonUtils normalizes tool names and arguments to canonical forms`() {
        assertEquals("searchTopics", AiJsonUtils.normalizeToolName("search_topics"))
        assertEquals("getTopicSectionsText", AiJsonUtils.normalizeToolName("get_topic_sections_text"))
        assertEquals("getGraphicContent", AiJsonUtils.normalizeToolName("get_graphic_content"))
        assertEquals("customTool", AiJsonUtils.normalizeToolName("customTool"))

        val rawArgs = mapOf(
            "topic_id" to "123",
            "section_ids" to "[\"s1\"]",
            "graphic_id" to "g1"
        )
        val normalized = AiJsonUtils.normalizeArgs(rawArgs)
        assertEquals("123", normalized["topicId"])
        assertEquals("[\"s1\"]", normalized["sectionIds"])
        assertEquals("g1", normalized["graphicId"])
    }

    @Test
    fun `AiJsonUtils cleanHtmlText strips HTML tags and decodes HTML entities`() {
        val raw = "Treatment of uncomplicated gonorrhea (<i>Neisseria gonorrhoeae&nbsp;</i>infection) in adults and adolescents"
        val expected = "Treatment of uncomplicated gonorrhea (Neisseria gonorrhoeae infection) in adults and adolescents"
        assertEquals(expected, AiJsonUtils.cleanHtmlText(raw))

        val entities = "Cavitary &amp; non-cavitary &quot;TB&#39;s&quot; &lt;100 &gt;50&nbsp;&#160;cases"
        assertEquals("Cavitary & non-cavitary \"TB's\" <100 >50 cases", AiJsonUtils.cleanHtmlText(entities))
        assertEquals("", AiJsonUtils.cleanHtmlText("   "))
    }

    @Test
    fun `ClinicalSource sanitizes HTML tags and entities in topic and section titles`() {
        val topicRefs = listOf(
            TopicRef("100", "secA", "<b>Diagnosis&nbsp;&amp;&nbsp;Workup</b>", "Gonorrhea (<i>Neisseria&nbsp;</i>Infection)"),
            TopicRef("100", "secB", "Treatment", "Gonorrhea (<i>Neisseria&nbsp;</i>Infection)")
        )
        val articles = ClinicalSource.fromTopicRefs(topicRefs)
        assertEquals(1, articles.size)
        assertEquals("Gonorrhea (Neisseria Infection)", articles[0].topicTitle)
        assertEquals("Diagnosis & Workup", articles[0].sections[0].sectionTitle)
    }

    @Test
    fun `ClinicalSource converts to and from TopicRef and Resolved refs`() {
        val topicRefs = listOf(
            TopicRef("100", "secA", "Section A", "Topic 100 Title"),
            TopicRef("100", "secB", "Section B", "Topic 100 Title"),
            TopicRef("200", "secC", "Section C", "Topic 200 Title")
        )
        val articles = ClinicalSource.fromTopicRefs(topicRefs)
        assertEquals(2, articles.size)
        assertEquals("100", articles[0].topicId)
        assertEquals("Topic 100 Title", articles[0].topicTitle)
        assertEquals(2, articles[0].sections.size)
        assertEquals("secA", articles[0].sections[0].sectionId)
        assertEquals("Section A", articles[0].sections[0].sectionTitle)

        val backToTopicRefs = articles[0].toTopicRefs()
        assertEquals(2, backToTopicRefs.size)
        assertEquals("100", backToTopicRefs[0].topicId)
        assertEquals("secA", backToTopicRefs[0].sectionId)

        val resolvedTopicRefs = articles[0].toResolvedTopicRefs()
        assertEquals(2, resolvedTopicRefs.size)
        assertEquals("Section A", resolvedTopicRefs[0].title)
        assertEquals("secA", resolvedTopicRefs[0].sectionId)
        assertEquals("Topic 100 Title", resolvedTopicRefs[0].topicTitle)

        val graphicRefs = listOf(
            GraphicRef("g123", "Table 1 Dosing", "100")
        )
        val tables = ClinicalSource.fromGraphicRefs(graphicRefs, mapOf("100" to "Topic 100 Title"))
        assertEquals(1, tables.size)
        assertEquals("g123", tables[0].graphicId)
        assertEquals("Table 1 Dosing", tables[0].tableTitle)
        assertEquals("100", tables[0].parentTopicId)
        assertEquals("Topic 100 Title", tables[0].parentTopicTitle)

        val backToGraphicRef = tables[0].toGraphicRef()
        assertEquals("g123", backToGraphicRef.graphicId)
        assertEquals("Table 1 Dosing", backToGraphicRef.label)

        val (fromResolvedArticles, fromResolvedTables) = ClinicalSource.fromResolved(
            topicRefs = resolvedTopicRefs,
            graphicRefs = listOf(ResolvedGraphicRef("g123", "Table 1 Dosing", "100", "Topic 100 Title"))
        )
        assertEquals(1, fromResolvedArticles.size)
        assertEquals(1, fromResolvedTables.size)
        assertEquals("Topic 100 Title", fromResolvedTables[0].parentTopicTitle)
    }

    @Test
    fun `reads topicId directly from getTopicSectionsText response`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":""}""")
        accumulator.onToolCallCompleted(
            "call-1", "getTopicSectionsText",
            """{"topicId":"9876","topicTitle":"Cardiac Arrest","sectionTitles":{"H1":"Epinephrine"},"markdown":"content"}""",
            true
        )

        val ctx = accumulator.buildTurnContext("Give epinephrine 1 mg.")
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("9876", ctx.topicRefs[0].topicId)
        assertEquals("Cardiac Arrest", ctx.topicRefs[0].topicTitle)
    }

    @Test
    fun `normalizes FULL sectionId to empty string in auto topic refs and ClinicalSource`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"148929","sectionIds":["FULL"]}""")
        accumulator.onToolCallCompleted(
            "call-1", "getTopicSectionsText",
            """{"topicId":"148929","topicTitle":"AHA PREVENT Calculator","sectionTitles":{"FULL":"AHA PREVENT Calculator"},"markdown":"=== Calculator: AHA PREVENT Calculator ==="}""",
            true
        )

        val ctx = accumulator.buildTurnContext("Calculate 10-year risk.")
        assertEquals(1, ctx.topicRefs.size)
        assertEquals("148929", ctx.topicRefs[0].topicId)
        assertEquals("", ctx.topicRefs[0].sectionId)

        val articles = ClinicalSource.fromTopicRefs(ctx.topicRefs)
        assertEquals(1, articles.size)
        assertEquals("148929", articles[0].topicId)
        assertTrue(articles[0].sections.isEmpty())
    }

    @Test
    fun `tool Args classes deserialize snake_case keys via JsonNames`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        val searchArgs = json.decodeFromString<com.clinref.app.data.tools.SearchTopicsTool.Args>("""{"search_query":"asthma"}""")
        assertEquals("asthma", searchArgs.query)

        val overviewArgs = json.decodeFromString<com.clinref.app.data.tools.GetTopicOverviewTool.Args>("""{"topic_id":"123"}""")
        assertEquals("123", overviewArgs.topicId)

        val sectionsArgs = json.decodeFromString<com.clinref.app.data.tools.GetTopicSectionsTextTool.Args>("""{"topic_id":"123","section_ids":["H1","H2"]}""")
        assertEquals("123", sectionsArgs.topicId)
        assertEquals(listOf("H1", "H2"), sectionsArgs.sectionIds)

        val graphicArgs = json.decodeFromString<com.clinref.app.data.tools.GetGraphicContentTool.Args>("""{"graphic_id":"456"}""")
        assertEquals("456", graphicArgs.graphicId)
    }

    @Test
    fun `onToolCallCompleted parses getTopicOverview outline and getTopicSectionsText content`() {
        val overviewJson = """
            {
                "topicId": "100",
                "title": "Atrial Fibrillation",
                "topicType": "article",
                "sections": [
                    {"id": "H1", "title": "Overview", "level": 1},
                    {"id": "H_SR", "title": "Summary & Recommendations", "level": 1}
                ],
                "graphics": [
                    {"id": "g99", "title": "DOAC Dosing Table", "isTable": true}
                ],
                "relatedTopics": []
            }
        """.trimIndent()

        accumulator.onToolCallStarting("call_overview_1", """{"topic_id": "100"}""")
        accumulator.onToolCallCompleted(
            toolCallId = "call_overview_1",
            toolName = "getTopicOverview",
            result = overviewJson,
            success = true
        )

        val sectionsJson = """
            {
                "topicId": "100",
                "topicTitle": "Atrial Fibrillation",
                "sectionTitles": {
                    "H_SR": "Summary & Recommendations"
                },
                "markdown": "=== Section: H_SR ===\nGive apixaban 5 mg twice daily."
            }
        """.trimIndent()

        accumulator.onToolCallStarting("call_sec_1", """{"topic_id": "100", "section_ids": ["H_SR"]}""")
        accumulator.onToolCallCompleted(
            toolCallId = "call_sec_1",
            toolName = "getTopicSectionsText",
            result = sectionsJson,
            success = true
        )

        val tc = accumulator.buildTurnContext("Apixaban 5 mg twice daily is recommended.")
        val fetchedSec = tc.fetchedSections.firstOrNull { it.sectionId == "H_SR" }
        assertNotNull(fetchedSec)
        assertEquals("Atrial Fibrillation", fetchedSec?.topicTitle)
        assertEquals("Summary & Recommendations", fetchedSec?.sectionTitle)
        assertEquals(1, tc.topicRefs.size)
        assertEquals("100", tc.topicRefs[0].topicId)
        assertEquals("H_SR", tc.topicRefs[0].sectionId)
        assertEquals("Summary & Recommendations", tc.topicRefs[0].label)
    }

    // ══════════════════════════════════════════════════════════════════
    // Overview + section accumulation through the real tool-call path
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `overview call records its outline but no evidence until a section or table is fetched`() {
        val overview = """{"topicId":"1","title":"Drug X","sections":[{"id":"H1","title":"Dosing"}],
            |"graphics":[{"id":"G1","title":"Renal dosing table","isTable":true}]}""".trimMargin()

        accumulator.onToolCallStarting("call-1", """{"topicId":"1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", overview, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals("1", ctx.toolCalls.single().arguments["topicId"])
        assertTrue("An outline is not evidence", ctx.fetchedSections.isEmpty())
        assertTrue("An offered table is not evidence until fetched", ctx.graphicRefs.isEmpty())
        assertFalse(accumulator.hasEvidence)

        // Fetching that same table turns it into accumulated evidence attributed to topic 1.
        accumulator.onToolCallStarting("call-2", """{"graphicId":"G1"}""")
        accumulator.onToolCallCompleted(
            "call-2", "getGraphicContent",
            """{"graphicId":"G1","title":"Renal dosing table","markdown":"### Graphic Table: Renal dosing table\n\n| eGFR | Dose |"}""",
            true
        )

        val afterFetch = accumulator.buildTurnContext("answer")
        assertEquals(listOf("G1"), afterFetch.graphicRefs.map { it.graphicId })
        assertEquals("Renal dosing table", afterFetch.graphicRefs.single().label)
        assertEquals("1", afterFetch.graphicRefs.single().topicId)
        assertTrue(accumulator.hasEvidence)
    }

    @Test
    fun `section fetch is recorded once per tool and args pair`() {
        val args = """{"topicId":"1","sectionIds":["H1"]}"""
        val result = """{"topicId":"1","topicTitle":"Drug X","sectionTitles":{"H1":"Dosing"},"markdown":"Give 500 mg daily."}"""

        accumulator.onToolCallStarting("call-1", args)
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", result, true)
        accumulator.onToolCallStarting("call-2", args)
        accumulator.onToolCallCompleted("call-2", "getTopicSectionsText", result, true)
        accumulator.onToolCallStarting("call-3", """{"topicId":"1","sectionIds":["H2"]}""")
        accumulator.onToolCallCompleted("call-3", "getTopicSectionsText", result, true)

        val ctx = accumulator.buildTurnContext("Give 500 mg daily.")
        assertEquals("Identical tool+args records must dedup", 2, ctx.toolCalls.size)
        assertEquals("Dosing", ctx.fetchedSections.first().sectionTitle)
        assertEquals("Drug X", ctx.fetchedSections.first().topicTitle)
    }

    @Test
    fun `failed overview is recorded as a logical failure and yields no evidence`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"9999"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", """{"error":"Topic not found: 9999"}""", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.toolCalls.size)
        assertFalse(ctx.toolCalls[0].success)
        assertTrue(ctx.fetchedSections.isEmpty())
        assertFalse(accumulator.hasEvidence)
    }

    @Test
    fun `parses raw markdown graphic result and populates fetchedGraphics`() {
        val rawMarkdown = "### Graphic Table: Urgent Rate Control Table\n\n| Drug | Dose |\n|---|---|\n| Diltiazem | 0.25 mg/kg |"
        accumulator.onToolCallStarting("call-1", """{"graphicId":"141646"}""")
        accumulator.onToolCallCompleted("call-1", "getGraphicContent", rawMarkdown, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.fetchedGraphics.size)
        assertEquals("141646", ctx.fetchedGraphics[0].graphicId)
        assertEquals("Urgent Rate Control Table", ctx.fetchedGraphics[0].title)
        assertTrue(ctx.fetchedGraphics[0].markdown.contains("Diltiazem"))
        assertTrue(accumulator.hasEvidence)
    }

    @Test
    fun `invalid section ID does not duplicate full markdown across fetchedSections`() {
        val batchResult = """{
            "topicId": "101",
            "topicTitle": "Drug X",
            "sectionTitles": {"H1": "Dosing"},
            "markdown": "=== Section: H1 ===\nAdminister 500 mg orally."
        }"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"101","sectionIds":["H1","INVALID_ID"]}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", batchResult, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.fetchedSections.size)
        assertEquals("H1", ctx.fetchedSections[0].sectionId)
        assertEquals("Administer 500 mg orally.", ctx.fetchedSections[0].contentSnippet)
    }

    @Test
    fun `indexes sectionTitles keys present in response even if missing in args`() {
        val batchResult = """{
            "topicId": "101",
            "topicTitle": "Drug X",
            "sectionTitles": {"H1": "Dosing", "H2": "Renal Adjustment"},
            "markdown": "=== Section: H1 ===\nAdminister 500 mg.\n\n=== Section: H2 ===\nCrCl < 30: 250 mg."
        }"""
        // Args only passed H1, but tool resolved and returned H1 and H2
        accumulator.onToolCallStarting("call-1", """{"topicId":"101","sectionIds":["H1"]}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionsText", batchResult, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(2, ctx.fetchedSections.size)
        assertTrue(ctx.fetchedSections.any { it.sectionId == "H1" && it.contentSnippet.contains("500 mg") })
        assertTrue(ctx.fetchedSections.any { it.sectionId == "H2" && it.contentSnippet.contains("CrCl < 30") })
    }

    @Test
    fun `failed tool call does not block retry with identical arguments`() {
        // First attempt fails with error
        accumulator.onToolCallStarting("call-1", """{"topicId":"101"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", """{"error":"Transient database timeout"}""", true)

        // Second attempt with identical arguments succeeds
        val successResult = """{
            "topicId": "101",
            "title": "Drug X",
            "sections": [{"id":"H1","title":"Dosing"}],
            "graphics": []
        }"""
        accumulator.onToolCallStarting("call-2", """{"topicId":"101"}""")
        accumulator.onToolCallCompleted("call-2", "getTopicOverview", successResult, true)

        val ctx = accumulator.buildTurnContext("answer")
        // Both attempts recorded in toolCalls history (first failed, second succeeded)
        assertEquals(2, ctx.toolCalls.size)
        assertFalse(ctx.toolCalls[0].success)
        assertTrue(ctx.toolCalls[1].success)
        assertTrue(ctx.toolCalls[1].result.contains("Drug X"))
        assertTrue(accumulator.buildDiscoveredInventorySummary().contains("Drug X"))
    }

    @Test
    fun `buildDiscoveredInventorySummary retains deep outline sections up to 70 tables up to 25 and uninspected candidate topics`() {
        // Discovered candidate topics from search
        accumulator.onToolCallStarting("call-search", """{"query":"pneumonia"}""")
        val searchResult = """{
            "results": [
                {"id":"101","title":"Community-acquired pneumonia in adults"},
                {"id":"102","title":"Hospital-acquired pneumonia in adults"}
            ]
        }"""
        accumulator.onToolCallCompleted("call-search", "searchTopics", searchResult, true)

        // Inspected topic 101 with 60 sections (Treatment at section 50) and 25 table graphics
        val sectionsJson = (1..60).joinToString(",") { idx ->
            """{"id":"H$idx","title":"Section $idx${if (idx == 50) " Treatment" else ""}"}"""
        }
        val graphicsJson = (1..25).joinToString(",") { idx ->
            """{"id":"$idx","title":"Table $idx"}"""
        }
        val overviewResult = """{
            "topicId": "101",
            "title": "Community-acquired pneumonia in adults",
            "sections": [$sectionsJson],
            "graphics": [$graphicsJson]
        }"""
        accumulator.onToolCallStarting("call-overview", """{"topicId":"101"}""")
        accumulator.onToolCallCompleted("call-overview", "getTopicOverview", overviewResult, true)

        val summary = accumulator.buildDiscoveredInventorySummary()

        // Section 50 Treatment must not be truncated by take(70)
        assertTrue("Treatment section 50 must be preserved in inventory", summary.contains("H50 (Section 50 Treatment)"))
        // Table 25 must not be truncated by take(25)
        assertTrue("Table 25 must be preserved in inventory", summary.contains("[25] Table 25"))
        // Uninspected topic 102 must not be hidden
        assertTrue("Uninspected candidate topic 102 must be listed", summary.contains("Hospital-acquired pneumonia in adults"))
    }

    @Test
    fun `parses canonical and snake_case arguments and preserves outline isolation across topics`() {
        // Topic 100 overview with H100
        accumulator.onToolCallStarting("call-overview", """{"topic_id":"100"}""")
        accumulator.onToolCallCompleted("call-overview", "getTopicOverview", """{
            "topicId": "100",
            "title": "Topic 100",
            "sections": [{"id":"H100","title":"Topic 100 Section"}]
        }""", true)

        // Topic 200 fetched using snake_case parameters
        val batchResult = """{
            "topicId": "200",
            "topicTitle": "Topic 200",
            "sectionTitles": {"H200": "Topic 200 Section"},
            "markdown": "=== Section: H200 ===\nTopic 200 content."
        }"""
        accumulator.onToolCallStarting("call-fetch", """{"topic_id":"200","section_ids":["H200"]}""")
        accumulator.onToolCallCompleted("call-fetch", "getTopicSectionsText", batchResult, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.fetchedSections.size)
        assertEquals("200", ctx.fetchedSections[0].topicId)
        assertEquals("H200", ctx.fetchedSections[0].sectionId)
        assertEquals("Topic 200 Section", ctx.fetchedSections[0].sectionTitle)

        // Verify outline of Topic 200 did not inherit H100 from Topic 100
        val summary = accumulator.buildDiscoveredInventorySummary()
        assertTrue(summary.contains("Topic [200] 'Topic 200': H200 (Topic 200 Section)"))
        assertFalse(summary.contains("Topic [200] 'Topic 200': H100"))
    }

    @Test
    fun `table graphic provenance resolves topic title and suppresses duplicates and non-tables`() {
        val overviewResult = """{
            "topicId": "7027",
            "title": "Community-acquired pneumonia in adults",
            "sections": [{"id":"H1","title":"Treatment"}],
            "graphics": [
                {"id":"Graphic-118967","title":"Empiric Antibiotic Regimens","isTable":true},
                {"id":"Graphic-999999","title":"Chest Radiograph Figure","isTable":false}
            ]
        }"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"7027"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOverview", overviewResult, true)

        val graphicResult = """{
            "graphicId": "118967",
            "title": "Empiric Antibiotic Regimens",
            "markdown": "| Antibiotic | Dose |\n|---|---|\n| Amoxicillin | 1 g TID |"
        }"""
        accumulator.onToolCallStarting("call-2", """{"graphic_id":"118967"}""")
        accumulator.onToolCallCompleted("call-2", "getGraphicContent", graphicResult, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.fetchedGraphics.size)
        assertEquals("118967", ctx.fetchedGraphics[0].graphicId)
        assertEquals("7027", ctx.fetchedGraphics[0].topicId)
        assertEquals("Community-acquired pneumonia in adults", ctx.fetchedGraphics[0].topicTitle)

        val evidenceSummary = SystemPrompt.evidenceSummaryFrom(ctx.fetchedSections, ctx.fetchedGraphics)
        assertTrue("Evidence summary must display human-readable topic title in table provenance",
            evidenceSummary.contains("[Topic: Community-acquired pneumonia in adults]"))
        assertFalse("Evidence summary must not display raw numeric topic ID",
            evidenceSummary.contains("[Topic: 7027]"))

        val inventory = accumulator.buildDiscoveredInventorySummary()
        // Must contain clean ID 118967 once
        assertTrue(inventory.contains("[118967] Empiric Antibiotic Regimens"))
        // Must NOT contain duplicate Graphic-118967 or non-table 999999
        assertFalse(inventory.contains("[Graphic-118967]"))
        assertFalse(inventory.contains("Chest Radiograph Figure"))
    }
}

