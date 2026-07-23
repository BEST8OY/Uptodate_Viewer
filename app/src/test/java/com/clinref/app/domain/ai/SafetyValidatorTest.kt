package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyValidatorTest {

    private val validator = SafetyValidator()

    // ── Helpers ──────────────────────────────────────────────────────

    private fun ctx(
        toolCalls: List<SafetyValidator.ToolCallRecord> = emptyList(),
        answer: String = "",
        citations: List<SafetyValidator.Citation> = emptyList(),
        toolResults: List<String> = emptyList(),
        fetchedSections: List<SafetyValidator.FetchedSection> = emptyList(),
        graphicIds: Set<String> = emptySet(),
        userQuestion: String = "",
    ) = SafetyValidator.TurnContext(
        toolCalls = toolCalls,
        answer = answer,
        citations = citations,
        toolResults = toolResults,
        fetchedSections = fetchedSections,
        graphicIds = graphicIds,
        userQuestion = userQuestion,
    )

    private fun toolCall(
        name: String = "getTopicSectionText",
        args: Map<String, String> = mapOf("section_id" to "H1"),
        result: String = "content",
        success: Boolean = true,
    ) = SafetyValidator.ToolCallRecord(name, args, result, success)

    private fun section(
        topicId: String = "1",
        sectionId: String = "H1",
        title: String = "Section 1",
    ) = SafetyValidator.FetchedSection(topicId, "", sectionId, title)

    private fun citation(
        topicTitle: String = "Topic",
        sectionTitle: String = "Section",
        sectionId: String = "H1",
    ) = SafetyValidator.Citation("", topicTitle, sectionId, sectionTitle)

    // ══════════════════════════════════════════════════════════════════
    // Rule 1: Tool calls required
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 1 blocks when no tool calls`() {
        val result = validator.validate(ctx())
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("No tool calls"))
    }

    @Test
    fun `rule 1 passes with tool calls`() {
        val result = validator.validate(ctx(toolCalls = listOf(toolCall())))
        // May fail on later rules, but not rule 1
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("No tool calls"))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 1b: Section content required
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 1b blocks when only search was performed`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "searchTopics")),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("section or table content"))
    }

    @Test
    fun `rule 1b passes for getTopicSectionText`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getTopicSectionText")),
            answer = "test",
            citations = listOf(citation()),
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        // Should not fail on rule 1b
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("section or table content"))
        }
    }

    @Test
    fun `rule 1b passes for getGraphicContent`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getGraphicContent")),
            answer = "test",
            citations = listOf(citation()),
            toolResults = listOf("table content"),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("section or table content"))
        }
    }

    @Test
    fun `rule 1b blocks for failed section fetch`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall(name = "getTopicSectionText", success = false, result = "Section not found.")),
        ))
        assertFalse(result.passed)
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 2: Citation consistency
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 2 passes when citation ID matches fetched section`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "test",
            citations = listOf(citation(sectionId = "H1")),
            toolResults = listOf("content"),
            fetchedSections = listOf(section(sectionId = "H1")),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("not retrieved"))
        }
    }

    @Test
    fun `rule 2 blocks when citation references unmatched section`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "test",
            citations = listOf(citation(sectionId = "Z99", sectionTitle = "Nonexistent")),
            toolResults = listOf("content"),
            fetchedSections = listOf(section(sectionId = "H1", title = "Real Section")),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("not retrieved"))
    }

    @Test
    fun `rule 2 allows unknown section ID with title match`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "test",
            citations = listOf(citation(sectionId = "unknown", sectionTitle = "Section 1")),
            toolResults = listOf("content"),
            fetchedSections = listOf(section(title = "Section 1")),
        ))
        // Should pass (with warning)
        assertTrue(result.warnings.any { it.contains("title fallback") })
    }

    @Test
    fun `rule 2 skips check when no fetched sections`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "test",
            citations = listOf(citation(sectionId = "Z99")),
            toolResults = listOf("content"),
            fetchedSections = emptyList(),
        ))
        // Should not fail on rule 2
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("not retrieved"))
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 3: No invented clinical quantities
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 3 passes when quantities match tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 5 mg.",
            citations = listOf(citation()),
            toolResults = listOf("The dose is 5 mg."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("clinical quantities"))
        }
    }

    @Test
    fun `rule 3 blocks when quantity not in tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 750 mg.",
            citations = listOf(citation()),
            toolResults = listOf("The dose is 500 mg."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("750 mg"))
    }

    @Test
    fun `rule 3 allows quantities from user question`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The dose is 250 mg.",
            citations = listOf(citation()),
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
            userQuestion = "Is 250 mg safe?",
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("250 mg"))
        }
    }

    @Test
    fun `rule 3 does not flag structural numbers`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "1. Introduction\n2. Methods\nSee section 3.1.\nYear 2024.",
            citations = listOf(citation()),
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("clinical quantities"))
        }
    }

    @Test
    fun `rule 3 boundary check prevents partial matches`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The value is 12 mg.",
            citations = listOf(citation()),
            toolResults = listOf("Value: 123 mg."),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 4: Citations required
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 4 blocks when no citations`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "test",
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("No citations"))
    }

    @Test
    fun `rule 4 passes with valid citation`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "test",
            citations = listOf(citation()),
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("No citations"))
        }
    }

    @Test
    fun `rule 4 density relaxed - one valid citation out of many fetched`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "test",
            citations = listOf(citation(sectionId = "H1")),
            toolResults = listOf("content"),
            fetchedSections = (1..5).map { section(sectionId = "H$it", title = "Section $it") },
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("citations match"))
        }
    }

    @Test
    fun `rule 4 blocks when no citation matches any fetched section`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "test",
            citations = listOf(citation(sectionId = "Z99", sectionTitle = "Nonexistent")),
            toolResults = listOf("content"),
            fetchedSections = listOf(section(sectionId = "H1", title = "Real Section")),
        ))
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("citations match"))
    }

    // ══════════════════════════════════════════════════════════════════
    // Rule 5: No graphic interpretation
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `rule 5 passes when no visual language`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The recommended treatment is aspirin.",
            citations = listOf(citation()),
            toolResults = listOf("content"),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("visual details"))
        }
    }

    @Test
    fun `rule 5 passes when visual language is in tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The x-ray shows opacity.",
            citations = listOf(citation()),
            toolResults = listOf("The x-ray shows opacity in the lower lobe."),
            fetchedSections = listOf(section()),
        ))
        if (!result.passed) {
            assertFalse(result.blockedReason!!.contains("visual details"))
        }
    }

    @Test
    fun `rule 5 blocks when visual language not in tool results`() {
        val result = validator.validate(ctx(
            toolCalls = listOf(toolCall()),
            answer = "The image shows a mass.",
            citations = listOf(citation()),
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
            toolCalls = listOf(toolCall(args = mapOf("section_id" to "H1"))),
            answer = "The dose is 5 mg.\n\nTopic: Drug X, Section: Dosing (ID: H1)",
            citations = listOf(citation(topicTitle = "Drug X", sectionTitle = "Dosing", sectionId = "H1")),
            toolResults = listOf("The dose is 5 mg."),
            fetchedSections = listOf(section(sectionId = "H1", title = "Dosing")),
        ))
        assertTrue(result.passed)
        assertEquals(1, result.citations.size)
    }

    @Test
    fun `full validation short-circuits on first failure`() {
        // No tool calls → fails rule 1 immediately
        val result = validator.validate(ctx())
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("No tool calls"))
    }
}
