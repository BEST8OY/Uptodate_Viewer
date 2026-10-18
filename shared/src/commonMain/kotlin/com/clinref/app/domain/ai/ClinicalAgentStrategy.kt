package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import com.clinref.shared.platform.PlatformLogger as Log

/**
 * 3-Phase Multi-Stage Clinical Inquiry Strategy Graph.
 *
 * Operational Phases:
 * 1. Phase 1: Evidence Gathering & Tool Retrieval ([ClinicalState.EvidenceGathering])
 * 2. Phase 2: Clinical Synthesis ([ClinicalState.ClinicalSynthesis])
 * 3. Phase 3: In-Graph Safety Verification & Remediation ([ClinicalState.SafetyVerification])
 */
object ClinicalAgentStrategy {

    private const val TAG = "ClinicalAgentStrategy"
    const val STRATEGY_AUTONOMOUS = "clinical-autonomous"
    const val STRATEGY_INQUIRY = "clinical-inquiry"

    /**
     * Parity with Python `agent.max_tool_retries`: hard cap on Phase-1 execute_tools
     * passes this turn. When the budget is exhausted, the graph routes out of evidence
     * gathering straight to [nodeValidateSafety] (with an empty pending answer, exactly
     * like Python's `after_tools` forced `validate_safety`) instead of letting the model
     * keep calling individual tools until Koog's global `maxAgentIterations` aborts the
     * whole run (`AIAgentMaxNumberOfIterationsReachedException` — each LLM/tool node
     * transfer consumes one iteration, so 30 node-level iterations ≈ ~10 tool rounds).
     */
    const val MAX_TOOL_ROUNDS = 24

    /** Context-hygiene truncation thresholds, mirrored from Python `after_tools`. */
    private const val SEARCH_TOOL_NAME = "search_topics"
    private const val SEARCH_RESULT_FULL_GATE = 300
    private const val SEARCH_RESULT_KEEP = 250
    private const val SEARCH_RESULT_SUFFIX = "... [truncated search results]"

    enum class RemediationTarget {
        /** Missing database section or table content -> route back to Phase 1 retrieval */
        PHASE_1_EVIDENCE,

        /** Unverified figures/dosages detected with evidence present -> route to Phase 2 synthesis */
        PHASE_2_SYNTHESIS
    }

    sealed class ClinicalState {
        /**
         * Phase 1: Evidence Gathering & Tool Retrieval.
         * Active while the agent interacts with database tools.
         */
        data class EvidenceGathering(
            val query: String = "",
            val toolRounds: Int = 0,
            val fetchedSectionsCount: Int = 0,
            val graphicCount: Int = 0
        ) : ClinicalState()

        /**
         * Phase 2: Clinical Synthesis.
         * Active while generating and formatting clinical text strictly grounded in evidence.
         */
        data class ClinicalSynthesis(
            val rawResponse: String,
            val cleanedResponse: String,
            val turnContext: SafetyValidator.TurnContext
        ) : ClinicalState()

        /**
         * Phase 3: In-Graph Safety Verification & Remediation.
         * Active while evaluating Tier 1 and Tier 2 clinical safety checks.
         */
        data class SafetyVerification(
            val evaluation: SafetyEvaluation,
            val retryCount: Int = 0,
            val targetPhase: RemediationTarget = RemediationTarget.PHASE_1_EVIDENCE
        ) : ClinicalState()
    }

    sealed class SafetyEvaluation {
        data class Valid(val content: String) : SafetyEvaluation()
        data class NeedsRemediation(
            val reason: String,
            val evidence: String,
            val targetPhase: RemediationTarget = RemediationTarget.PHASE_1_EVIDENCE
        ) : SafetyEvaluation()
        data class Blocked(val reason: String) : SafetyEvaluation()
    }

    /**
     * Creates an autonomous self-healing clinical retrieval strategy graph.
     * Incorporates native in-graph safety validation and multi-turn self-correction loops.
     */
    fun create(
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2,
        strategyName: String = STRATEGY_AUTONOMOUS
    ): AIAgentGraphStrategy<String, String> = createAutonomous(safetyValidator, accumulator, maxSafetyRetries, strategyName)

    fun createInquiry(
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2
    ): AIAgentGraphStrategy<String, String> = createAutonomous(safetyValidator, accumulator, maxSafetyRetries, STRATEGY_INQUIRY)

    fun createAutonomous(
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2,
        strategyName: String = STRATEGY_AUTONOMOUS
    ): AIAgentGraphStrategy<String, String> = strategy(strategyName) {
        var retryCount = 0
        var prematureInterceptions = 0
        var toolRounds = 0
        var currentState: ClinicalState = ClinicalState.EvidenceGathering()

        // ── Phase 1: Evidence Gathering & Tool Retrieval Nodes ──────────────────
        val nodeSendInput by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResults()

        /**
         * Accounting gate between each tool execution and its follow-up LLM call.
         *
         * Mirrors Python `agent.after_tools` / `execute_tools`:
         *  - increments the per-turn tool-round counter (one execute_tools pass = one round);
         *  - when [MAX_TOOL_ROUNDS] is reached the outgoing forced edge bypasses the LLM
         *    entirely and routes to safety validation, so retrieval always terminates;
         *  - otherwise performs context hygiene: once section evidence exists, stale
         *    `search_topics` result payloads (>300 chars) are truncated in history so
         *    small models are not buried under accumulated retrieval bulk.
         */
        val nodeToolRoundBoundary by node<ReceivedToolResults, ReceivedToolResults>("tool_round_boundary") { toolResults ->
            toolRounds++
            val context = accumulator.buildTurnContext("")
            currentState = ClinicalState.EvidenceGathering(
                toolRounds = toolRounds,
                fetchedSectionsCount = context.fetchedSections.size,
                graphicCount = context.graphicIds.size
            )

            if (toolRounds >= MAX_TOOL_ROUNDS) {
                Log.w(TAG, "Tool round budget exhausted ($toolRounds >= $MAX_TOOL_ROUNDS); forcing safety validation")
            } else if (context.fetchedSections.isNotEmpty()) {
                llm.writeSession {
                    rewritePrompt { prompt ->
                        if (prompt.messages.size <= 4) prompt
                        else prompt.withMessages { messages ->
                            messages.map { message -> truncateStaleSearchResults(message) }
                        }
                    }
                }
            }
            toolResults
        }

        val nodeInterceptPremature by node<String, String>("intercept_premature") { _ ->
            prematureInterceptions++
            val context = accumulator.buildTurnContext("")
            currentState = ClinicalState.EvidenceGathering(
                toolRounds = prematureInterceptions,
                fetchedSectionsCount = context.fetchedSections.size,
                graphicCount = context.graphicIds.size
            )
            Log.w(TAG, "Premature response intercepted (attempt $prematureInterceptions). State: $currentState. Redirecting to retrieval.")
            // Mirrors Python build_premature_interception_prompt(retry counts): the directive is static;
            // per-attempt accounting lives in the graph counters, not the prompt text.
            SystemPrompt.buildPrematureInterceptionPrompt()
        }

        // ── Phase 2: Clinical Synthesis Node ────────────────────────────────────
        val nodeClinicalSynthesis by node<String, String>("clinical_synthesis") { rawText ->
            val cleanText = ReasoningTokenSanitizer.stripReasoning(rawText)
            val turnContext = accumulator.buildTurnContext(cleanText)
            currentState = ClinicalState.ClinicalSynthesis(
                rawResponse = rawText,
                cleanedResponse = cleanText,
                turnContext = turnContext
            )
            Log.d(TAG, "Phase 2 Clinical Synthesis entered: state=$currentState")
            cleanText
        }

        // ── Phase 3: Safety Verification & Remediation Nodes ────────────────────
        val nodeValidateSafety by node<String, SafetyEvaluation>("validate_safety") { text ->
            val cleanText = ReasoningTokenSanitizer.stripReasoning(text)
            val turnContext = accumulator.buildTurnContext(cleanText)

            // Blank-answer guard: a budget-forced (tool_round_boundary) exit or any
            // interrupted turn that gathered evidence but never synthesized MUST NOT
            // complete the turn with an empty answer. Intentional divergence from
            // Python validate_safety (which passes a blank answer through): steer
            // into Phase 2 synthesis so the clinician sees the gathered evidence.
            val evaluation = if (
                cleanText.isBlank() &&
                (turnContext.fetchedSections.isNotEmpty() || turnContext.graphicIds.isNotEmpty())
            ) {
                if (retryCount < maxSafetyRetries) {
                    retryCount++
                    val snapshot = accumulator.snapshotForCorrection()
                    accumulator.prepareForCorrection()
                    SafetyEvaluation.NeedsRemediation(
                        reason = "No final clinical answer was synthesized; render the gathered evidence",
                        evidence = evidenceSummaryFrom(snapshot),
                        targetPhase = RemediationTarget.PHASE_2_SYNTHESIS
                    )
                } else {
                    SafetyEvaluation.Blocked(
                        reason = "No final clinical answer was synthesized after evidence gathering"
                    )
                }
            } else {
                val validation = safetyValidator.validate(turnContext)

                if (validation.passed) {
                    SafetyEvaluation.Valid(cleanText)
                } else if (retryCount < maxSafetyRetries) {
                    retryCount++
                    val snapshot = accumulator.snapshotForCorrection()
                    accumulator.prepareForCorrection()
                    val evidenceSummary = evidenceSummaryFrom(snapshot)

                    // Route back to Phase 1 whenever NO section evidence exists this turn:
                    //   - Rule 0 (no tools executed) and Rule 1 (no successful section/graphic fetch) both imply
                    //     fetchedSections.isEmpty(), so remediation routing must NOT depend on human-readable
                    //     blockedReason strings (byte-syncing prompts with routing silently corrupts the graph).
                    //   - Anything else (unverified quantities, graphic interpretation, patient-bounds) routes
                    //     back to Phase 2 for re-synthesis with the correction prompt.
                    val targetPhase = if (turnContext.fetchedSections.isEmpty() && turnContext.graphicIds.isEmpty()) {
                        RemediationTarget.PHASE_1_EVIDENCE
                    } else {
                        RemediationTarget.PHASE_2_SYNTHESIS
                    }

                    SafetyEvaluation.NeedsRemediation(
                        reason = validation.blockedReason ?: "Clinical verification failed",
                        evidence = evidenceSummary,
                        targetPhase = targetPhase
                    )
                } else {
                    SafetyEvaluation.Blocked(
                        reason = validation.blockedReason ?: "Clinical verification blocked after retries"
                    )
                }
            }

            currentState = ClinicalState.SafetyVerification(
                evaluation = evaluation,
                retryCount = retryCount,
                targetPhase = (evaluation as? SafetyEvaluation.NeedsRemediation)?.targetPhase ?: RemediationTarget.PHASE_1_EVIDENCE
            )
            Log.d(TAG, "Phase 3 Safety Verification evaluated: state=$currentState")
            evaluation
        }

        val nodeRemediate by node<SafetyEvaluation.NeedsRemediation, String>("build_remediation") { rem ->
            Log.i(TAG, "Remediating toward target phase: ${rem.targetPhase}")
            SystemPrompt.buildCorrectionPrompt(rem.reason, rem.evidence)
        }

        val nodeSafetyBlock by node<SafetyEvaluation.Blocked, String>("safety_block") { blocked ->
            "Clinical Response Verification Blocked: ${blocked.reason}"
        }

        // ── Premature Detection Helper ──────────────────────────────────────────
        val isPremature: (MessagePart.Text) -> Boolean = { textPart ->
            val context = accumulator.buildTurnContext(textPart.text)
            if (SafetyValidator.isNonClinicalResponse(context)) {
                false
            } else {
                (context.fetchedSections.isEmpty() && context.graphicIds.isEmpty()) &&
                    (prematureInterceptions < maxSafetyRetries)
            }
        }

        // ── 1. Graph Entry ──────────────────────────────────────────────────────
        edge(nodeStart forwardTo nodeSendInput)

        // ── 2. Phase 1 LLM Output Routing ───────────────────────────────────────
        edge(nodeSendInput forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendInput forwardTo nodeInterceptPremature onTextMessage { textPart -> isPremature(textPart) })
        edge(nodeSendInput forwardTo nodeClinicalSynthesis onTextMessage { textPart -> !isPremature(textPart) })

        // ── 3. Phase 1 Tool Loop Routing ────────────────────────────────────────
        edge(nodeExecuteTool forwardTo nodeToolRoundBoundary)
        // Python parity: budget exhausted after execution -> skip the LLM and force
        // the safety gate with an empty pending answer (matches _extract_final_answer()
        // returning "" when no plain-text assistant message exists in history).
        edge(
            (nodeToolRoundBoundary forwardTo nodeValidateSafety)
                onCondition { toolRounds >= MAX_TOOL_ROUNDS }
                transformed { "" }
        )
        edge(
            (nodeToolRoundBoundary forwardTo nodeSendToolResult)
                onCondition { toolRounds < MAX_TOOL_ROUNDS }
        )
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeInterceptPremature onTextMessage { textPart -> isPremature(textPart) })
        edge(nodeSendToolResult forwardTo nodeClinicalSynthesis onTextMessage { textPart -> !isPremature(textPart) })

        // Premature Interception loopback to Phase 1
        edge(nodeInterceptPremature forwardTo nodeSendInput)

        // ── 4. Phase 2 -> Phase 3 Transition ────────────────────────────────────
        edge(nodeClinicalSynthesis forwardTo nodeValidateSafety)

        // ── 5. Phase 3 Safety Verification Routing ──────────────────────────────
        edge(
            (nodeValidateSafety forwardTo nodeFinish)
                onCondition { it is SafetyEvaluation.Valid }
                transformed { (it as SafetyEvaluation.Valid).content }
        )

        edge(
            (nodeValidateSafety forwardTo nodeRemediate)
                onCondition { it is SafetyEvaluation.NeedsRemediation }
                transformed { it as SafetyEvaluation.NeedsRemediation }
        )

        edge(
            (nodeValidateSafety forwardTo nodeSafetyBlock)
                onCondition { it is SafetyEvaluation.Blocked }
                transformed { it as SafetyEvaluation.Blocked }
        )

        // ── 6. Remediation & Block Transitions ──────────────────────────────────
        edge(nodeRemediate forwardTo nodeSendInput)
        edge(nodeSafetyBlock forwardTo nodeFinish)
    }

    /**
     * Evidence digest shared by the blank-answer guard and the validation remediation
     * branch, mirroring Python's evidence summary assembly for correction prompts.
     */
    private fun evidenceSummaryFrom(snapshot: TurnContextAccumulator): String =
        snapshot.buildTurnContext("").fetchedSections
            .joinToString("\n---\n") { sec ->
                if (sec.contentSnippet.isNotBlank()) {
                    "Section [${sec.sectionId}] (${sec.sectionTitle}):\n${sec.contentSnippet}"
                } else {
                    "Section [${sec.sectionId}] (${sec.sectionTitle}) from topic ${sec.topicTitle}"
                }
            }.ifBlank { "No section text was successfully fetched in the prior turn." }

    /**
     * Context hygiene runtime for one LLM-history message, mirroring Python:
     * tool-`search_topics` result records whose textual payload exceeds the gate
     * length are folded down in place. No-op when the message carries no oversized
     * search listing. Returns the original instance when untouched.
     *
     * `internal` so [com.clinref.app.domain.ai.ClinicalAgentStrategyTest] can pin the
     * compaction contract (preserved ids/metadata, untouched parts, no-op semantics)
     * without a scripted LLM harness.
     */
    internal fun truncateStaleSearchResults(message: Message): Message {
        if (message !is Message.User) return message
        var touched = false
        val compacted = message.parts.map { part ->
            val toolResult = part as? MessagePart.Tool.Result ?: return@map part
            if (toolResult.tool != SEARCH_TOOL_NAME || toolResult.output.length <= SEARCH_RESULT_FULL_GATE) {
                part
            } else {
                touched = true
                MessagePart.Tool.Result(
                    id = toolResult.id,
                    tool = toolResult.tool,
                    output = toolResult.output.take(SEARCH_RESULT_KEEP) + SEARCH_RESULT_SUFFIX,
                    isError = toolResult.isError,
                    cacheControl = toolResult.cacheControl
                )
            }
        }
        return if (touched) message.copy(parts = compacted) else message
    }
}
