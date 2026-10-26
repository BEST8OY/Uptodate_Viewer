package com.clinref.app.domain.ai

import com.clinref.app.data.TopicSectionsResponse
import kotlinx.coroutines.test.runTest
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

        assertTrue("Must contain route_intent (Change 1 entry router)", nodeNames.contains("route_intent"))
        assertTrue("Must contain small_talk (Change 1 subgraph)", nodeNames.contains("small_talk"))
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
    fun `strategy defines edges connecting start, router, tool loop, validation, remediation, and finish`() {
        val strategy = ClinicalAgentStrategy.create()
        val allNodes = strategy.metadata.nodesMap.values
        val allEdges = allNodes.flatMap { it.edges }

        assertTrue("Strategy must have multiple directed edges", allEdges.isNotEmpty())

        // Start node connects to the entry router
        assertTrue(
            "An edge must originate from startNode",
            strategy.nodeStart.edges.isNotEmpty()
        )

        // Finish node must be targeted by safety valid path and safety block path
        val finishEdges = allEdges.filter { it.toNode == strategy.nodeFinish }
        assertTrue("At least one edge must target nodeFinish", finishEdges.isNotEmpty())

        val routeIntentNode = allNodes.first { it.name == "route_intent" }
        val smallTalkNode = allNodes.first { it.name == "small_talk" }
        val evidenceNode = allNodes.first { it.name == "evidence_gathering" }
        val validateEvidenceNode = allNodes.first { it.name == "validate_evidence" }
        val compressHistoryNode = allNodes.first { it.name == "compress_history" }
        val synthesisNode = allNodes.first { it.name == "clinical_synthesis" }
        val validateSafetyNode = allNodes.first { it.name == "validate_safety" }
        val remediateNode = allNodes.first { it.name == "build_remediation" }
        val remediateSynthesisNode = allNodes.first { it.name == "build_remediation_synthesis" }
        val safetyBlockNode = allNodes.first { it.name == "safety_block" }

        // Start connects to the entry router; the router forks into small-talk and clinical graph
        assertTrue(
            "nodeStart must edge forwardTo route_intent",
            strategy.nodeStart.edges.any { it.toNode == routeIntentNode }
        )
        assertTrue(
            "route_intent must branch to small_talk",
            routeIntentNode.edges.any { it.toNode == smallTalkNode }
        )
        assertTrue(
            "route_intent must branch to evidence_gathering",
            routeIntentNode.edges.any { it.toNode == evidenceNode }
        )

        // Change 1: the small-talk reply path terminates the run directly (no gates,
        // no synthesis) — the subgraph edge must target nodeFinish.
        assertTrue(
            "small_talk reply path must terminate on nodeFinish",
            smallTalkNode.edges.any { it.toNode == strategy.nodeFinish }
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
            violations = listOf(Violation(ViolationCodes.NO_SECTIONS, SafetyValidator.NO_SECTIONS_REASON, ViolationTarget.PHASE_1)),
            targetPhase = ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE,
            draft = ""
        )
        assertEquals("Missing section evidence", remediationEvidence.reason)
        assertEquals("No section text", remediationEvidence.evidence)
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE, remediationEvidence.targetPhase)
        assertEquals(1, remediationEvidence.violations.size)
        assertEquals(ViolationCodes.NO_SECTIONS, remediationEvidence.violations.first().code)

        val remediationSynthesis = ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation(
            reason = "Unverified dosage",
            evidence = "Standard dose 5 mg",
            targetPhase = ClinicalAgentStrategy.RemediationTarget.PHASE_2_SYNTHESIS,
            violations = listOf(Violation(ViolationCodes.UNVERIFIED_QTY, "Unverified 750 mg.", ViolationTarget.PHASE_2)),
            draft = "Take 750 mg."
        )
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_2_SYNTHESIS, remediationSynthesis.targetPhase)
        assertEquals("Take 750 mg.", remediationSynthesis.draft)
        assertEquals(ViolationCodes.UNVERIFIED_QTY, remediationSynthesis.violations.first().code)

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
        // Change-1 exact rendering (camelCase tool names per the Kotlin registry).
        assertTrue(prompt.startsWith("SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED."))
        assertTrue(prompt.contains("Your last message was text, but no UpToDate section text or table has been retrieved yet, so it cannot be used as an answer."))
        assertTrue(prompt.contains("Write no text. Respond ONLY with tool calls:"))
        assertTrue(prompt.contains("1. searchTopics(query): one condition or drug per call, 1-3 words. Use parallel calls for several entities."))
        assertTrue(prompt.contains("2. getTopicOverview(topicId): use a topicId returned by searchTopics, copied exactly."))
        assertTrue(prompt.contains("3. getTopicSectionsText(topicId, sectionIds): use section ids copied exactly from the overview, or getGraphicContent(graphicId) for a dosing table."))
        assertTrue(prompt.endsWith("You will write the answer after the section text is in context."))
        // The loose SYSTEM NOTICE prefix keeps the format_synthesis passthrough predicate working.
        assertTrue(prompt.startsWith("SYSTEM NOTICE"))
    }

    @Test
    fun `buildEvidenceGatePausePrompt renders the exact Change-2 Phase-1 template`() {
        val prompt = SystemPrompt.buildEvidenceGatePausePrompt("")
        assertTrue(prompt.startsWith("SYSTEM NOTICE — EVIDENCE GATE PAUSE:"))
        assertTrue(prompt.contains("Your previous turn ended without retrieving full clinical section text or table content."))
        assertTrue(prompt.contains("RETRIEVED SO FAR:"))
        // Blank evidence summary falls back to the deterministic placeholder.
        assertTrue(prompt.contains("Nothing has been retrieved yet."))
        assertTrue(prompt.contains("MANDATORY NEXT STEP (tool calls only, no text):"))
        assertTrue(prompt.contains("Call getTopicSectionsText(topicId, sectionIds)"))
        assertTrue(prompt.contains("then getTopicOverview(topicId), then getTopicSectionsText(topicId, sectionIds)."))
        assertTrue(prompt.endsWith("Reply with text only after the section text is in context."))
    }

    @Test
    fun `buildEvidenceGatePausePrompt renders the retrieved summary verbatim when present`() {
        val prompt = SystemPrompt.buildEvidenceGatePausePrompt("Section [H1] (Dosing):\nCeftriaxone 1,000 mg IV daily.")
        assertTrue(prompt.contains("RETRIEVED SO FAR:\nSection [H1] (Dosing):\nCeftriaxone 1,000 mg IV daily."))
        assertFalse(prompt.contains("Nothing has been retrieved yet."))
    }

    @Test
    fun `buildRevisionPrompt renders the exact Change-2 Phase-2 template`() {
        val prompt = SystemPrompt.buildRevisionPrompt(
            violations = listOf(
                Violation(ViolationCodes.UNVERIFIED_QTY, "Unverified clinical quantities found in response: 750 mg.", ViolationTarget.PHASE_2),
                Violation(ViolationCodes.VISUAL, "Answer describes visual details.", ViolationTarget.PHASE_2)
            ),
            draft = "Take 750 mg and the image shows a mass.",
            evidenceSummary = "Section [S1] (Dosing):\nStandard dose is 250 mg daily."
        )
        assertTrue(prompt.startsWith("SYSTEM NOTICE — SAFETY REVISION (NO TOOLS ACTIVE):"))
        assertTrue(prompt.contains("Your previous draft failed deterministic verification. Rewrite it completely."))
        assertTrue(prompt.contains("<violations>\n[UNVERIFIED_QTY] Unverified clinical quantities found in response: 750 mg.\n[VISUAL] Answer describes visual details.\n</violations>"))
        assertTrue(prompt.contains("<previous_draft>\nTake 750 mg and the image shows a mass.\n</previous_draft>"))
        assertTrue(prompt.contains("<retrieved_evidence>\nSection [S1] (Dosing):\nStandard dose is 250 mg daily.\n</retrieved_evidence>"))
        assertTrue(prompt.contains("[UNVERIFIED_QTY] Replace each rejected quantity with the exact string from `<retrieved_evidence>`, or delete it and write \"Not specified in retrieved UpToDate sections.\""))
        assertTrue(prompt.contains("[VISUAL] Do not describe images, scans, figures or tracings. Name the topic or table instead."))
        assertTrue(prompt.contains("[BLANK_WITH_EVIDENCE] Write the complete answer now from `<retrieved_evidence>`."))
        assertTrue(prompt.endsWith("5. Output the complete answer in the required structure: bolded BLUF first, then only the headings the evidence fills — plain text, no links, no sources list."))
    }

    @Test
    fun `buildRevisionPrompt renders the none-placeholder for a blank draft`() {
        val prompt = SystemPrompt.buildRevisionPrompt(
            violations = listOf(Violation(ViolationCodes.BLANK_NO_EVIDENCE, "No clinical response was generated. Evidence retrieval is required.", ViolationTarget.PHASE_1)),
            draft = "",
            evidenceSummary = "Nothing has been retrieved yet."
        )
        assertTrue(prompt.contains("<previous_draft>\n(none: no answer was produced)\n</previous_draft>"))
    }

    @Test
    fun `typed buildCorrectionPrompt dispatches Phase-1 codes to the evidence gate template and others to revision`() {
        // NO_TOOLS / NO_SECTIONS / BLANK_NO_EVIDENCE / NO_TOPICS_FOUND → Phase-1 template.
        for (code in ViolationCodes.PHASE_1_TEMPLATE_CODES) {
            val prompt = SystemPrompt.buildCorrectionPrompt(
                violations = listOf(Violation(code, "detail for $code", ViolationTarget.PHASE_1)),
                evidenceSummary = "",
                draft = "draft text"
            )
            assertTrue("Code $code must render EVIDENCE GATE PAUSE", prompt.contains("EVIDENCE GATE PAUSE"))
            assertFalse("Code $code must not render the revision template", prompt.contains("SAFETY REVISION"))
            assertFalse("Phase-1 template must not carry the draft block", prompt.contains("<previous_draft>"))
        }

        // PHASE_2 targeted codes → revision template.
        for (code in listOf(ViolationCodes.UNVERIFIED_QTY, ViolationCodes.VISUAL, ViolationCodes.BLANK_WITH_EVIDENCE)) {
            val prompt = SystemPrompt.buildCorrectionPrompt(
                violations = listOf(Violation(code, "detail for $code", ViolationTarget.PHASE_2)),
                evidenceSummary = "Section [S1] (Dosing):\nStandard dose is 250 mg daily.",
                draft = "Take 750 mg."
            )
            assertTrue("Code $code must render SAFETY REVISION", prompt.contains("SAFETY REVISION"))
            assertTrue("Code $code revision must carry [CODE] detail line", prompt.contains("[$code] detail for $code"))
            assertTrue("Code $code revision must carry previous_draft", prompt.contains("<previous_draft>\nTake 750 mg.\n</previous_draft>"))
            assertFalse("Code $code must not render the Phase-1 template", prompt.contains("EVIDENCE GATE PAUSE"))
        }
    }

    @Test
    fun `buildCorrectionPrompt routes by violation codes to Phase 1 and Phase 2 templates`() {
        val missingPrompt = SystemPrompt.buildCorrectionPrompt(
            violations = listOf(Violation(ViolationCodes.NO_SECTIONS, "Answer requires section or table content retrieval.", ViolationTarget.PHASE_1)),
            evidenceSummary = "No section text was successfully fetched in the prior turn."
        )
        assertTrue(missingPrompt.contains("EVIDENCE GATE PAUSE"))
        assertTrue(missingPrompt.contains("RETRIEVED SO FAR"))

        val unverifiedPrompt = SystemPrompt.buildCorrectionPrompt(
            violations = listOf(Violation(ViolationCodes.UNVERIFIED_QTY, "Unverified clinical quantities found in response: 750 mg.", ViolationTarget.PHASE_2)),
            evidenceSummary = "Section [H1]: 500 mg."
        )
        assertTrue(unverifiedPrompt.contains("SAFETY REVISION"))
        assertTrue(unverifiedPrompt.contains("UNVERIFIED_QTY"))
    }

    @Test
    fun `synthesis passthrough treats em-dash SYSTEM NOTICE header as remediation, not greeting`() {
        // Header shape produced by SystemPrompt (em-dash form). A strict
        // `startsWith("SYSTEM NOTICE:")` predicate would miss it; Kotlin's loose predicate
        // (formatSynthesisPrompt) matches it, so the passthrough path fires and the
        // correction payload is rendered verbatim with its own directives.
        val notice = "SYSTEM NOTICE — SAFETY REVISION (NO TOOLS ACTIVE): " +
            "[VISUAL] Answer describes visual details."

        val turnContext = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = "getTopicSectionsText",
                    arguments = mapOf("topicId" to "3861", "sectionIds" to "[\"H1\"]"),
                    result = "Dosing evidence",
                    success = true
                )
            ),
            answer = "",
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("3861", "Hypertension", "H1", "Goals", "Goal <130/80 mmHg")
            ),
            userQuestion = "What are diabetes blood pressure goals?"
        )

        // The only way this input survives untransformed is the SYSTEM NOTICE passthrough predicate.
        assertEquals(notice, ClinicalAgentStrategy.formatSynthesisPrompt(notice, "What are diabetes blood pressure goals?", turnContext))
    }

    @Test
    fun `formatSynthesisPrompt anchors CLINICAL INQUIRY to the ORIGINAL user question`() {
        // Change 1: the synthesis directive renders the ORIGINAL question captured at
        // route_intent, never the raw Phase-1 answer or the interception notice.
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = "getTopicSectionsText",
                    arguments = mapOf("topicId" to "3861", "sectionIds" to "[\"H1\"]"),
                    result = "Goal blood pressure is <130/80 mmHg",
                    success = true
                )
            ),
            answer = "Target BP is <130/80 mmHg per protocol.",
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("3861", "Hypertension", "H1", "Goals", "Goal <130/80 mmHg")
            ),
            userQuestion = "What are diabetes blood pressure goals?"
        )

        val prompt = ClinicalAgentStrategy.formatSynthesisPrompt(
            pendingInput = "Target BP is <130/80 mmHg per protocol.",
            userQuestion = "What are diabetes blood pressure goals?",
            turnContext = turnContext
        )

        assertTrue(prompt.contains("CLINICAL SYNTHESIS DIRECTIVE (CLOSED-BOOK — NO TOOLS ACTIVE)"))
        assertTrue(prompt.contains("CLINICAL INQUIRY:\nWhat are diabetes blood pressure goals?"))
        // The Phase-1 raw answer text must NOT be embedded as the inquiry anchor.
        assertFalse(prompt.contains("CLINICAL INQUIRY:\nTarget BP is <130/80 mmHg per protocol."))
        assertFalse(prompt.startsWith("SYSTEM NOTICE"))
    }

    @Test
    fun `premature interception condition logic is content-blind and evidence-driven after Change 1`() {
        val accumulator = TurnContextAccumulator()
        accumulator.setUserQuestion("What are diabetes blood pressure goals?")

        // 1. No tools or evidence -> ANY text is premature (clinical or otherwise).
        assertTrue(
            "Clinical response before tools or evidence must be premature",
            ClinicalAgentStrategy.isPrematureTurn(accumulator, 0, 2)
        )
        assertTrue(
            "Conversational-feeling text without tools or evidence is premature too (routing owns small talk)",
            ClinicalAgentStrategy.isPrematureTurn(accumulator, 0, 2)
        )

        // 2. Search-only tool call with NO section evidence -> still premature (the
        //    exact Change-2 (c) premise: quantity-free text after tools ran).
        accumulator.onToolCallCompleted(
            toolCallId = "call-0",
            toolName = "searchTopics",
            result = """{"query":"hypertension","results":[{"id":"3726","title":"Hypertension"}]}""",
            success = true,
            toolArgs = """{"query":"hypertension"}"""
        )
        assertTrue(
            "Text without fetched section evidence remains premature even after tools ran",
            ClinicalAgentStrategy.isPrematureTurn(accumulator, 0, 2)
        )

        // 3. Evidence fetched (sections present) -> NOT premature.
        accumulator.onToolCallCompleted(
            toolCallId = "call-1",
            toolName = "getTopicSectionsText",
            result = """{"topicId":"3861","topicTitle":"Hypertension in adults","sectionTitles":{"H1":"Goals"},"markdown":"Goal blood pressure is <130/80 mmHg."}""",
            success = true,
            toolArgs = """{"topicId":"3861","sectionIds":["H1"]}"""
        )
        assertFalse(
            "Clinical response with fetched section evidence must never be premature",
            ClinicalAgentStrategy.isPrematureTurn(accumulator, 0, 2)
        )

        // 4. Retry budget exhausted -> NOT premature (flows to Gate 1 for remediation/block).
        val freshAccumulator = TurnContextAccumulator()
        freshAccumulator.setUserQuestion("What are diabetes blood pressure goals?")
        assertFalse(
            "Exhausted premature retries must not be intercepted as premature",
            ClinicalAgentStrategy.isPrematureTurn(freshAccumulator, 2, 2)
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

        // Python parity (agent.py validate_safety): the exhausted path forwards the ORIGINAL
        // blank-guard string, not a reworded "after evidence gathering" variant.
        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.Blocked)
        val blocked = evaluation as ClinicalAgentStrategy.SafetyEvaluation.Blocked
        assertEquals("No final clinical answer was synthesized; render the gathered evidence", blocked.reason)
    }

    @Test
    fun `evaluateSafetyTurn emits the blank-guard no-evidence string at exhaustion`() {
        val validator = SafetyValidator()
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = emptyList(),
            answer = "",
            fetchedSections = emptyList(),
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
        assertEquals("No clinical response was generated. Evidence retrieval is required.", blocked.reason)
    }

    @Test
    fun `evaluateSafetyTurn forwards validator reason verbatim at exhaustion for non-blank answers`() {
        val validator = SafetyValidator()
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = "getTopicSectionsText",
                    arguments = mapOf("topicId" to "100", "sectionIds" to "[\"S1\"]"),
                    result = "Standard dose is 250 mg daily.",
                    success = true
                )
            ),
            answer = "Take 999 mg daily.",
            toolResults = listOf("Standard dose is 250 mg daily."),
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("100", "Drug Therapy", "S1", "Dosing", "Standard dose is 250 mg daily.")
            ),
            userQuestion = "What is the dosage?"
        )

        val evaluation = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = turnContext.answer,
            turnContext = turnContext,
            safetyValidator = validator,
            retryCount = 2,
            maxSafetyRetries = 2,
            onPrepareRetry = { error("Should not prepare retry when budget exhausted") }
        )

        // The validator's own blocked reason (not a synthesized variant) reaches the terminal block.
        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.Blocked)
        val blocked = evaluation as ClinicalAgentStrategy.SafetyEvaluation.Blocked
        assertTrue(blocked.reason.contains("Unverified clinical quantities found in response: 999 mg"))
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
        // Change 2: the remediation carries the typed violations and the rejected draft.
        assertEquals(listOf(ViolationCodes.UNVERIFIED_QTY), rem.violations.map { it.code })
        assertEquals("Take 999 mg daily.", rem.draft)
        assertTrue(retryCalled)
    }

    // ══════════════════════════════════════════════════════════════════
    // Change 2: Dual Gate 1 (evaluateEvidenceGate) + NO_TOPICS_FOUND shape
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `evaluateEvidenceGate passes a turn with real section evidence`() {
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = "getTopicSectionsText",
                    arguments = mapOf("topicId" to "3861", "sectionIds" to """["H1"]"""),
                    result = "Goal blood pressure is <130/80 mmHg.",
                    success = true
                )
            ),
            answer = "Goal blood pressure is <130/80 mmHg.",
            fetchedSections = listOf(
                SafetyValidator.FetchedSection("3861", "Hypertension", "H1", "Goals", "Goal <130/80 mmHg.")
            ),
            userQuestion = "What are diabetes blood pressure goals?"
        )

        var preparedRetry = false
        val evaluation = ClinicalAgentStrategy.evaluateEvidenceGate(
            pendingAnswer = turnContext.answer,
            turnContext = turnContext,
            safetyValidator = SafetyValidator(),
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { preparedRetry = true }
        )

        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.Valid)
        assertEquals("Goal blood pressure is <130/80 mmHg.", (evaluation as ClinicalAgentStrategy.SafetyEvaluation.Valid).content)
        assertFalse(preparedRetry)
    }

    @Test
    fun `evaluateEvidenceGate emits NO_TOOLS when no tool calls ran and never bypasses on answer content`() {
        // Change 1: the former greeting auto-pass is deleted — ANY answer with zero tools
        // is a NO_TOOLS violation regardless of how non-clinical it reads.
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = emptyList(),
            answer = "Hello! How can I assist you today?",
            userQuestion = "Hello"
        )

        val evaluation = ClinicalAgentStrategy.evaluateEvidenceGate(
            pendingAnswer = turnContext.answer,
            turnContext = turnContext,
            safetyValidator = SafetyValidator(),
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { }
        )

        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation)
        val rem = evaluation as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE, rem.targetPhase)
        assertEquals(listOf(ViolationCodes.NO_TOOLS), rem.violations.map { it.code })
        assertEquals(SafetyValidator.NO_TOOLS_REASON, rem.reason)
        // Phase-1 placeholder when nothing was retrieved.
        assertEquals(SystemPrompt.NOTHING_RETRIEVED_PLACEHOLDER, rem.evidence)
    }

    @Test
    fun `evaluateEvidenceGate blocks with the joined reason at exhaustion`() {
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = emptyList(),
            answer = "Hello!",
            userQuestion = "Hello"
        )

        val evaluation = ClinicalAgentStrategy.evaluateEvidenceGate(
            pendingAnswer = turnContext.answer,
            turnContext = turnContext,
            safetyValidator = SafetyValidator(),
            retryCount = 2,
            maxSafetyRetries = 2,
            onPrepareRetry = { error("Should not prepare retry when budget exhausted") }
        )

        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.Blocked)
        assertEquals(SafetyValidator.NO_TOOLS_REASON, (evaluation as ClinicalAgentStrategy.SafetyEvaluation.Blocked).reason)
    }

    @Test
    fun `evaluateSafetyTurn keeps defensive Phase-1 targeting for NO_TOOLS`() {
        val turnContext = SafetyValidator.TurnContext(
            toolCalls = emptyList(),
            answer = "Some answer text without quantities.",
            userQuestion = "question"
        )
        val evaluation = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = turnContext.answer,
            turnContext = turnContext,
            safetyValidator = SafetyValidator(),
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { }
        )
        assertTrue(evaluation is ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation)
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE, (evaluation as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation).targetPhase)
        assertEquals(listOf(ViolationCodes.NO_TOOLS), evaluation.violations.map { it.code })
        assertEquals(turnContext.answer, evaluation.draft)
    }

    @Test
    fun `evaluateSafetyTurn blank guard encodes typed BLANK violations with today's strings`() {
        // With evidence -> BLANK_WITH_EVIDENCE (Phase 2 revision).
        val withEvidenceContext = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("getTopicSectionsText", mapOf("topicId" to "1", "sectionIds" to """["S1"]"""), "text", true)
            ),
            answer = "",
            fetchedSections = listOf(SafetyValidator.FetchedSection("1", "Topic", "S1", "Dosing", "500 mg daily"))
        )
        val phase2 = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = "",
            turnContext = withEvidenceContext,
            safetyValidator = SafetyValidator(),
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { }
        )
        assertTrue(phase2 is ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation)
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_2_SYNTHESIS, (phase2 as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation).targetPhase)
        assertEquals(
            listOf(Violation(ViolationCodes.BLANK_WITH_EVIDENCE, SafetyValidator.BLANK_WITH_EVIDENCE_REASON, ViolationTarget.PHASE_2)),
            phase2.violations
        )
        assertEquals(SafetyValidator.BLANK_WITH_EVIDENCE_REASON, phase2.reason)
        // Blank draft must render the none-placeholder inside the revision template.
        val revisionPrompt = SystemPrompt.buildCorrectionPrompt(phase2.violations, phase2.evidence, phase2.draft)
        assertTrue(revisionPrompt.contains("<previous_draft>\n(none: no answer was produced)\n</previous_draft>"))

        // Without evidence -> BLANK_NO_EVIDENCE (Phase 1 gate pause).
        val withoutEvidenceContext = SafetyValidator.TurnContext(
            toolCalls = listOf(
                SafetyValidator.ToolCallRecord("searchTopics", mapOf("query" to "x"), """{"query":"x","results":[{"id":"1","title":"Topic"}]}""", true)
            ),
            answer = ""
        )
        val phase1 = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = "",
            turnContext = withoutEvidenceContext,
            safetyValidator = SafetyValidator(),
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { }
        )
        assertTrue(phase1 is ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation)
        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE, (phase1 as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation).targetPhase)
        assertEquals(SafetyValidator.BLANK_NO_EVIDENCE_REASON, phase1.reason)
        // The typed dispatcher renders the Phase-1 template for this code.
        assertTrue(SystemPrompt.buildCorrectionPrompt(phase1.violations, phase1.evidence).contains("EVIDENCE GATE PAUSE"))
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

    private fun seedPhase2QuantityTurn(): TurnContextAccumulator {
        val accumulator = TurnContextAccumulator()
        accumulator.onToolCallStarting("seed-overview", """{"topic_id":"100"}""")
        accumulator.onToolCallCompleted(
            toolCallId = "seed-overview",
            toolName = "getTopicOverview",
            result = """{"topicId":"100","title":"Drug 100","sections":[{"id":"H1","title":"Dosing"},{"id":"F1","title":"Renal dosage adjustment"}]}""",
            success = true
        )
        accumulator.onToolCallStarting("seed-sections", """{"topic_id":"100","sectionIds":["H1"]}""")
        accumulator.onToolCallCompleted(
            toolCallId = "seed-sections",
            toolName = "getTopicSectionsText",
            result = """{"topicId":"100","topicTitle":"Drug 100","sectionTitles":{"H1":"Dosing"},"markdown":"General dosing guidance without a verifiable dose."}""",
            success = true
        )
        return accumulator
    }

    @Test
    fun `Phase 2 remediation renders safety revision prompt with evidence`() {
        val accumulator = seedPhase2QuantityTurn()
        val draft = "For severe renal impairment, use 500 mg intravenously every 24 hours."

        val rejected = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = draft,
            turnContext = accumulator.buildTurnContext(draft),
            safetyValidator = SafetyValidator(),
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { }
        ) as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation

        val prompt = SystemPrompt.buildRevisionPrompt(
            violations = rejected.violations,
            draft = rejected.draft,
            evidenceSummary = rejected.evidence
        )

        assertTrue(prompt.startsWith("SYSTEM NOTICE — SAFETY REVISION (NO TOOLS ACTIVE):"))
        assertTrue(prompt.contains("<previous_draft>\n$draft\n</previous_draft>"))
    }

    @Test
    fun `formatTerminalBlockedMessage keeps the marker and tells the clinician what to do`() {
        val result = ClinicalAgentStrategy.formatTerminalBlockedMessage(
            blockedReason = "Documented patient allergy contraindication: amoxicillin"
        )
        assertTrue(
            "The stable marker must stay first so a blocked run is detectable",
            result.startsWith(ClinicalAgentStrategy.BLOCKED_MARKER)
        )
        assertTrue(result.contains("Documented patient allergy contraindication: amoxicillin"))
        assertTrue("The message must say the draft is withheld", result.contains("withheld"))
        assertTrue("The message must point at the attached sources", result.contains("attached below as references"))
        assertFalse(result.contains("Clinical Evidence Excerpt:"))
        assertFalse(result.contains("Patient Reminder:"))
    }

    @Test
    fun `second quantity failure escalates to a tool-enabled retrieval round`() {
        // A hallucinated dose cannot be fixed by a tool-free rewrite: the value simply is not
        // in the retrieved text, so the second failure must hand the tools back.
        val accumulator = seedPhase2QuantityTurn()
        val draft = "Use 500 mg intravenously every 24 hours."

        val first = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = draft,
            turnContext = accumulator.buildTurnContext(draft),
            safetyValidator = SafetyValidator(),
            retryCount = 0,
            maxSafetyRetries = 2,
            onPrepareRetry = { }
        ) as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation

        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_2_SYNTHESIS, first.targetPhase)
        assertEquals(listOf(ViolationCodes.UNVERIFIED_QTY), first.violations.map { it.code })

        val second = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = draft,
            turnContext = accumulator.buildTurnContext(draft),
            safetyValidator = SafetyValidator(),
            retryCount = 1,
            maxSafetyRetries = 2,
            onPrepareRetry = { }
        ) as ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation

        assertEquals(ClinicalAgentStrategy.RemediationTarget.PHASE_1_EVIDENCE, second.targetPhase)
        assertEquals(listOf(ViolationCodes.UNRETRIEVED_DOSES), second.violations.map { it.code })
        assertEquals(ViolationTarget.PHASE_1, second.violations.first().target)
        assertTrue("The offending values stay named", second.violations.first().detail.contains("500 mg"))

        val prompt = SystemPrompt.buildCorrectionPrompt(second.violations, second.evidence, second.draft)
        assertTrue(prompt.startsWith("SYSTEM NOTICE — MISSING EVIDENCE FOR STATED QUANTITIES:"))
        assertTrue(prompt.contains("searchTopics("))
        assertTrue(prompt.contains("getTopicSectionsText(topicId, sectionIds)"))
        assertFalse("Escalation must not re-offer a tool-free rewrite", prompt.contains("SAFETY REVISION"))

        // The budget still ends the turn on the third failure.
        val third = ClinicalAgentStrategy.evaluateSafetyTurn(
            cleanText = draft,
            turnContext = accumulator.buildTurnContext(draft),
            safetyValidator = SafetyValidator(),
            retryCount = 2,
            maxSafetyRetries = 2,
            onPrepareRetry = { }
        )
        assertTrue(third is ClinicalAgentStrategy.SafetyEvaluation.Blocked)
    }
}

