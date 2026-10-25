package com.clinref.app.domain.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.utils.time.KoogClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

        assertTrue("Must contain evidence_gathering", nodeNames.contains("evidence_gathering"))
        assertTrue("Must contain validate_evidence", nodeNames.contains("validate_evidence"))
        assertTrue("Must contain compress_history", nodeNames.contains("compress_history"))
        assertTrue("Must contain clinical_synthesis", nodeNames.contains("clinical_synthesis"))
        assertTrue("Must contain validate_safety", nodeNames.contains("validate_safety"))
        assertTrue("Must contain build_remediation", nodeNames.contains("build_remediation"))
        assertTrue("Must contain build_remediation_synthesis", nodeNames.contains("build_remediation_synthesis"))
        assertTrue("Must contain safety_block", nodeNames.contains("safety_block"))
    }

    @Test
    fun `strategy defines edges connecting start, tool loop, validation, remediation, and finish`() {
        val strategy = ClinicalAgentStrategy.create()
        val allNodes = strategy.metadata.nodesMap.values
        val allEdges = allNodes.flatMap { it.edges }

        assertTrue("Strategy must have multiple directed edges", allEdges.isNotEmpty())

        // Start node connects to evidence gathering
        assertTrue(
            "An edge must originate from startNode",
            strategy.nodeStart.edges.isNotEmpty()
        )

        // Finish node must be targeted by safety valid path and safety block path
        val finishEdges = allEdges.filter { it.toNode == strategy.nodeFinish }
        assertTrue("At least one edge must target nodeFinish", finishEdges.isNotEmpty())

        val evidenceNode = allNodes.first { it.name == "evidence_gathering" }
        val validateEvidenceNode = allNodes.first { it.name == "validate_evidence" }
        val compressHistoryNode = allNodes.first { it.name == "compress_history" }
        val synthesisNode = allNodes.first { it.name == "clinical_synthesis" }
        val validateSafetyNode = allNodes.first { it.name == "validate_safety" }
        val remediateNode = allNodes.first { it.name == "build_remediation" }
        val remediateSynthesisNode = allNodes.first { it.name == "build_remediation_synthesis" }
        val safetyBlockNode = allNodes.first { it.name == "safety_block" }

        // Start connects to evidence gathering
        assertTrue(
            "nodeStart must edge forwardTo evidence_gathering",
            strategy.nodeStart.edges.any { it.toNode == evidenceNode }
        )

        // Evidence gathering forwards to validate_evidence
        assertTrue(
            "evidence_gathering must edge forwardTo validate_evidence",
            evidenceNode.edges.any { it.toNode == validateEvidenceNode }
        )

        // Pre-synthesis validation connects to remediation, block, compression, and synthesis
        assertTrue(
            "validate_evidence must connect to build_remediation",
            validateEvidenceNode.edges.any { it.toNode == remediateNode }
        )
        assertTrue(
            "validate_evidence must connect to safety_block",
            validateEvidenceNode.edges.any { it.toNode == safetyBlockNode }
        )
        assertTrue(
            "validate_evidence must connect to compress_history",
            validateEvidenceNode.edges.any { it.toNode == compressHistoryNode }
        )
        assertTrue(
            "validate_evidence must connect to clinical_synthesis",
            validateEvidenceNode.edges.any { it.toNode == synthesisNode }
        )

        // History compression connects to clinical_synthesis
        assertTrue(
            "compress_history must edge forwardTo clinical_synthesis",
            compressHistoryNode.edges.any { it.toNode == synthesisNode }
        )

        // Clinical synthesis forwards to validate_safety
        assertTrue(
            "clinical_synthesis must edge forwardTo validate_safety",
            synthesisNode.edges.any { it.toNode == validateSafetyNode }
        )

        // Post-synthesis validation connects to finish, remediations, and safety_block
        assertTrue(
            "validate_safety must connect to nodeFinish",
            validateSafetyNode.edges.any { it.toNode == strategy.nodeFinish }
        )
        assertTrue(
            "validate_safety must connect to build_remediation",
            validateSafetyNode.edges.any { it.toNode == remediateNode }
        )
        assertTrue(
            "validate_safety must connect to build_remediation_synthesis",
            validateSafetyNode.edges.any { it.toNode == remediateSynthesisNode }
        )
        assertTrue(
            "validate_safety must connect to safety_block",
            validateSafetyNode.edges.any { it.toNode == safetyBlockNode }
        )

        // Remediation loops
        assertTrue(
            "build_remediation must loop back to evidence_gathering",
            remediateNode.edges.any { it.toNode == evidenceNode }
        )
        assertTrue(
            "build_remediation_synthesis must loop back to clinical_synthesis",
            remediateSynthesisNode.edges.any { it.toNode == synthesisNode }
        )

        // Safety block connects to nodeFinish
        assertTrue(
            "safety_block must edge forwardTo nodeFinish",
            safetyBlockNode.edges.any { it.toNode == strategy.nodeFinish }
        )
    }

    @Test
    fun `phase 1 tool loop funnels through round boundary with forced safety exit`() {
        val strategy = ClinicalAgentStrategy.create()
        assertEquals("Tool round budget matches Python agent.max_tool_retries", 24, ClinicalAgentStrategy.MAX_TOOL_ROUNDS)

        val evidenceSubgraph = strategy.metadata.nodesMap.values
            .first { it.name == "evidence_gathering" } as ai.koog.agents.core.agent.entity.AIAgentSubgraphBase<*, *>

        // Collect all reachable nodes within evidence_gathering subgraph
        val subgraphNodes = mutableSetOf<ai.koog.agents.core.agent.entity.AIAgentNodeBase<*, *>>()
        fun collect(node: ai.koog.agents.core.agent.entity.AIAgentNodeBase<*, *>) {
            if (subgraphNodes.add(node)) {
                node.edges.forEach { collect(it.toNode) }
            }
        }
        collect(evidenceSubgraph.start)

        val boundaryNode = subgraphNodes.firstOrNull { it.name == "tool_round_boundary" }
        assertNotNull("Round boundary accounting node must exist in evidence_gathering", boundaryNode)

        val executeNode = subgraphNodes.first { it.name.contains("nodeExecuteTool") }
        val sendToolResultNode = subgraphNodes.first { it.name.contains("nodeSendToolResult") }

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

        // When the budget is exhausted, the boundary exits the subgraph to nodeFinish
        assertTrue(
            "tool_round_boundary must exit to subgraph finish at budget exhaustion",
            boundaryNode.edges.any { it.toNode == evidenceSubgraph.finish }
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
        assertTrue(prompt.contains("EVIDENCE GATE PAUSE"))
        assertTrue(prompt.contains("MANDATORY NEXT STEP"))
        assertTrue(prompt.contains("getTopicSectionsText"))
    }

    @Test
    fun `buildCorrectionPrompt with unverified quantities instructs figure correction`() {
        val prompt = SystemPrompt.buildCorrectionPrompt(
            blockedReason = "Unverified clinical quantities found in response: 750 mg.",
            evidenceSummary = "Section [H1] (Dosing):\nCeftriaxone 1,000 mg IV daily."
        )
        assertTrue(prompt.contains("NUMERICAL VERIFICATION BLOCK"))
        assertTrue(prompt.contains("rejected_quantities"))
        assertTrue(prompt.contains("750 mg"))
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

    @Test
    fun `isNearTokenLimit detects accumulator approaching compression threshold`() {
        val accumulator = TurnContextAccumulator()
        // Threshold: 1000 tokens -> 85% is 850 tokens (~2,975 chars)

        assertFalse(
            "Empty accumulator must not be near token limit",
            ClinicalAgentStrategy.isNearTokenLimit(accumulator, 1000)
        )

        accumulator.onToolCallCompleted(
            toolCallId = "call-1",
            toolName = "search_topics",
            result = "Short search result",
            success = true
        )
        assertFalse(
            "Small tool call result must not trigger token limit",
            ClinicalAgentStrategy.isNearTokenLimit(accumulator, 1000)
        )

        // 800 words * 1.1 = 880 tokens >= 850 tokens (85% of 1000)
        accumulator.onToolCallCompleted(
            toolCallId = "call-2",
            toolName = "get_topic_sections_text",
            result = "clinical evidence word ".repeat(267),
            success = true
        )
        assertTrue(
            "Accumulator with 800 words must trigger token limit for 1000 threshold",
            ClinicalAgentStrategy.isNearTokenLimit(accumulator, 1000)
        )
    }

    @Test
    fun `estimatePromptTokens accurately weights text, tool results, and tool calls`() {
        val now = KoogClock.System.now()
        val text100Words = "word ".repeat(100)
        val result200Words = "data ".repeat(200)
        val args40Words = "arg ".repeat(40)

        val userMsg = Message.User(
            parts = listOf(
                MessagePart.Text(text100Words),
                MessagePart.Tool.Result(id = "r1", tool = "test", output = result200Words)
            ),
            metaInfo = RequestMetaInfo(now),
            id = null
        )

        val assistantMsg = Message.Assistant(
            parts = listOf(
                MessagePart.Tool.Call(id = "c1", tool = "test", args = args40Words)
            ),
            metaInfo = ResponseMetaInfo(now),
            id = null
        )

        val messages = listOf(userMsg, assistantMsg)
        val estimatedTokens = ClinicalAgentStrategy.estimatePromptTokens(messages)

        // userMsg: 300 words * 1.1 = 330 tokens; assistantMsg: 40 words * 1.1 = 44 tokens; Total = 374 tokens
        assertEquals(374, estimatedTokens)
    }
}

