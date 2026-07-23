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
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.toolCalls.size)
        assertEquals("getTopicSectionText", ctx.toolCalls[0].toolName)
        assertTrue(ctx.toolCalls[0].success)
        assertEquals("content", ctx.toolCalls[0].result)
    }

    @Test
    fun `detects logical failure in tool result`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "Topic not found", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertFalse(ctx.toolCalls[0].success)
    }

    @Test
    fun `detects section not found`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "Section not found.", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertFalse(ctx.toolCalls[0].success)
    }

    @Test
    fun `detects topic not found with prefix`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "Topic not found: 123", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertFalse(ctx.toolCalls[0].success)
    }

    @Test
    fun `physical failure stays failed`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "content", false)

        val ctx = accumulator.buildTurnContext("answer")
        assertFalse(ctx.toolCalls[0].success)
    }

    // ══════════════════════════════════════════════════════════════════
    // Section tracking
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `tracks fetched sections from getTopicSectionText`() {
        accumulator.onToolCallStarting("call-1", """{"topicId":"1","sectionId":"H1","sectionTitle":"Dosing"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(1, ctx.fetchedSections.size)
        assertEquals("H1", ctx.fetchedSections[0].sectionId)
        assertEquals("Dosing", ctx.fetchedSections[0].sectionTitle)
    }

    @Test
    fun `does not track sections for failed calls`() {
        accumulator.onToolCallStarting("call-1", """{"sectionId":"H1"}""")
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "Section not found.", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(0, ctx.fetchedSections.size)
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
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertTrue(ctx.graphicIds.isEmpty())
    }

    // ══════════════════════════════════════════════════════════════════
    // Citation parsing
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `parses basic citation`() {
        val answer = "Topic: Drug X, Section: Dosing (ID: H1)"
        val ctx = accumulator.buildTurnContext(answer)
        assertEquals(1, ctx.citations.size)
        assertEquals("Drug X", ctx.citations[0].topicTitle)
        assertEquals("Dosing", ctx.citations[0].sectionTitle)
        assertEquals("H1", ctx.citations[0].sectionId)
    }

    @Test
    fun `parses citation without ID`() {
        val answer = "Topic: Drug X, Section: Dosing"
        val ctx = accumulator.buildTurnContext(answer)
        assertEquals(1, ctx.citations.size)
        assertEquals("unknown", ctx.citations[0].sectionId)
    }

    @Test
    fun `parses bullet-prefixed citations`() {
        val answer = "- Topic: Drug X, Section: Dosing (ID: H1)\n* Topic: Drug Y, Section: Safety (ID: H2)"
        val ctx = accumulator.buildTurnContext(answer)
        assertEquals(2, ctx.citations.size)
    }

    @Test
    fun `parses numbered list citations`() {
        val answer = "1. Topic: Drug X, Section: Dosing (ID: H1)"
        val ctx = accumulator.buildTurnContext(answer)
        assertEquals(1, ctx.citations.size)
    }

    @Test
    fun `parses bold-formatted citations`() {
        val answer = "**Topic:** Drug X, **Section:** Dosing (ID: H1)"
        val ctx = accumulator.buildTurnContext(answer)
        assertEquals(1, ctx.citations.size)
        assertEquals("Drug X", ctx.citations[0].topicTitle)
        assertEquals("Dosing", ctx.citations[0].sectionTitle)
    }

    @Test
    fun `parses mixed format citations`() {
        val answer = listOf(
            "- Topic: Apixaban Dosing, Section: Renal (ID: H5)",
            "* Topic: Apixaban Monitoring, Section: Labs (ID: H8)",
            "1. Topic: Asthma Overview, Section: Treatment (ID: H3)",
            "**Topic:** Gout Management, Section: Acute (ID: H12)",
        ).joinToString("\n")
        val ctx = accumulator.buildTurnContext(answer)
        assertEquals(4, ctx.citations.size)
        val ids = ctx.citations.map { it.sectionId }.toSet()
        assertEquals(setOf("H5", "H8", "H3", "H12"), ids)
    }

    @Test
    fun `strips bold markers from citation titles`() {
        val answer = "**Topic:** Drug X, **Section:** Dosing (ID: H1)"
        val ctx = accumulator.buildTurnContext(answer)
        assertEquals("Drug X", ctx.citations[0].topicTitle)
        assertEquals("Dosing", ctx.citations[0].sectionTitle)
    }

    @Test
    fun `returns empty citations for plain answer`() {
        val ctx = accumulator.buildTurnContext("This is a plain answer.")
        assertTrue(ctx.citations.isEmpty())
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
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "content", true)
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
        accumulator.onToolCallCompleted("call-1", "getTopicSectionText", "content", true)

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
    // Tool results
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `accumulates tool results in order`() {
        accumulator.onToolCallStarting("call-1", "{}")
        accumulator.onToolCallCompleted("call-1", "searchTopics", "results1", true)
        accumulator.onToolCallStarting("call-2", "{}")
        accumulator.onToolCallCompleted("call-2", "getTopicOutline", "outline", true)
        accumulator.onToolCallStarting("call-3", "{}")
        accumulator.onToolCallCompleted("call-3", "getTopicSectionText", "content", true)

        val ctx = accumulator.buildTurnContext("answer")
        assertEquals(3, ctx.toolResults.size)
        assertEquals("results1", ctx.toolResults[0])
        assertEquals("outline", ctx.toolResults[1])
        assertEquals("content", ctx.toolResults[2])
    }
}
