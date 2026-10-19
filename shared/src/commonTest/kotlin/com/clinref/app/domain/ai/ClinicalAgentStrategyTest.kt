package com.clinref.app.domain.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalAgentStrategyTest {

    @Test
    fun `strategy creates autonomous graph with correct name`() {
        val strategy = ClinicalAgentStrategy.create()

        assertNotNull(strategy)
        assertEquals("clinical-autonomous", strategy.name)
    }

    @Test
    fun `strategy contains all required lifecycle, safety, and remediation nodes`() {
        val strategy = ClinicalAgentStrategy.create()
        val nodeNames = strategy.metadata.nodesMap.values.map { it.name }.toSet()

        assertTrue("Must contain nodeSendInput", nodeNames.any { it.contains("nodeSendInput") })
        assertTrue("Must contain nodeExecuteTool", nodeNames.any { it.contains("nodeExecuteTool") })
        assertTrue("Must contain nodeSendToolResult", nodeNames.any { it.contains("nodeSendToolResult") })
        assertTrue("Must contain intercept_premature", nodeNames.contains("intercept_premature"))
        assertTrue("Must contain clinical_synthesis", nodeNames.contains("clinical_synthesis"))
        assertTrue("Must contain validate_safety", nodeNames.contains("validate_safety"))
        assertTrue("Must contain build_remediation", nodeNames.contains("build_remediation"))
        assertTrue("Must contain safety_block", nodeNames.contains("safety_block"))
    }

    @Test
    fun `strategy defines edges connecting start, tool loop, validation, remediation, and finish`() {
        val strategy = ClinicalAgentStrategy.create()
        val allNodes = strategy.metadata.nodesMap.values
        val allEdges = allNodes.flatMap { it.edges }

        assertTrue("Strategy must have multiple directed edges", allEdges.isNotEmpty())

        // Start node connects to LLM input
        assertTrue(
            "An edge must originate from startNode",
            strategy.nodeStart.edges.isNotEmpty()
        )

        // Finish node must be targeted by safety valid path and safety block path
        val finishEdges = allEdges.filter { it.toNode == strategy.nodeFinish }
        assertTrue("At least one edge must target nodeFinish", finishEdges.isNotEmpty())

        val sendInputNode = allNodes.first { it.name.contains("nodeSendInput") }
        val remediateNode = allNodes.first { it.name == "build_remediation" }
        val interceptNode = allNodes.first { it.name == "intercept_premature" }
        val synthesisNode = allNodes.first { it.name == "clinical_synthesis" }
        val validateNode = allNodes.first { it.name == "validate_safety" }
        val safetyBlockNode = allNodes.first { it.name == "safety_block" }

        // Remediation must loop back to nodeSendInput
        assertTrue(
            "Remediation node must edge forwardTo nodeSendInput",
            remediateNode.edges.any { it.toNode == sendInputNode }
        )

        // Intercept premature node must loop back to nodeSendInput
        assertTrue(
            "intercept_premature node must edge forwardTo nodeSendInput",
            interceptNode.edges.any { it.toNode == sendInputNode }
        )

        // Clinical synthesis node must edge forwardTo validate_safety
        assertTrue(
            "clinical_synthesis node must edge forwardTo validate_safety",
            synthesisNode.edges.any { it.toNode == validateNode }
        )

        // Safety validation node must edge to finish, remediation, and safety_block
        assertTrue(
            "validate_safety must connect to nodeFinish",
            validateNode.edges.any { it.toNode == strategy.nodeFinish }
        )
        assertTrue(
            "validate_safety must connect to build_remediation",
            validateNode.edges.any { it.toNode == remediateNode }
        )
        assertTrue(
            "validate_safety must connect to safety_block",
            validateNode.edges.any { it.toNode == safetyBlockNode }
        )

        // LLM output routing connects to premature interception and synthesis
        val sendToolResultNode = allNodes.first { it.name.contains("nodeSendToolResult") }
        assertTrue(
            "nodeSendInput must connect to intercept_premature",
            sendInputNode.edges.any { it.toNode == interceptNode }
        )
        assertTrue(
            "nodeSendInput must connect to clinical_synthesis",
            sendInputNode.edges.any { it.toNode == synthesisNode }
        )
        assertTrue(
            "nodeSendToolResult must connect to intercept_premature",
            sendToolResultNode.edges.any { it.toNode == interceptNode }
        )
        assertTrue(
            "nodeSendToolResult must connect to clinical_synthesis",
            sendToolResultNode.edges.any { it.toNode == synthesisNode }
        )
    }

    @Test
    fun `phase 1 tool loop funnels through round boundary with forced safety exit`() {
        val strategy = ClinicalAgentStrategy.create()
        val allNodes = strategy.metadata.nodesMap.values

        assertEquals("Tool round budget matches Python agent.max_tool_retries", 24, ClinicalAgentStrategy.MAX_TOOL_ROUNDS)

        val boundaryNode = allNodes.firstOrNull { it.name == "tool_round_boundary" }
        assertNotNull("Round boundary accounting node must exist", boundaryNode)

        val executeNode = allNodes.first { it.name.contains("nodeExecuteTool") }
        val sendToolResultNode = allNodes.first { it.name.contains("nodeSendToolResult") }
        val validateNode = allNodes.first { it.name == "validate_safety" }

        // Every tool execution passes through the accounting boundary
        assertTrue(
            "nodeExecuteTool must funnel into tool_round_boundary",
            executeNode.edges.any { it.toNode == boundaryNode }
        )

        // When the budget remains, the boundary continues to the follow-up LLM call
        assertTrue(
            "tool_round_boundary must continue to nodeSendToolResult within budget",
            boundaryNode!!.edges.any { it.toNode == sendToolResultNode }
        )

        // When the budget is exhausted, the boundary bypasses the LLM and forces safety
        assertTrue(
            "tool_round_boundary must force-exit into validate_safety at budget exhaustion",
            boundaryNode.edges.any { it.toNode == validateNode }
        )
    }

    @Test
    fun `safety evaluation sealed class holds correct models and remediation targets`() {
        val valid = ClinicalAgentStrategy.SafetyEvaluation.Valid("Verified response")
        assertEquals("Verified response", valid.content)

        val remediationEvidence = ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation(
            reason = "Missing section evidence",
            evidence = "No section text",
            targetPhase = ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE
        )
        assertEquals("Missing section evidence", remediationEvidence.reason)
        assertEquals("No section text", remediationEvidence.evidence)
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE, remediationEvidence.targetPhase)

        val remediationSynthesis = ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation(
            reason = "Unverified dosage",
            evidence = "Standard dose 5 mg",
            targetPhase = ClinicalAgentStrategy.RemediationTarget.PHASE_2_SYNTHESIS
        )
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_2_SYNTHESIS, remediationSynthesis.targetPhase)

        val blocked = ClinicalAgentStrategy.SafetyEvaluation.Blocked("Invented numbers")
        assertEquals("Invented numbers", blocked.reason)
    }

    @Test
    fun `clinical state sealed hierarchy represents operational phases`() {
        val evidencePhase = ClinicalAgentStrategy.ClinicalState.EvidenceGathering(
            query = "What is the dose of amoxicillin?",
            toolRounds = 1,
            fetchedSectionsCount = 2,
            graphicCount = 0
        )
        assertEquals(1, evidencePhase.toolRounds)
        assertEquals(2, evidencePhase.fetchedSectionsCount)

        val turnContext = TurnContextAccumulator().buildTurnContext("Clean clinical response")
        val synthesisPhase = ClinicalAgentStrategy.ClinicalState.ClinicalSynthesis(
            rawResponse = "<thought>Thinking...</thought>Clean clinical response",
            cleanedResponse = "Clean clinical response",
            turnContext = turnContext
        )
        assertEquals("Clean clinical response", synthesisPhase.cleanedResponse)

        val verificationPhase = ClinicalAgentStrategy.ClinicalState.SafetyVerification(
            evaluation = ClinicalAgentStrategy.SafetyEvaluation.Valid("Verified"),
            retryCount = 0,
            targetPhase = ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE
        )
        assertEquals(0, verificationPhase.retryCount)
    }

    @Test
    fun `buildPrematureInterceptionPrompt contains required retrieval directives`() {
        val prompt = SystemPrompt.buildPrematureInterceptionPrompt()
        assertTrue(prompt.contains("SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED"))
        assertTrue(prompt.contains("MANDATORY CLINICAL DIRECTIVE"))
        assertTrue(prompt.contains("getTopicOverview"))
        assertTrue(prompt.contains("searchTopics"))
    }

    @Test
    fun `buildCorrectionPrompt with missing sections instructs immediate tool retrieval`() {
        val prompt = SystemPrompt.buildCorrectionPrompt(
            blockedReason = "Answer requires section or table content retrieval.",
            evidenceSummary = "No section text was successfully fetched in the prior turn."
        )
        assertTrue(prompt.contains("REMEDIAL ACTION REQUIRED"))
        assertTrue(prompt.contains("getTopicSectionsText"))
    }

    @Test
    fun `buildCorrectionPrompt with unverified quantities instructs figure correction`() {
        val prompt = SystemPrompt.buildCorrectionPrompt(
            blockedReason = "Unverified clinical quantities found in response: 750 mg.",
            evidenceSummary = "Section [H1] (Dosing):\nCeftriaxone 1,000 mg IV daily."
        )
        assertTrue(prompt.contains("REMEDIAL INSTRUCTIONS"))
        assertTrue(prompt.contains("Correct or remove any unverified clinical quantities"))
    }

    @Test
    fun `context hygiene truncates only stale oversized search_topics results at the tool round boundary`() {
        val oversized = "x".repeat(400)
        val staleSearch = ToolResultPart(id = "call-1", tool = "search_topics", output = oversized)
        val compactSearch = ToolResultPart(id = "call-2", tool = "search_topics", output = "tiny results")
        val freshTable = ToolResultPart(id = "call-3", tool = "get_graphic_content", output = oversized)
        val textPart = MessagePart.Text("plain text marker")

        val history = Message.User(
            parts = listOf(staleSearch, compactSearch, freshTable, textPart),
            metaInfo = RequestMetaInfo(KoogClock.System.now()),
            id = null
        )

        val compacted = ClinicalAgentStrategy.truncateStaleSearchResults(history) as Message.User

        assertEquals(4, compacted.parts.size)
        val truncated = compacted.parts[0] as ToolResultPart
        // 250 kept chars + "... [truncated search results]" suffix
        assertEquals(250 + "... [truncated search results]".length, truncated.output.length)
        assertTrue(truncated.output.startsWith("x".repeat(250)))
        // Truncation must not corrupt protocol-critical tool-result metadata
        assertEquals("call-1", truncated.id)
        assertEquals("search_topics", truncated.tool)
        assertFalse(truncated.isError)
        // Untouched instances are preserved verbatim (no accidental copies)
        assertSame(compactSearch, compacted.parts[1])
        assertSame(freshTable, compacted.parts[2])
        assertSame(textPart, compacted.parts[3])
    }

    @Test
    fun `context hygiene is a no-op for clean, small, or non-user messages`() {
        val now = KoogClock.System.now()

        // No oversized search part -> original instance returned untouched
        val cleanUser = Message.User(
            content = "clinical question",
            metaInfo = RequestMetaInfo(now),
            id = null
        )
        assertSame(cleanUser, ClinicalAgentStrategy.truncateStaleSearchResults(cleanUser))

        // Oversized payload but wrong tool name -> no-op
        val nonSearch = Message.User(
            parts = listOf(ToolResultPart(id = "c", tool = "get_topic_sections_text", output = "y".repeat(500))),
            metaInfo = RequestMetaInfo(now),
            id = null
        )
        assertSame(nonSearch, ClinicalAgentStrategy.truncateStaleSearchResults(nonSearch))

        // Assistant/System messages never carry compactable tool results
        val assistant = Message.Assistant(
            content = "assistant text",
            metaInfo = ResponseMetaInfo(now),
            id = null
        )
        assertSame(assistant, ClinicalAgentStrategy.truncateStaleSearchResults(assistant))
    }

    @Test
    fun `premature interception condition logic respects tool calls and retrieved evidence`() {
        val accumulator = TurnContextAccumulator()
        accumulator.setUserQuestion("What are diabetes blood pressure goals?")

        // 1. Initial state without tools or evidence -> clinical text is premature
        assertTrue(
            "Clinical response before tools or evidence must be premature",
            ClinicalAgentStrategy.isPrematureTurn(accumulator, "Target BP is <130/80 mmHg", 0, 2)
        )

        // 2. Conversational response without tools or evidence -> NOT premature
        assertFalse(
            "Conversational greeting without tools must not be premature",
            ClinicalAgentStrategy.isPrematureTurn(accumulator, "Hello, how can I help you today?", 0, 2)
        )

        // 3. Evidence fetched (sections present) -> NOT premature
        accumulator.onToolCallCompleted(
            toolCallId = "call-1",
            toolName = "getTopicSectionsText",
            result = """{"topicId":"3861","topicTitle":"Hypertension in adults","sectionTitles":{"H1":"Goals"}}""",
            success = true,
            toolArgs = """{"topicId":"3861","sectionIds":["H1"]}"""
        )
        assertFalse(
            "Clinical response with fetched section evidence must never be premature",
            ClinicalAgentStrategy.isPrematureTurn(accumulator, "Target BP is <130/80 mmHg", 0, 2)
        )

        // 4. Retry budget exhausted -> NOT premature (allows flow to safety verification)
        val freshAccumulator = TurnContextAccumulator()
        freshAccumulator.setUserQuestion("What are diabetes blood pressure goals?")
        assertFalse(
            "Exhausted premature retries must not be intercepted as premature",
            ClinicalAgentStrategy.isPrematureTurn(freshAccumulator, "Target BP is <130/80 mmHg", 2, 2)
        )
    }

    @Test
    fun `evaluateSafetyTurn returns Valid when response passes clinical verification`() {
        val validator = SafetyValidator()
        val sections = listOf(
            SafetyValidator.FetchedSection(
                topicId = "3861",
                topicTitle = "Hypertension in adults",
                sectionId = "H1",
                sectionTitle = "Blood pressure targets",
                contentSnippet = "Goal blood pressure is <130/80 mmHg for patients with diabetes."
            )
        )
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = "getTopicSectionsText",
                    arguments = mapOf("topicId" to "3861", "sectionIds" to "[\"H1\"]"),
                    result = "Goal blood pressure is <130/80 mmHg",
                    success = true
                )
            ),
            answer = "Goal blood pressure is <130/80 mmHg for patients with diabetes.",
            fetchedSections = sections,
            userQuestion = "What are diabetes blood pressure goals?"
        )

        var retryCalled = false
        val evaluation = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = turnContext.answer,
            turnContext = turnContext,
            safetyValidator = validator,
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { retryCalled = true }
        )

        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.Valid)
        assertEquals("Goal blood pressure is <130/80 mmHg for patients with diabetes.", (evaluation as ClinicalAgentStrategy.SafetyEvaluation.Valid).content)
        assertFalse("Retry callback must not be invoked on valid response", retryCalled)
    }

    @Test
    fun `evaluateSafetyTurn steers blank response with evidence into Phase 2 synthesis`() {
        val validator = SafetyValidator()
        val sections = listOf(
            SafetyValidator.FetchedSection(
                topicId = "3861",
                topicTitle = "Hypertension in adults",
                sectionId = "H1",
                sectionTitle = "Blood pressure targets",
                contentSnippet = "Goal blood pressure is <130/80 mmHg"
            )
        )
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = "getTopicSectionsText",
                    arguments = mapOf("topicId" to "3861", "sectionIds" to "[\"H1\"]"),
                    result = "Goal blood pressure is <130/80 mmHg",
                    success = true
                )
            ),
            answer = "",
            fetchedSections = sections,
            userQuestion = "What are diabetes blood pressure goals?"
        )

        var retryCalled = false
        val evaluation = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = "",
            turnContext = turnContext,
            safetyValidator = validator,
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { retryCalled = true }
        )

        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation)
        val rem = evaluation as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_2_SYNTHESIS, rem.targetPhase)
        assertTrue(rem.reason.contains("No final clinical answer was synthesized"))
        assertTrue(rem.evidence.contains("Section [H1]"))
        assertTrue(retryCalled)
    }

    @Test
    fun `evaluateSafetyTurn blocks blank response when retries are exhausted`() {
        val validator = SafetyValidator()
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = emptyList(),
            answer = "",
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("3861", "Hypertension", "H1", "Goals", "Snippet")
            ),
            userQuestion = "What are diabetes blood pressure goals?"
        )

        val evaluation = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = "",
            turnContext = turnContext,
            safetyValidator = validator,
            retryCount = 2,
            maxSafetyRetries = 2,
            onPrepareRetry = { error("Should not prepare retry when budget exhausted") }
        )

        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.Blocked)
        val blocked = evaluation as ClinicalAgentStrategy.SafetyEvaluation.Blocked
        assertTrue(blocked.reason.contains("No final clinical answer was synthesized after evidence gathering"))
    }

    @Test
    fun `evaluateSafetyTurn steers missing evidence failure into Phase 1 evidence retrieval`() {
        val validator = SafetyValidator()
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = emptyList(),
            answer = "Dosing is 500 mg orally daily.",
            fetchedSections = emptyList(),
            userQuestion = "What is the dosing?"
        )

        var retryCalled = false
        val evaluation = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = turnContext.answer,
            turnContext = turnContext,
            safetyValidator = validator,
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { retryCalled = true }
        )

        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation)
        val rem = evaluation as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE, rem.targetPhase)
        assertTrue(retryCalled)
    }

    @Test
    fun `evaluateSafetyTurn steers unverified quantities with evidence into Phase 2 synthesis`() {
        val validator = SafetyValidator()
        val sections = listOf(
            SafetyValidator.FetchedSection(
                topicId = "100",
                topicTitle = "Drug Therapy",
                sectionId = "S1",
                sectionTitle = "Dosing",
                contentSnippet = "Standard dose is 250 mg daily."
            )
        )
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = "getTopicSectionsText",
                    arguments = mapOf("topicId" to "100", "sectionIds" to "[\"S1\"]"),
                    result = "Standard dose is 250 mg daily.",
                    success = true
                )
            ),
            answer = "Take 999 mg daily.", // 999 mg is invented/unverified
            toolResults = listOf("Standard dose is 250 mg daily."),
            fetchedSections = sections,
            userQuestion = "What is the dosage?"
        )

        var retryCalled = false
        val evaluation = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = turnContext.answer,
            turnContext = turnContext,
            safetyValidator = validator,
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { retryCalled = true }
        )

        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation)
        val rem = evaluation as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_2_SYNTHESIS, rem.targetPhase)
        assertTrue(rem.reason.contains("999 mg"))
        assertTrue(retryCalled)
    }

    @Test
    fun `evidenceSummaryFrom formats snippets and empty fallbacks cleanly`() {
        val emptySummary = ClinicalAgentStrategy.evidenceSummaryFrom(emptyList())
        assertEquals("No section text was successfully fetched in the prior turn.", emptySummary)

        val withSnippet = ClinicalAgentStrategy.evidenceSummaryFrom(
            listOf(
                SafetyValidator.FetchedSection(
                    topicId = "1",
                    topicTitle = "Topic A",
                    sectionId = "S1",
                    sectionTitle = "Overview",
                    contentSnippet = "Clinical text here"
                )
            )
        )
        assertTrue(withSnippet.contains("Section [S1] (Overview):"))
        assertTrue(withSnippet.contains("Clinical text here"))

        val withoutSnippet = ClinicalAgentStrategy.evidenceSummaryFrom(
            listOf(
                SafetyValidator.FetchedSection(
                    topicId = "1",
                    topicTitle = "Topic A",
                    sectionId = "S2",
                    sectionTitle = "Summary",
                    contentSnippet = ""
                )
            )
        )
        assertEquals("Section [S2] (Summary) from topic Topic A", withoutSnippet)
    }
}

/** Shorthand import alias for the Koog tool-result part used by compaction tests. */
private typealias Tool = ai.koog.prompt.message.MessagePart.Tool
private typealias ToolResultPart = ai.koog.prompt.message.MessagePart.Tool.Result
