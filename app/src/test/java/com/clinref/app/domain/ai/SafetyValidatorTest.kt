package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyValidatorTest {

    private val validator = SafetyValidator()

    // ── Helpers ──────────────────────────────────────────────────────

    private fun ctx(
        toolCalls: List<SafetyValidator.ToolCallRecord> = emptyList(),
        answer: String = "",
        toolResults: List<String> = emptyList(),
        fetchedSections: List<SafetyValidator.FetchedSection> = emptyList(),
        graphicIds: Set<String> = emptySet(),
        userQuestion: String = "",
    ) = SafetyValidator.TurnContext(
        toolCalls = toolCalls,
        answer = answer,
        toolResults = toolResults,
        fetchedSections = fetchedSections,
        graphicIds = graphicIds,
        userQuestion = userQuestion,
    )

    private fun toolCall(
        name: String = "getTopicSectionsText",
        args: Map<String, String> = mapOf("sectionId" to "H1"),
        result: String = "content",
        success: Boolean = true,
    ) = SafetyValidator.ToolCallRecord(name, args, result, success)

    private fun section(
        topicId: String = "1",
        sectionId: String = "H1",
        title: String = "Section 1",
    ) = SafetyValidator.FetchedSection(topicId, "Topic Title", sectionId, title)

    // ══════════════════════════════════════════════════════════════════
    // Rule 0: Intent-aware tool call check
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 0 blocks when no tool calls and clinical answer`() {
        val result = validator.validate(ctx(answer = "The recommended dose is 5 mg twice daily."))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("Clinical recommendations require database verification"))
    }

    @Test
    fun `rule 0 passes with tool calls`() {
        val result = validator.validate(ctx(toolCalls = listOf(toolCall())))
        // May fail on later rules, but not rule 0
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Clinical recommendations require database verification"))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 1: Section content required
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 1 blocks when only search was performed`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "searchTopics")),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("section or table content"))
    }

    @Test
    fun `rule 1 passes for getTopicSectionsText`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getTopicSectionsText")),
            answer = "test",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        // Should not fail on rule 1
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("section or table content"))
        }
    }

    @Test
    fun `rule 1 passes for getGraphicContent`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getGraphicContent")),
            answer = "test",
            toolResults = listOf("table content"),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("section or table content"))
        }
    }

    @Test
    fun `rule 1 blocks for failed section fetch`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getTopicSectionsText", success = false, result = "Section not found.")),
        ))
        assertFalse(result.passed)
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 2: No invented clinical quantities
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 2 passes when quantities match tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 5 mg.",
            toolResults = listOf("The dose is 5 mg."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("clinical quantities"))
        }
    }

    @Test
    fun `rule 2 blocks when quantity not in tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 750 mg.",
            toolResults = listOf("The dose is 500 mg."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("750 mg"))
    }

    @Test
    fun `rule 2 allows quantities from user question`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 250 mg.",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
            userQuestion = "Is 250 mg safe?",
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("250 mg"))
        }
    }

    @Test
    fun `rule 2 does not flag structural numbers`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "1. Introduction\n2. Methods\nSee section 3.1.\nYear 2024.",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("clinical quantities"))
        }
    }

    @Test
    fun `rule 2 boundary check prevents partial matches`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The value is 12 mg.",
            toolResults = listOf("Value: 123 mg."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
    }

    @Test
    fun `rule 2 handles compound slashed units`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "Recommended rates are 5 u/x, 10 U/L, and 0.5 mcg/kg/min.",
            toolResults = listOf("Give 5 u/x IV, 10 U/L, and 0.5 mcg/kg/min."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    @Test
    fun `rule 2 normalizes thousand separator commas`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 1200 mg.",
            toolResults = listOf("The dose is 1,200 mg daily."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("Unverified clinical quantities"))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 3: No graphic interpretation
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 3 passes when no visual language`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The recommended treatment is aspirin.",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("visual details"))
        }
    }

    @Test
    fun `rule 3 passes when visual language is in tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The x-ray shows opacity.",
            toolResults = listOf("The x-ray shows opacity in the lower lobe."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("visual details"))
        }
    }

    @Test
    fun `rule 3 blocks when visual language not in tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The image shows a mass.",
            toolResults = listOf("Normal lung fields."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("visual details"))
    }

    // ══════════════════════════════════════════════════════════════════
    // Full validation integration
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `full validation passes on realistic successful turn`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(args = mapOf("sectionId" to "H1"))),
            answer = "The dose is 5 mg.",
            toolResults = listOf("The dose is 5 mg."),
            fetchedSections = listOf(section(sectionId = "H1", title = "Dosing")),
            topicRefs = listOf(SafetyValidator.TopicRef(topicId = "1", sectionId = "H1", label = "Dosing", topicTitle = "Topic Title"))
        ))
        assertTrue(result.passed)
        assertEquals(1, result.topicRefs.size)
    }

    @Test
    fun `full validation short-circuits on first failure`() {
        // No tool calls → fails rule 0 immediately
        val result = validator.validate(ctx(answer = "The recommended dose is 5 mg twice daily."))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("Clinical recommendations require database verification"))
    }
}