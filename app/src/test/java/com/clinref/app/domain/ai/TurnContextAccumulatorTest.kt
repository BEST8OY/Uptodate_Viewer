package com.clinref.app.domain.ai

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
    fun `stores outline sections from getTopicOutline`() {
        val outlineResult = """{"topicId":"128998","title":"Atrial Fibrillation","sections":[{"id":"H1","title":"Dosing"},{"id":"H2","title":"Monitoring"}]}"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"128998"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOutline", outlineResult, true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals("Atrial Fibrillation", ctx.fetchedSections.firstOrNull()?.topicTitle ?: "")
    }

    @Test
    fun `batch sections use stored outline titles`() {
        // First call getTopicOutline to store titles
        val outlineResult = """{"topicId":"1","title":"Drug X","sections":[{"id":"H1","title":"Dosing"},{"id":"H2","title":"Safety"}]}"""
        accumulator.onToolCallStarting("call-1", """{"topicId":"1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicOutline", outlineResult, true)

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
        accumulator.onToolCallCompleted("call-1", "getTopicOutline", outlineResult, true)

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
        accumulator.onToolCallCompleted("call-1", "getTopicOutline", outlineResult, true)

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
        accumulator.onToolCallCompleted("call-1", "getTopicOutline", outlineResult, true)

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
        accumulator.onToolCallCompleted("call-2", "getTopicOutline", "outline", true)
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
}
