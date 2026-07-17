package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SafetyValidatorTest {

    private lateinit var validator: SafetyValidator

    @Before
    fun setup() {
        validator = SafetyValidator()
    }

    // ── Rule 1: Tool call required ──────────────────────────────────

    @Test
    fun `Rule 1 blocks when no tool calls made`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = emptyList(),
            answer = "Metformin is used for diabetes.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin is first-line for type 2 diabetes.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertNotNull(result.blockedReason)
        assertTrue(result.blockedReason!!.contains("No tool calls"))
    }

    @Test
    fun `Rule 1 passes when tool calls exist`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = "searchTopics",
                    arguments = mapOf("query" to "diabetes"),
                    result = "[{id: 123, title: Diabetes}]",
                    success = true
                )
            ),
            answer = "Metformin is used for diabetes.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin is first-line for type 2 diabetes.")
        )
        val result = validator.validate(context)
        // Rule 1 passes, but may fail on later rules — that's OK for this test
        // We just verify it doesn't fail on Rule 1
        assertTrue(result.blockedReason == null || !result.blockedReason!!.contains("No tool calls"))
    }

    // ── Rule 1b: Section content required ───────────────────────────

    @Test
    fun `Rule 1b blocks when only searchTopics and getTopicOutline called`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("searchTopics", mapOf("query" to "diabetes"), "results", true),
                SafetyValidator.ToolCallRecord("getTopicOutline", mapOf("topicId" to "123"), "outline", true)
            ),
            answer = "Diabetes is a condition.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Overview")
            ),
            fetchedSections = emptyList(),
            toolResults = listOf("outline content")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("section content"))
    }

    @Test
    fun `Rule 1b passes when getTopicSectionText called`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("searchTopics", mapOf("query" to "diabetes"), "results", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "section content", true)
            ),
            answer = "Metformin 500mg twice daily is recommended.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily is recommended for type 2 diabetes.")
        )
        val result = validator.validate(context)
        // Should pass Rule 1b
        assertTrue(result.blockedReason == null || !result.blockedReason!!.contains("section content"))
    }

    // ── Rule 2: Citation-to-tool consistency ─────────────────────────

    @Test
    fun `Rule 2 passes when citation matches fetched section`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Metformin is recommended [Diabetes > Treatment (sec-1)].",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily is recommended.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    @Test
    fun `Rule 2 blocks when citation references unfetched section`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Based on guidelines [Diabetes > Dosage (sec-99)].",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-99", "Dosage")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily is recommended.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("not retrieved"))
    }

    @Test
    fun `Rule 2 passes with fuzzy sectionTitle match`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Treatment involves metformin [Diabetes > Treatment and Management (sec-1)].",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment and Management")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily is recommended.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    // ── Rule 3: Complex data warning ─────────────────────────────────

    @Test
    fun `Rule 3 warns when complex section not flagged in answer`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The dosing table shows 500mg twice daily.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Dosing")
            ),
            sectionWarnings = mapOf("sec-1" to true),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Dosing")
            ),
            toolResults = listOf("Dosing table: 500mg, 850mg, 1000mg")
        )
        val result = validator.validate(context)
        assertTrue(result.passed) // warns but doesn't block
        assertTrue(result.warnings.any { it.contains("complex clinical data") })
    }

    @Test
    fun `Rule 3 passes when warning is included in answer`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "[WARNING: This section contains complex dosing tables. Please verify the raw details directly.] The dosing table shows 500mg twice daily.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Dosing")
            ),
            sectionWarnings = mapOf("sec-1" to true),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Dosing")
            ),
            toolResults = listOf("Dosing table: 500mg, 850mg, 1000mg")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
        assertTrue(result.warnings.none { it.contains("complex clinical data") })
    }

    // ── Rule 4: No invented numbers ──────────────────────────────────

    @Test
    fun `Rule 4 passes when all numbers traceable to tool results`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Metformin 500mg twice daily is recommended. eGFR should be above 30.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily is recommended for patients with eGFR above 30 mL/min.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    @Test
    fun `Rule 4 blocks when invented number not in tool results`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The maximum dose is 2500mg daily.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily is recommended. Maximum dose 2000mg.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("2500mg"))
    }

    @Test
    fun `Rule 4 passes for multi-source facts where each number is traceable`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-a"), "content A", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "456", "sectionId" to "sec-b"), "content B", true)
            ),
            answer = "Metformin 500mg twice daily is recommended. Lisinopril 10mg daily for blood pressure.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-a", "Treatment"),
                SafetyValidator.Citation("456", "Hypertension", "sec-b", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-a", "Treatment"),
                SafetyValidator.FetchedSection("456", "Hypertension", "sec-b", "Treatment")
            ),
            toolResults = listOf(
                "Metformin 500mg twice daily is recommended for diabetes.",
                "Lisinopril 10mg daily is recommended for hypertension."
            )
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    // ── Rule 5: Citation required ────────────────────────────────────

    @Test
    fun `Rule 5 blocks when no citations provided`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Metformin is recommended.",
            citations = emptyList(),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("No citations"))
    }

    @Test
    fun `Rule 5 passes when citations present`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Metformin is recommended [Diabetes > Treatment (sec-1)].",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    // ── Combined / edge cases ────────────────────────────────────────

    @Test
    fun `full valid context passes all rules`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("searchTopics", mapOf("query" to "diabetes"), "results", true),
                SafetyValidator.ToolCallRecord("getTopicOutline", mapOf("topicId" to "123"), "outline", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Metformin 500mg twice daily is first-line treatment [Diabetes > Treatment (sec-1)].",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily is first-line for type 2 diabetes.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    @Test
    fun `empty answer with no tools is blocked`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = emptyList(),
            answer = "",
            citations = emptyList()
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
    }
}
