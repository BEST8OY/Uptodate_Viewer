package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.context.AIAgentGraphContextBase
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.subgraph
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.dsl.extension.ReceivedToolResults
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMCompressHistory
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls
import ai.koog.agents.core.tools.Tool
import ai.koog.agents.features.tokenizer.feature.tokenizer
import ai.koog.prompt.message.Message
import ai.koog.prompt.tokenizer.CachingTokenizer
import ai.koog.prompt.tokenizer.PromptTokenizer
import ai.koog.prompt.tokenizer.SimpleRegexBasedTokenizer
import com.clinref.shared.platform.PlatformLogger as Log

/**
 * Phase-isolated clinical inquiry graph.
 *
 * 1. [evidenceGathering] — retrieval tools bound. Parallel calls, the [MAX_TOOL_ROUNDS] budget,
 *    and premature-text interception live here.
 * 2. [nodeValidateEvidence] — Gate 1. No tool calls, or no captured section/table text, sends the
 *    turn back to retrieval with a corrective prompt that names what is already in context.
 * 3. [nodeCompressHistory] — native history compression near the token threshold
 *    ([preserveMemory = true] keeps patient bounds).
 * 4. [clinicalSynthesis] — closed book: the evidence digest plus the answer-shape contract, with
 *    `tools = emptyList()`, so a synthesis-phase tool call is unrepresentable.
 * 5. [nodeValidateSafety] — Gate 2. Verbatim quantities and graphic interpretation. A failure
 *    revises the draft without tools; a quantity failure that survives one revision escalates
 *    back to retrieval ([ViolationCodes.UNRETRIEVED_DOSES], at most [MAX_EVIDENCE_ESCALATIONS]
 *    times). When the budget is exhausted the turn ends with [BLOCKED_MARKER].
 */
object ClinicalAgentStrategy {

    private const val TAG = "ClinicalAgentStrategy"
    const val STRATEGY_AUTONOMOUS = "clinical-autonomous"

    /**
     * Parity with Python `agent.max_tool_retries`: hard cap on Phase-1 execute_tools
     * passes this turn. When the budget is exhausted, the graph routes out of evidence
     * gathering straight to pre-synthesis validation with an empty pending answer.
     */
    const val MAX_TOOL_ROUNDS = 24

    /**
     * How many times a turn may be sent back to retrieval because its draft stated values
     * that were never retrieved. One escalation is enough to distinguish "the model forgot
     * to fetch the drug topic" from "the model will not stop inventing"; more would just
     * multiply token cost on a slowly converging model.
     */
    const val MAX_EVIDENCE_ESCALATIONS = 1

    /** Stable prefix of the terminal message, used to detect a blocked run post-hoc. */
    const val BLOCKED_MARKER = "Clinical Response Verification Blocked: "

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
         * Pre-synthesis Evidence Validation.
         * Active while checking Rule 0 and Rule 1 grounding.
         */
        data class PreSynthesisValidation(
            val toolRounds: Int = 0,
            val fetchedSectionsCount: Int = 0,
            val graphicCount: Int = 0,
            val isPassed: Boolean = false
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
         * Active while evaluating Rules 2–4 (numerical fidelity, graphic tables, and patient profile bounds).
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
            val targetPhase: RemediationTarget = RemediationTarget.PHASE_1_EVIDENCE,
            val violations: List<Violation> = emptyList(),
            val draft: String = ""
        ) : SafetyEvaluation()

        data class Blocked(
            val reason: String,
            val violations: List<Violation> = emptyList()
        ) : SafetyEvaluation()
    }

    /**
     * Creates an autonomous self-healing clinical retrieval strategy graph.
     * Incorporates native in-graph safety validation and multi-turn self-correction loops.
     */
    fun create(
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2,
        strategyName: String = STRATEGY_AUTONOMOUS,
        onSynthesisStarting: (() -> Unit)? = null
    ): AIAgentGraphStrategy<String, String> = createAutonomous(
        tools = emptyList(),
        safetyValidator = safetyValidator,
        accumulator = accumulator,
        maxSafetyRetries = maxSafetyRetries,
        strategyName = strategyName,
        onSynthesisStarting = onSynthesisStarting
    )

    fun create(
        tools: List<Tool<*, *>>,
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2,
        strategyName: String = STRATEGY_AUTONOMOUS,
        historyCompressionThreshold: Int = 128000,
        onSynthesisStarting: (() -> Unit)? = null
    ): AIAgentGraphStrategy<String, String> = createAutonomous(
        tools = tools,
        safetyValidator = safetyValidator,
        accumulator = accumulator,
        maxSafetyRetries = maxSafetyRetries,
        strategyName = strategyName,
        historyCompressionThreshold = historyCompressionThreshold,
        onSynthesisStarting = onSynthesisStarting
    )


    fun createAutonomous(
        tools: List<Tool<*, *>> = emptyList(),
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2,
        strategyName: String = STRATEGY_AUTONOMOUS,
        historyCompressionThreshold: Int = 128000,
        onSynthesisStarting: (() -> Unit)? = null
    ): AIAgentGraphStrategy<String, String> = strategy(strategyName) {
        var gate1Retries = 0
        var gate2Revisions = 0
        var gate2Escalations = 0
        var prematureInterceptions = 0
        var toolRounds = 0
        // Canonical user question of this run captured at entry routing
        var runUserQuestion = ""
        // Classification computed once at entry; the routing edges reuse it
        var runIsSmallTalk = false

        var currentState: ClinicalState = ClinicalState.EvidenceGathering()

        // ════════════════════════════════════════════════════════════════════════
        // ENTRY ROUTER: route_intent
        // Classifies small-talk vs clinical query before any LLM invocation
        // ════════════════════════════════════════════════════════════════════════
        val routeIntent by node<String, String>("route_intent") { question ->
            runUserQuestion = accumulator.question.ifBlank { question.trim() }
            runIsSmallTalk = QuestionRouter.smallTalk(runUserQuestion)
            Log.d(TAG, "route_intent: smallTalk=$runIsSmallTalk question=$runUserQuestion")
            question
        }

        // ════════════════════════════════════════════════════════════════════════
        // SMALL-TALK PATH
        // Deterministic canned reply — no LLM call, no tools, no gates
        // ════════════════════════════════════════════════════════════════════════
        val nodeSmallTalk by node<String, String>("small_talk") {
            Log.d(TAG, "small_talk: replying with the canned line")
            QuestionRouter.CANNED_FALLBACK_REPLY
        }

        // ════════════════════════════════════════════════════════════════════════
        // SUBGRAPH 1: evidence_gathering
        // Binds retrieval database tools into LLM scope
        // ════════════════════════════════════════════════════════════════════════
        val evidenceGathering by subgraph<String, String>(
            name = "evidence_gathering",
            tools = tools
        ) {
            val retrievalLLM by nodeLLMRequest("retrieval_llm")
            val nodeExecuteTool by nodeExecuteTools(parallel = true)
            val nodeSendToolResult by nodeLLMSendToolResults()

            val nodeToolRoundBoundary by node<ReceivedToolResults, ReceivedToolResults>("tool_round_boundary") { toolResults ->
                toolRounds++
                currentState = ClinicalState.EvidenceGathering(
                    query = accumulator.question,
                    toolRounds = toolRounds,
                    fetchedSectionsCount = accumulator.fetchedSectionsCount,
                    graphicCount = accumulator.graphicCount
                )

                if (toolRounds >= MAX_TOOL_ROUNDS) {
                    Log.w(TAG, "Tool round budget exhausted ($toolRounds >= $MAX_TOOL_ROUNDS); forcing evidence verification")
                }
                toolResults
            }

            val nodeInterceptPremature by node<String, String>("intercept_premature") { _ ->
                prematureInterceptions++
                currentState = ClinicalState.EvidenceGathering(
                    query = accumulator.question,
                    toolRounds = prematureInterceptions,
                    fetchedSectionsCount = accumulator.fetchedSectionsCount,
                    graphicCount = accumulator.graphicCount
                )
                Log.w(TAG, "Premature response intercepted (attempt $prematureInterceptions). State: $currentState. Redirecting to retrieval.")
                SystemPrompt.buildPrematureInterceptionPrompt()
            }

            // Text turn leaves Phase 1 only when section/table evidence exists
            val isPremature: (String) -> Boolean = { _ ->
                isPrematureTurn(accumulator, prematureInterceptions, maxSafetyRetries)
            }

            // Entry -> Retrieval LLM
            edge(nodeStart forwardTo retrievalLLM)

            // Retrieval LLM routing
            edge(retrievalLLM forwardTo nodeExecuteTool onToolCalls { true })
            edge(
                (retrievalLLM forwardTo nodeInterceptPremature)
                    onTextMessage { true }
                    onCondition { isPremature(it) }
            )
            edge(
                (retrievalLLM forwardTo nodeFinish)
                    onTextMessage { true }
                    onCondition { !isPremature(it) }
            )

            // Tool Execution -> Boundary Accounting
            edge(nodeExecuteTool forwardTo nodeToolRoundBoundary)
            edge(
                (nodeToolRoundBoundary forwardTo nodeFinish)
                    onCondition { toolRounds >= MAX_TOOL_ROUNDS }
                    transformed { "" }
            )
            edge(
                (nodeToolRoundBoundary forwardTo nodeSendToolResult)
                    onCondition { toolRounds < MAX_TOOL_ROUNDS }
            )

            // Tool Result Continuation routing
            edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
            edge(
                (nodeSendToolResult forwardTo nodeInterceptPremature)
                    onTextMessage { true }
                    onCondition { isPremature(it) }
            )
            edge(
                (nodeSendToolResult forwardTo nodeFinish)
                    onTextMessage { true }
                    onCondition { !isPremature(it) }
            )

            // Premature Interception loopback
            edge(nodeInterceptPremature forwardTo retrievalLLM)
        }

        // ════════════════════════════════════════════════════════════════════════
        // DUAL GATE 1: validate_evidence (Pre-Synthesis Validation Gate)
        // Evaluates NO_TOOLS and NO_SECTIONS before synthesis
        // ════════════════════════════════════════════════════════════════════════
        val nodeValidateEvidence by node<String, SafetyEvaluation>("validate_evidence") { text ->
            val cleanText = ReasoningTokenSanitizer.stripReasoning(text)
            val context = accumulator.buildTurnContext(cleanText)

            val evaluation = evaluateEvidenceGate(
                pendingAnswer = cleanText,
                turnContext = context,
                safetyValidator = safetyValidator,
                retryCount = gate1Retries,
                maxSafetyRetries = maxSafetyRetries,
                onPrepareRetry = {
                    gate1Retries++
                }
            )

            currentState = ClinicalState.PreSynthesisValidation(
                toolRounds = toolRounds,
                fetchedSectionsCount = accumulator.fetchedSectionsCount,
                graphicCount = accumulator.graphicCount,
                isPassed = evaluation is SafetyEvaluation.Valid
            )
            evaluation
        }

        // ════════════════════════════════════════════════════════════════════════
        // NATIVE HISTORY COMPRESSION NODE
        // ════════════════════════════════════════════════════════════════════════
        val nodeCompressHistory by nodeLLMCompressHistory<String>(
            name = "compress_history",
            strategy = HistoryCompressionStrategy.WholeHistoryMultipleSystemMessages,
            preserveMemory = true
        )

        // ════════════════════════════════════════════════════════════════════════
        // SUBGRAPH 2: clinical_synthesis
        // Closed-book synthesis with tools = emptyList()
        // ════════════════════════════════════════════════════════════════════════
        val clinicalSynthesis by subgraph<String, String>(
            name = "clinical_synthesis",
            tools = emptyList()
        ) {
            val nodeFormatSynthesisPrompt by node<String, String>("format_synthesis") { pendingInput ->
                onSynthesisStarting?.invoke()
                val cleanInput = ReasoningTokenSanitizer.stripReasoning(pendingInput)
                val turnContext = accumulator.buildTurnContext(cleanInput)
                currentState = ClinicalState.ClinicalSynthesis(
                    rawResponse = pendingInput,
                    cleanedResponse = cleanInput,
                    turnContext = turnContext
                )

                // Retain prior conversation history while removing Phase-1 tool turns and the current turn user question
                if (!cleanInput.startsWith("SYSTEM NOTICE")) {
                    try {
                        llm.writeSession {
                            rewritePrompt { prompt ->
                                val filtered = prompt.messages.filter { msg ->
                                    when (msg) {
                                        is Message.System -> true
                                        is Message.User -> {
                                            val isToolResult = msg.parts.any { it is ai.koog.prompt.message.MessagePart.Tool }
                                            val isCurrentTurn = msg.textContent() == runUserQuestion
                                            !isToolResult && !isCurrentTurn
                                        }
                                        is Message.Assistant -> {
                                            val hasToolCall = msg.parts.any { it is ai.koog.prompt.message.MessagePart.Tool }
                                            !hasToolCall
                                        }
                                    }
                                }
                                prompt.copy(messages = filtered)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "rewritePrompt failed; continuing: ${t.message}")
                    }
                }

                formatSynthesisPrompt(
                    pendingInput = cleanInput,
                    userQuestion = runUserQuestion,
                    turnContext = turnContext
                )
            }

            val synthesisLLM by nodeLLMRequest("synthesis_llm")

            val nodeStripReasoning by node<String, String>("strip_reasoning") { rawText ->
                ReasoningTokenSanitizer.stripReasoning(rawText)
            }

            edge(nodeStart forwardTo nodeFormatSynthesisPrompt)
            edge(nodeFormatSynthesisPrompt forwardTo synthesisLLM)
            edge(synthesisLLM forwardTo nodeStripReasoning onTextMessage { true })
            edge(nodeStripReasoning forwardTo nodeFinish)
        }

        // ════════════════════════════════════════════════════════════════════════
        // DUAL GATE 2: validate_safety (Post-Synthesis Safety Validation Gate)
        // ════════════════════════════════════════════════════════════════════════
        val nodeValidateSafety by node<String, SafetyEvaluation>("validate_safety") { text ->
            val cleanText = ReasoningTokenSanitizer.stripReasoning(text)
            val turnContext = accumulator.buildTurnContext(cleanText)

            val evaluation = evaluateSafetyTurn(
                cleanText = cleanText,
                turnContext = turnContext,
                safetyValidator = safetyValidator,
                retryCount = gate2Revisions,
                maxSafetyRetries = maxSafetyRetries,
                allowEscalation = gate2Escalations < MAX_EVIDENCE_ESCALATIONS,
                onPrepareRetry = {
                    gate2Revisions++
                }
            )

            // Escalation is a fresh evidence round, so the new draft earns its own revision
            // budget — otherwise the escalated attempt has no chance to be repaired.
            if (evaluation is SafetyEvaluation.NeedsRemediation &&
                evaluation.violations.any { it.code == ViolationCodes.UNRETRIEVED_DOSES }
            ) {
                gate2Revisions = 0
                gate2Escalations++
                Log.i(TAG, "Escalated to an evidence round (${gate2Escalations}/$MAX_EVIDENCE_ESCALATIONS); revision budget reset")
            }

            currentState = ClinicalState.SafetyVerification(
                evaluation = evaluation,
                retryCount = gate2Revisions,
                targetPhase = (evaluation as? SafetyEvaluation.NeedsRemediation)?.targetPhase ?: RemediationTarget.PHASE_1_EVIDENCE
            )
            Log.d(TAG, "Phase 3 Safety Verification evaluated: state=$currentState")
            evaluation
        }

        // Remediation Prompt Builders
        val nodeRemediate by node<SafetyEvaluation.NeedsRemediation, String>("build_remediation") { rem ->
            Log.i(TAG, "Remediating toward Phase 1 Evidence: ${rem.reason}")
            SystemPrompt.buildCorrectionPrompt(
                violations = rem.violations,
                evidenceSummary = rem.evidence,
                draft = rem.draft
            )
        }

        val nodeRemediateSynthesis by node<SafetyEvaluation.NeedsRemediation, String>("build_remediation_synthesis") { rem ->
            Log.i(TAG, "Remediating toward Phase 2 Synthesis: ${rem.reason}")
            SystemPrompt.buildRevisionPrompt(
                violations = rem.violations,
                draft = rem.draft,
                evidenceSummary = rem.evidence
            )
        }

        val nodeSafetyBlock by node<SafetyEvaluation.Blocked, String>("safety_block") { blocked ->
            formatTerminalBlockedMessage(blocked.reason)
        }

        // ════════════════════════════════════════════════════════════════════════
        // OUTER STRATEGY EDGES
        // ════════════════════════════════════════════════════════════════════════

        // 1. Graph Entry -> Entry Router (route_intent)
        edge(nodeStart forwardTo routeIntent)

        // 2. Entry Router: small-talk vs clinical query (classified once in route_intent)
        edge(
            (routeIntent forwardTo nodeSmallTalk)
                onCondition { runIsSmallTalk }
        )
        edge(
            (routeIntent forwardTo evidenceGathering)
                onCondition { !runIsSmallTalk }
        )

        // 2b. The canned small-talk reply terminates the run
        edge(nodeSmallTalk forwardTo nodeFinish)

        // 3. Evidence Gathering -> Pre-Synthesis Validation Gate
        edge(evidenceGathering forwardTo nodeValidateEvidence)

        // 4. Pre-Synthesis Validation Gate Outgoing Edges
        edge(
            (nodeValidateEvidence forwardTo nodeRemediate)
                onCondition { it is SafetyEvaluation.NeedsRemediation }
                transformed { it as SafetyEvaluation.NeedsRemediation }
        )
        edge(
            (nodeValidateEvidence forwardTo nodeSafetyBlock)
                onCondition { it is SafetyEvaluation.Blocked }
                transformed { it as SafetyEvaluation.Blocked }
        )
        edge(
            (nodeValidateEvidence forwardTo nodeCompressHistory)
                onCondition { it is SafetyEvaluation.Valid && isNearTokenLimit(this, accumulator, historyCompressionThreshold) }
                transformed { (it as SafetyEvaluation.Valid).content }
        )
        edge(
            (nodeValidateEvidence forwardTo clinicalSynthesis)
                onCondition { it is SafetyEvaluation.Valid && !isNearTokenLimit(this, accumulator, historyCompressionThreshold) }
                transformed { (it as SafetyEvaluation.Valid).content }
        )

        // 5. History Compression -> Clinical Synthesis Subgraph
        edge(nodeCompressHistory forwardTo clinicalSynthesis)

        // 6. Clinical Synthesis Subgraph -> Post-Synthesis Safety Gate
        edge(clinicalSynthesis forwardTo nodeValidateSafety)

        // 7. Post-Synthesis Safety Gate Outgoing Edges
        edge(
            (nodeValidateSafety forwardTo nodeFinish)
                onCondition { it is SafetyEvaluation.Valid }
                transformed { (it as SafetyEvaluation.Valid).content }
        )
        edge(
            (nodeValidateSafety forwardTo nodeRemediate)
                onCondition { it is SafetyEvaluation.NeedsRemediation && it.targetPhase == RemediationTarget.PHASE_1_EVIDENCE }
                transformed { it as SafetyEvaluation.NeedsRemediation }
        )
        edge(
            (nodeValidateSafety forwardTo nodeRemediateSynthesis)
                onCondition { it is SafetyEvaluation.NeedsRemediation && it.targetPhase == RemediationTarget.PHASE_2_SYNTHESIS }
                transformed { it as SafetyEvaluation.NeedsRemediation }
        )
        edge(
            (nodeValidateSafety forwardTo nodeSafetyBlock)
                onCondition { it is SafetyEvaluation.Blocked }
                transformed { it as SafetyEvaluation.Blocked }
        )

        // 8. Remedial Return Loops
        edge(nodeRemediate forwardTo evidenceGathering)
        edge(nodeRemediateSynthesis forwardTo clinicalSynthesis)

        // 9. Safety Block Termination
        edge(nodeSafetyBlock forwardTo nodeFinish)
    }

    /**
     * Formats the closed-book synthesis directive: the retrieved evidence digest plus the
     * output contract. The patient profile is deliberately not repeated here — it is a
     * persistent system message that survives the Phase-1 session rewrite.
     * Passes SYSTEM NOTICE remediation payloads through untouched.
     */
    internal fun formatSynthesisPrompt(
        pendingInput: String,
        userQuestion: String,
        turnContext: SafetyValidator.TurnContext
    ): String {
        // Passthrough for system notices
        if (pendingInput.startsWith("SYSTEM NOTICE")) {
            return pendingInput
        }

        val evidenceSummary = evidenceSummaryFrom(turnContext.fetchedSections)
        val question = if (userQuestion.isNotBlank()) userQuestion else pendingInput

        return buildString {
            appendLine("CLINICAL SYNTHESIS DIRECTIVE (CLOSED-BOOK — NO TOOLS ACTIVE)")
            appendLine()
            appendLine("<retrieved_evidence>")
            appendLine(evidenceSummary)
            appendLine("</retrieved_evidence>")
            appendLine()
            appendLine(SystemPrompt.SYNTHESIS_CONTRACT)
            appendLine()
            appendLine("CLINICAL INQUIRY:")
            append(question)
        }
    }

    /**
     * Terminal message when the safety budget is exhausted. The leading marker is what the
     * post-run validation keys on, so it stays stable; the body tells the clinician what to
     * do next instead of quoting the rule that fired.
     */
    internal fun formatTerminalBlockedMessage(blockedReason: String): String = buildString {
        append(BLOCKED_MARKER)
        append(blockedReason)
        appendLine()
        appendLine()
        appendLine("The drafted answer is withheld because these values are not in the sections retrieved for this question.")
        append("The retrieved sections are attached below as references — review them directly, or rephrase the question to name the drug whose dose you need.")
    }

    /**
     * Resolves the active [PromptTokenizer] from [AIAgentGraphContextBase],
     * falling back to a cached [SimpleRegexBasedTokenizer] if not installed.
     */
    internal fun resolveTokenizer(context: AIAgentGraphContextBase): PromptTokenizer {
        return try {
            context.tokenizer()
        } catch (_: Throwable) {
            CachingTokenizer(SimpleRegexBasedTokenizer())
        }
    }

    /**
     * Token headroom heuristic evaluating whether retrieved context approaches
     * the active history compression threshold using Koog's tokenizer.
     */
    internal fun isNearTokenLimit(
        accumulator: TurnContextAccumulator,
        threshold: Int = 128000
    ): Boolean {
        val regexTokenizer = SimpleRegexBasedTokenizer()
        val totalToolTokens = accumulator.getToolCalls().sumOf { regexTokenizer.countTokens(it.result) }
        val totalSectionTokens = accumulator.getFetchedSections().sumOf { regexTokenizer.countTokens(it.contentSnippet) }
        val estimatedTokens = totalToolTokens + totalSectionTokens
        return estimatedTokens >= (threshold * 0.80)
    }

    /**
     * Suspending token evaluation inspecting LLM prompt session message token counts
     * using Koog's official [PromptTokenizer] via [context.tokenizer].
     */
    internal suspend fun isNearTokenLimit(
        context: AIAgentGraphContextBase,
        accumulator: TurnContextAccumulator,
        threshold: Int = 128000
    ): Boolean {
        val tokenizer = resolveTokenizer(context)
        if (isNearTokenLimit(accumulator, threshold)) return true
        return try {
            context.llm.readSession {
                val tokenCount = tokenizer.tokenCountFor(prompt)
                tokenCount >= (threshold * 0.80)
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Determines whether a Phase-1 text turn is premature (lacks retrieved evidence)
     * and should be intercepted before reaching the maximum allowed retries.
     */
    internal fun isPrematureTurn(
        accumulator: TurnContextAccumulator,
        prematureInterceptions: Int,
        maxSafetyRetries: Int
    ): Boolean {
        if (prematureInterceptions >= maxSafetyRetries) return false
        return !accumulator.hasEvidence
    }

    /**
     * Pre-synthesis evidence gate. Delegates the rule to [SafetyValidator.validateEvidence]
     * so Phase-1 enforcement and the post-hoc verdict can never disagree.
     */
    internal fun evaluateEvidenceGate(
        pendingAnswer: String,
        turnContext: SafetyValidator.TurnContext,
        safetyValidator: SafetyValidator,
        retryCount: Int,
        maxSafetyRetries: Int,
        onPrepareRetry: () -> Unit
    ): SafetyEvaluation {
        val violations = safetyValidator.validateEvidence(turnContext)

        if (violations.isEmpty()) {
            Log.d(TAG, "evaluateEvidenceGate: Evidence verified (sections=${turnContext.fetchedSections.size}, graphics=${turnContext.graphicIds.size})")
            return SafetyEvaluation.Valid(pendingAnswer)
        }

        val blockedReason = violations.joinToString(" | ") { it.detail }

        if (retryCount >= maxSafetyRetries) {
            Log.w(TAG, "evaluateEvidenceGate: Max retries exhausted ($retryCount >= $maxSafetyRetries). Blocking.")
            return SafetyEvaluation.Blocked(reason = blockedReason, violations = violations)
        }

        onPrepareRetry()
        Log.i(TAG, "evaluateEvidenceGate: Missing evidence, retry $retryCount/$maxSafetyRetries. Remediating.")
        return SafetyEvaluation.NeedsRemediation(
            reason = blockedReason,
            evidence = evidenceSummaryOrPlaceholder(turnContext.fetchedSections),
            targetPhase = RemediationTarget.PHASE_1_EVIDENCE,
            violations = violations
        )
    }

    /**
     * Renders evidence summary for the Phase-1 gate prompt, or a placeholder if empty.
     */
    private fun evidenceSummaryOrPlaceholder(sections: List<SafetyValidator.FetchedSection>): String {
        return if (sections.isEmpty()) {
            SystemPrompt.NOTHING_RETRIEVED_PLACEHOLDER
        } else {
            evidenceSummaryFrom(sections)
        }
    }

    /**
     * Evaluates safety rules and orchestrates remediation or terminal blocking for synthesized drafts.
     *
     * @param allowEscalation whether a repeated quantity failure may be sent back to retrieval;
     *        once the escalation budget is spent the turn can only revise or block.
     */
    internal fun evaluateSafetyTurn(
        cleanText: String,
        turnContext: SafetyValidator.TurnContext,
        safetyValidator: SafetyValidator,
        retryCount: Int,
        maxSafetyRetries: Int,
        allowEscalation: Boolean = true,
        onPrepareRetry: () -> Unit
    ): SafetyEvaluation {
        val hasEvidence = turnContext.fetchedSections.isNotEmpty() || turnContext.graphicIds.isNotEmpty()

        // Verify answer is non-blank before running safety rules.
        val validation = if (cleanText.isBlank()) {
            val violation = if (hasEvidence) {
                Violation(ViolationCodes.BLANK_WITH_EVIDENCE, SafetyValidator.BLANK_WITH_EVIDENCE_REASON, ViolationTarget.PHASE_2)
            } else {
                Violation(ViolationCodes.BLANK_NO_EVIDENCE, SafetyValidator.BLANK_NO_EVIDENCE_REASON, ViolationTarget.PHASE_1)
            }
            SafetyValidator.ValidationResult(
                passed = false,
                warnings = emptyList(),
                violations = listOf(violation),
                blockedReason = violation.detail
            )
        } else {
            safetyValidator.validate(turnContext)
        }

        if (validation.passed) {
            return SafetyEvaluation.Valid(cleanText)
        }

        if (retryCount >= maxSafetyRetries) {
            return SafetyEvaluation.Blocked(
                reason = validation.blockedReason ?: "Clinical verification blocked after retries",
                violations = validation.violations
            )
        }

        onPrepareRetry()

        // A quantity failure that survived one tool-free revision means the value was never
        // retrieved. Retrying without tools can only paraphrase it again, so hand tools back
        // and name the values — but only while the escalation budget lasts.
        val unverified = validation.violations.firstOrNull { it.code == ViolationCodes.UNVERIFIED_QTY }
        val violations = if (unverified != null && retryCount >= 1 && allowEscalation) {
            listOf(Violation(ViolationCodes.UNRETRIEVED_DOSES, unverified.detail, ViolationTarget.PHASE_1))
        } else {
            validation.violations
        }

        // Determine whether remediation requires tool retrieval (Phase 1) or draft revision (Phase 2).
        val targetPhase = if (violations.any { it.target == ViolationTarget.PHASE_1 }) {
            RemediationTarget.PHASE_1_EVIDENCE
        } else {
            RemediationTarget.PHASE_2_SYNTHESIS
        }

        return SafetyEvaluation.NeedsRemediation(
            reason = violations.first().detail,
            evidence = evidenceSummaryFrom(turnContext.fetchedSections),
            targetPhase = targetPhase,
            violations = violations,
            draft = cleanText
        )
    }

    /**
     * Formats fetched section content into an evidence digest string.
     */
    internal fun evidenceSummaryFrom(sections: List<SafetyValidator.FetchedSection>): String =
        sections.joinToString("\n---\n") { sec ->
            if (sec.contentSnippet.isNotBlank()) {
                "Section [${sec.sectionId}] (${sec.sectionTitle}):\n${sec.contentSnippet}"
            } else {
                "Section [${sec.sectionId}] (${sec.sectionTitle}) from topic ${sec.topicTitle}"
            }
        }.ifBlank { "No section text was successfully fetched in the prior turn." }

}
