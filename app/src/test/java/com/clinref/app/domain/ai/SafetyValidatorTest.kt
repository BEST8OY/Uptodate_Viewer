package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // Rule 1 passes — may fail on later rules, which is fine for this test
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
    fun `Rule 1b passes when getTopicSectionText called successfully`() {
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
        assertTrue(result.blockedReason == null || !result.blockedReason!!.contains("section content"))
    }

    @Test
    fun `Rule 1b blocks when getTopicSectionText failed`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123"), "Section not found.", false)
            ),
            answer = "Diabetes is a condition.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Overview")
            ),
            fetchedSections = emptyList(),
            toolResults = listOf("Section not found.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("section content"))
    }

    // ── Rule 2: Citation consistency ─────────────────────────────────

    @Test
    fun `Rule 2 passes when citation ID matches fetched section`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Metformin is recommended.\n\nTopic: Diabetes, Section: Treatment (ID: sec-1)\n",
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
            answer = "Based on guidelines.\n\nTopic: Diabetes, Section: Dosage (ID: sec-99)\n",
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
    fun `Rule 2 passes with fuzzy title match as fallback`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Treatment involves metformin.\n\nTopic: Diabetes, Section: Treatment and Management (ID: sec-1-fuzzy)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1-fuzzy", "Treatment and Management")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily is recommended.")
        )
        val result = validator.validate(context)
        // ID doesn't match, but title "Treatment and Management" contains "Treatment" (bidirectional)
        assertTrue(result.passed)
        assertTrue(result.warnings.any { it.contains("title match") || it.contains("fallback") })
    }

    @Test
    fun `Rule 2 blocks when neither ID nor title matches`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Some answer.\n\nTopic: Diabetes, Section: Surgery (ID: sec-99)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-99", "Surgery")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("Surgery"))
    }

    // ── Rule 3: No invented numbers ──────────────────────────────────

    @Test
    fun `Rule 3 passes when all numbers traceable to tool results`() {
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
    fun `Rule 3 blocks when invented number not in tool results`() {
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
    fun `Rule 3 passes for multi-source facts where each number is traceable`() {
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

    @Test
    fun `Rule 3 passes when no tool results to compare against`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The dose is 500mg.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = emptyList()
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    @Test
    fun `Rule 3 normalizes whitespace between number and unit`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The dose is 5.0 MG twice daily.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Give 5.0mg twice daily.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    @Test
    fun `Rule 3 prevents substring false positive`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The value is 12 units.",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Reference range is 123 units.")
        )
        val result = validator.validate(context)
        // "12" should NOT match as part of "123" — boundary check prevents substring match
        assertFalse(result.passed)
    }

    // ── Rule 4: Citation required ────────────────────────────────────

    @Test
    fun `Rule 4 blocks when no citations provided`() {
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
    fun `Rule 4 passes when citations present`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Metformin is recommended.\n\nTopic: Diabetes, Section: Treatment (ID: sec-1)\n",
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

    // ── Rule 5: No graphic interpretation ────────────────────────────

    @Test
    fun `Rule 5 blocks visual interpretation when graphic IDs present`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The ECG demonstrates ST-segment elevation in leads II, III, and aVF.\n\nTopic: Diagnosis, Section: ECG Findings (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diagnosis", "sec-1", "ECG Findings")
            ),
            graphicIds = setOf("G12345"),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diagnosis", "sec-1", "ECG Findings")
            ),
            toolResults = listOf("ECG findings for acute MI include ST-segment changes.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("visual interpretation"))
    }

    @Test
    fun `Rule 5 blocks when answer contains Graphic refs`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The x-ray shows a fracture. See also Graphic-ABC123.\n\nTopic: Diagnosis, Section: Imaging (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diagnosis", "sec-1", "Imaging")
            ),
            graphicIds = emptySet(),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diagnosis", "sec-1", "Imaging")
            ),
            toolResults = listOf("Imaging reveals fracture.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("visual interpretation"))
    }

    @Test
    fun `Rule 5 passes when referencing graphic type without interpretation`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getGraphicInfo", mapOf("graphicId" to "G12345"), "result", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "This section includes a reference for evaluating acute chest pain. Please review the source directly.\n\nTopic: Diagnosis, Section: ECG Findings (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diagnosis", "sec-1", "ECG Findings")
            ),
            graphicIds = setOf("G12345"),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diagnosis", "sec-1", "ECG Findings")
            ),
            toolResults = listOf("ECG findings for acute MI.")
        )
        val result = validator.validate(context)
        // No visual interpretation language — patterns require "the X shows/demonstrates" etc.
        assertTrue(result.passed)
    }

    @Test
    fun `Rule 5 blocks when visual language and graphic refs in answer`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The image shows a mass. See also Graphic-ABC.\n\nTopic: Diagnosis, Section: Imaging (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diagnosis", "sec-1", "Imaging")
            ),
            graphicIds = setOf("G999"),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diagnosis", "sec-1", "Imaging")
            ),
            toolResults = listOf("Imaging findings.")
        )
        val result = validator.validate(context)
        // Visual language + graphic refs → block
        assertFalse(result.passed)
    }

    @Test
    fun `Rule 5 passes when no graphics and no visual language`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "Metformin 500mg twice daily is recommended.\n\nTopic: Diabetes, Section: Treatment (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            graphicIds = emptySet(),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Metformin 500mg twice daily.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    @Test
    fun `Rule 5 warns when visual language but no graphic tool called`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The image shows a mass in the right lower lobe.\n\nTopic: Diagnosis, Section: Imaging (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diagnosis", "sec-1", "Imaging")
            ),
            graphicIds = emptySet(),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diagnosis", "sec-1", "Imaging")
            ),
            toolResults = listOf("Imaging reveals mass.")
        )
        val result = validator.validate(context)
        // Visual language present, no graphic tool called, no graphic refs — warning only
        assertTrue(result.passed)
        assertTrue(result.warnings.any { it.contains("visual-interpretation") || it.contains("graphic tool") })
    }

    @Test
    fun `Rule 5 does not warn when visual language and graphic tool was called`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getGraphicInfo", mapOf("graphicId" to "G12345"), "result", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The image shows a mass in the right lower lobe.\n\nTopic: Diagnosis, Section: Imaging (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diagnosis", "sec-1", "Imaging")
            ),
            graphicIds = emptySet(),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diagnosis", "sec-1", "Imaging")
            ),
            toolResults = listOf("Imaging reveals mass.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
        // No warning because getGraphicInfo was called
        assertTrue(result.warnings.none { it.contains("graphic tool") })
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
            answer = "Metformin 500mg twice daily is first-line treatment.\n\nTopic: Diabetes, Section: Treatment (ID: sec-1)\n",
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
    fun `empty answer with no tools is blocked on Rule 1`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = emptyList(),
            answer = "",
            citations = emptyList()
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("No tool calls"))
    }

    @Test
    fun `Rule 4 blocks before Rule 3 when no citations and numbers present`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The dose is 500mg.",
            citations = emptyList(),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Give 500mg twice daily.")
        )
        val result = validator.validate(context)
        // Rule 4 (citation required) fires before Rule 3 (invented numbers) in the cascade
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("No citations"))
    }

    @Test
    fun `multiple citations all must pass consistency`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content A", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "456", "sectionId" to "sec-2"), "content B", true)
            ),
            answer = "Answer.\n\nTopic: Diabetes, Section: Treatment (ID: sec-1)\nTopic: Hypertension, Section: Dosing (ID: sec-99)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment"),
                SafetyValidator.Citation("456", "Hypertension", "sec-99", "Dosing")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment"),
                SafetyValidator.FetchedSection("456", "Hypertension", "sec-2", "Management")
            ),
            toolResults = listOf("Content A", "Content B")
        )
        val result = validator.validate(context)
        // sec-1 matches, sec-99 doesn't match sec-2, and "Dosing" != "Management"
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("Dosing"))
    }

    @Test
    fun `graphic interpretation blocks on image shows pattern`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The CT reveals a pulmonary embolism.\n\nTopic: Diagnosis, Section: Imaging (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diagnosis", "sec-1", "Imaging")
            ),
            graphicIds = setOf("G999"),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diagnosis", "sec-1", "Imaging")
            ),
            toolResults = listOf("CT findings.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
    }

    @Test
    fun `graphic interpretation blocks on findings pattern`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "MRI findings suggest demyelination.\n\nTopic: Diagnosis, Section: Imaging (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diagnosis", "sec-1", "Imaging")
            ),
            graphicIds = setOf("G999"),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diagnosis", "sec-1", "Imaging")
            ),
            toolResults = listOf("MRI findings.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
    }

    @Test
    fun `graphic interpretation blocks on visual appears to show pattern`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The lesion appears to show malignancy.\n\nTopic: Diagnosis, Section: Pathology (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diagnosis", "sec-1", "Pathology")
            ),
            graphicIds = setOf("G999"),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diagnosis", "sec-1", "Pathology")
            ),
            toolResults = listOf("Pathology report.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
    }

    @Test
    fun `graphic interpretation blocks on figure shows pattern`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The algorithm demonstrates the treatment pathway.\n\nTopic: Treatment, Section: Overview (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Treatment", "sec-1", "Overview")
            ),
            graphicIds = setOf("G999"),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Treatment", "sec-1", "Overview")
            ),
            toolResults = listOf("Treatment algorithm.")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
    }

    @Test
    fun `warning accumulation across rules`() {
        // Rule 2 warning (title fallback) + Rule 5 warning (visual language, no graphic tool)
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The image shows improvement.\n\nTopic: Diabetes, Section: Treatment and Management (ID: sec-1-fuzzy)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1-fuzzy", "Treatment and Management")
            ),
            graphicIds = emptySet(),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Imaging shows improvement.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
        // Should have both Rule 2 fallback warning and Rule 5 visual language warning
        assertTrue(result.warnings.size >= 2)
    }

    // ── Markdown formatting strip ────────────────────────────────────

    @Test
    fun `markdown list numbers not treated as clinical data`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "1. The dose is 500mg twice daily for 7 days.\n2. Monitor kidney function.\n3. Continue treatment.\n\nTopic: Diabetes, Section: Treatment (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("The dose is 500mg twice daily for 7 days.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    @Test
    fun `bold numbers match tool results`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The dose is **500mg** twice daily.\n\nTopic: Diabetes, Section: Treatment (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("The dose is 500mg twice daily.")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    @Test
    fun `boundary aware number matching prevents substring false positive`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The value is 12 units.\n\nTopic: Diabetes, Section: Treatment (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Diabetes", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Diabetes", "sec-1", "Treatment")
            ),
            toolResults = listOf("Reference range is 123 units.")
        )
        val result = validator.validate(context)
        // "12" should NOT match as part of "123" — boundary check prevents substring match
        assertFalse(result.passed)
    }

    // ── Citation density ─────────────────────────────────────────────

    @Test
    fun `citation density blocks when multiple sections uncited`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content A", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-2"), "content B", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-3"), "content C", true)
            ),
            answer = "Summary from Section A.\n\nTopic: Test, Section: Section A (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Test", "sec-1", "Section A")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Test", "sec-1", "Section A"),
                SafetyValidator.FetchedSection("123", "Test", "sec-2", "Section B"),
                SafetyValidator.FetchedSection("123", "Test", "sec-3", "Section C")
            ),
            toolResults = listOf("Content A", "Content B", "Content C")
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("not cited"))
    }

    @Test
    fun `citation density allows one uncited section`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content A", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-2"), "content B", true),
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-3"), "content C", true)
            ),
            answer = "Summary from Section A and B.\n\nTopic: Test, Section: Section A (ID: sec-1)\nTopic: Test, Section: Section B (ID: sec-2)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Test", "sec-1", "Section A"),
                SafetyValidator.Citation("123", "Test", "sec-2", "Section B")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Test", "sec-1", "Section A"),
                SafetyValidator.FetchedSection("123", "Test", "sec-2", "Section B"),
                SafetyValidator.FetchedSection("123", "Test", "sec-3", "Section C")
            ),
            toolResults = listOf("Content A", "Content B", "Content C")
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    // ── User question numbers ────────────────────────────────────────

    @Test
    fun `patient-specific numbers from question are not flagged as invented`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The patient is 62 years old with eGFR of 35 mL/min. Reduce dose.\n\nTopic: Gout, Section: Treatment (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Gout", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Gout", "sec-1", "Treatment")
            ),
            toolResults = listOf("Reduce dose for renal impairment."),
            userQuestion = "A 62-year-old man with eGFR of 35 mL/min presents with gout."
        )
        val result = validator.validate(context)
        assertTrue(result.passed)
    }

    @Test
    fun `invented numbers still blocked even with user question`() {
        val context = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionText", mapOf("topicId" to "123", "sectionId" to "sec-1"), "content", true)
            ),
            answer = "The dose is 500mg. Patient is 62 years old.\n\nTopic: Gout, Section: Treatment (ID: sec-1)\n",
            citations = listOf(
                SafetyValidator.Citation("123", "Gout", "sec-1", "Treatment")
            ),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("123", "Gout", "sec-1", "Treatment")
            ),
            toolResults = listOf("Reduce dose for renal impairment."),
            userQuestion = "A 62-year-old man with eGFR of 35 mL/min presents with gout."
        )
        val result = validator.validate(context)
        assertFalse(result.passed)
        assertTrue(result.blockedReason!!.contains("500mg"))
    }
}
