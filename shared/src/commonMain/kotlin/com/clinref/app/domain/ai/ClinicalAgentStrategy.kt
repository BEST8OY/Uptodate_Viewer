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
 * Phase-Isolated Multi-Subgraph Clinical Inquiry Strategy Graph.
 *
 * Operational Phases & Subgraphs:
 * 1. Subgraph 1: Evidence Gathering ([evidenceGathering])
 *    - Binds all retrieval database tools into LLM scope.
 *    - Handles retrieval tool execution, parallel queries, tool round limit ([MAX_TOOL_ROUNDS]),
 *      and premature clinical assertion interception.
 * 2. Dual Gate 1: Pre-Synthesis Evidence Gate ([nodeValidateEvidence])
 *    - Checks Rule 0 (tool execution) and Rule 1 (section/table evidence) before synthesis.
 *    - Un-grounded responses loop back to evidence gathering with targeted correction prompt.
 * 3. History Compression: Native Semantic History Compression ([nodeCompressHistory])
 *    - Condenses long conversational histories at [historyCompressionThreshold] (128k) while
 *      preserving patient demographics, renal/allergy bounds, and memory context ([preserveMemory = true]).
 * 4. Subgraph 2: Clinical Synthesis ([clinicalSynthesis])
 *    - Explicitly binds zero database tools (tools = emptyList()), mathematically preventing
 *      synthesis-phase hallucinated tool calls.
 *    - Injects structured PICO/SOAP directives, patient safety constraints, and verified evidence summary.
 * 5. Dual Gate 2: Post-Synthesis Safety Gate ([nodeValidateSafety])
 *    - Evaluates final clinical answer against numbers, dosages, patient profile bounds, and graphic interpretation.
 *    - Targeted remediation loops back to evidence gathering if missing evidence, or back to
 *      clinical synthesis if existing evidence suffices.
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
    ): AIAgentGraphStrategy<String, String> = createAutonomous(
        tools = emptyList(),
        safetyValidator = safetyValidator,
        accumulator = accumulator,
        maxSafetyRetries = maxSafetyRetries,
        strategyName = strategyName
    )

    fun create(
        tools: List<Tool<*, *>>,
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2,
        strategyName: String = STRATEGY_AUTONOMOUS,
        historyCompressionThreshold: Int = 128000
    ): AIAgentGraphStrategy<String, String> = createAutonomous(
        tools = tools,
        safetyValidator = safetyValidator,
        accumulator = accumulator,
        maxSafetyRetries = maxSafetyRetries,
        strategyName = strategyName,
        historyCompressionThreshold = historyCompressionThreshold
    )


    fun createAutonomous(
        tools: List<Tool<*, *>> = emptyList(),
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2,
        strategyName: String = STRATEGY_AUTONOMOUS,
        historyCompressionThreshold: Int = 128000
    ): AIAgentGraphStrategy<String, String> = strategy(strategyName) {
        var retryCount = 0
        var prematureInterceptions = 0
        var toolRounds = 0
        var currentState: ClinicalState = ClinicalState.EvidenceGathering()

        // ════════════════════════════════════════════════════════════════════════
        // SUBGRAPH 1: evidence_gathering
        // Binds all 6 retrieval database tools into LLM scope.
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
                    query = accumulator.buildTurnContext("").userQuestion,
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
                    query = accumulator.buildTurnContext("").userQuestion,
                    toolRounds = prematureInterceptions,
                    fetchedSectionsCount = accumulator.fetchedSectionsCount,
                    graphicCount = accumulator.graphicCount
                )
                Log.w(TAG, "Premature response intercepted (attempt $prematureInterceptions). State: $currentState. Redirecting to retrieval.")
                SystemPrompt.buildPrematureInterceptionPrompt()
            }

            val isPremature: (String) -> Boolean = { text ->
                isPrematureTurn(accumulator, text, prematureInterceptions, maxSafetyRetries)
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
        // Evaluates Rule 0 & Rule 1 section/table presence before synthesis.
        // ════════════════════════════════════════════════════════════════════════
        val nodeValidateEvidence by node<String, SafetyEvaluation>("validate_evidence") { text ->
            val cleanText = ReasoningTokenSanitizer.stripReasoning(text)
            val turnContext = accumulator.buildTurnContext(cleanText)

            val isGreeting = SafetyValidator.isNonClinicalResponse(turnContext)
            val hasEvidence = turnContext.fetchedSections.isNotEmpty() || turnContext.graphicIds.isNotEmpty()
            val hasTools = turnContext.toolCalls.isNotEmpty()

            val evaluation = if (isGreeting) {
                Log.d(TAG, "validate_evidence: Non-clinical greeting detected, proceeding to synthesis")
                SafetyEvaluation.Valid(cleanText)
            } else if (!hasTools || !hasEvidence) {
                val blockedReason = if (!hasTools) {
                    "Clinical recommendations require database verification. No database tools were executed."
                } else {
                    "Answer requires section or table content retrieval. Only topic outlines or searches were performed."
                }

                if (retryCount >= maxSafetyRetries) {
                    Log.w(TAG, "validate_evidence: Max retries exhausted ($retryCount >= $maxSafetyRetries). Blocking.")
                    SafetyEvaluation.Blocked(blockedReason)
                } else {
                    retryCount++
                    accumulator.prepareForCorrection()
                    val evidenceSummary = evidenceSummaryFrom(turnContext.fetchedSections)
                    Log.i(TAG, "validate_evidence: Missing evidence, retry $retryCount/$maxSafetyRetries. Remediating.")
                    SafetyEvaluation.NeedsRemediation(
                        reason = blockedReason,
                        evidence = evidenceSummary,
                        targetPhase = RemediationTarget.PHASE_1_EVIDENCE
                    )
                }
            } else {
                Log.d(TAG, "validate_evidence: Evidence verified (sections=${turnContext.fetchedSections.size}, graphics=${turnContext.graphicIds.size})")
                SafetyEvaluation.Valid(cleanText)
            }

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
        // Semantic summarization with memory and concept preservation.
        // ════════════════════════════════════════════════════════════════════════
        val nodeCompressHistory by nodeLLMCompressHistory<String>(
            name = "compress_history",
            strategy = HistoryCompressionStrategy.WholeHistoryMultipleSystemMessages,
            preserveMemory = true
        )

        // ════════════════════════════════════════════════════════════════════════
        // SUBGRAPH 2: clinical_synthesis
        // Explicitly binds tools = emptyList() (zero tools in model schema).
        // ════════════════════════════════════════════════════════════════════════
        val clinicalSynthesis by subgraph<String, String>(
            name = "clinical_synthesis",
            tools = emptyList()
        ) {
            val isGreetingBypass: (String) -> Boolean = { input ->
                val clean = ReasoningTokenSanitizer.stripReasoning(input)
                val context = accumulator.buildTurnContext(clean)
                SafetyValidator.isNonClinicalResponse(context) &&
                    context.fetchedSections.isEmpty() &&
                    context.toolCalls.isEmpty() &&
                    clean.isNotBlank() &&
                    !clean.startsWith("SYSTEM NOTICE")
            }

            val nodeFormatSynthesisPrompt by node<String, String>("format_synthesis") { pendingInput ->
                val cleanInput = ReasoningTokenSanitizer.stripReasoning(pendingInput)
                val turnContext = accumulator.buildTurnContext(cleanInput)
                currentState = ClinicalState.ClinicalSynthesis(
                    rawResponse = pendingInput,
                    cleanedResponse = cleanInput,
                    turnContext = turnContext
                )

                formatSynthesisPrompt(
                    pendingInput = cleanInput,
                    turnContext = turnContext
                )
            }

            val synthesisLLM by nodeLLMRequest("synthesis_llm")

            val nodeStripReasoning by node<String, String>("strip_reasoning") { rawText ->
                ReasoningTokenSanitizer.stripReasoning(rawText)
            }

            edge(
                (nodeStart forwardTo nodeFinish)
                    onCondition { isGreetingBypass(it) }
                    transformed { ReasoningTokenSanitizer.stripReasoning(it) }
            )
            edge(
                (nodeStart forwardTo nodeFormatSynthesisPrompt)
                    onCondition { !isGreetingBypass(it) }
            )
            edge(nodeFormatSynthesisPrompt forwardTo synthesisLLM)
            edge(synthesisLLM forwardTo nodeStripReasoning onTextMessage { true })
            edge(nodeStripReasoning forwardTo nodeFinish)
        }

        // ════════════════════════════════════════════════════════════════════════
        // DUAL GATE 2: validate_safety (Post-Synthesis Safety Validation Gate)
        // Evaluates clinical response against quantities, patient profile bounds.
        // ════════════════════════════════════════════════════════════════════════
        val nodeValidateSafety by node<String, SafetyEvaluation>("validate_safety") { text ->
            val cleanText = ReasoningTokenSanitizer.stripReasoning(text)
            val turnContext = accumulator.buildTurnContext(cleanText)

            val evaluation = evaluateSafetyTurn(
                cleanText = cleanText,
                turnContext = turnContext,
                safetyValidator = safetyValidator,
                retryCount = retryCount,
                maxSafetyRetries = maxSafetyRetries,
                onPrepareRetry = {
                    retryCount++
                    accumulator.prepareForCorrection()
                }
            )

            currentState = ClinicalState.SafetyVerification(
                evaluation = evaluation,
                retryCount = retryCount,
                targetPhase = (evaluation as? SafetyEvaluation.NeedsRemediation)?.targetPhase ?: RemediationTarget.PHASE_1_EVIDENCE
            )
            Log.d(TAG, "Phase 3 Safety Verification evaluated: state=$currentState")
            evaluation
        }

        // Remediation Prompt Builders
        val nodeRemediate by node<SafetyEvaluation.NeedsRemediation, String>("build_remediation") { rem ->
            Log.i(TAG, "Remediating toward Phase 1 Evidence: ${rem.reason}")
            SystemPrompt.buildCorrectionPrompt(rem.reason, rem.evidence)
        }

        val nodeRemediateSynthesis by node<SafetyEvaluation.NeedsRemediation, String>("build_remediation_synthesis") { rem ->
            Log.i(TAG, "Remediating toward Phase 2 Synthesis: ${rem.reason}")
            SystemPrompt.buildCorrectionPrompt(rem.reason, rem.evidence)
        }

        val nodeSafetyBlock by node<SafetyEvaluation.Blocked, String>("safety_block") { blocked ->
            "Clinical Response Verification Blocked: ${blocked.reason}"
        }

        // ════════════════════════════════════════════════════════════════════════
        // OUTER STRATEGY EDGES & DETERMINISTIC ROUTING TOPOLOGY
        // ════════════════════════════════════════════════════════════════════════

        // 1. Graph Entry -> Evidence Gathering Subgraph
        edge(nodeStart forwardTo evidenceGathering)

        // 2. Evidence Gathering -> Pre-Synthesis Validation Gate
        edge(evidenceGathering forwardTo nodeValidateEvidence)

        // 3. Pre-Synthesis Validation Gate Outgoing Edges
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

        // 4. History Compression -> Clinical Synthesis Subgraph
        edge(nodeCompressHistory forwardTo clinicalSynthesis)

        // 5. Clinical Synthesis Subgraph -> Post-Synthesis Safety Gate
        edge(clinicalSynthesis forwardTo nodeValidateSafety)

        // 6. Post-Synthesis Safety Gate Outgoing Edges
        edge(
            (nodeValidateSafety forwardTo nodeFinish)
                onCondition { it is SafetyEvaluation.Valid }
                transformed { (it as SafetyEvaluation.Valid).content }
        )
        edge(
            (nodeValidateSafety forwardTo nodeRemediate)
                onCondition { it is SafetyEvaluation.NeedsRemediation && (it as SafetyEvaluation.NeedsRemediation).targetPhase == RemediationTarget.PHASE_1_EVIDENCE }
                transformed { it as SafetyEvaluation.NeedsRemediation }
        )
        edge(
            (nodeValidateSafety forwardTo nodeRemediateSynthesis)
                onCondition { it is SafetyEvaluation.NeedsRemediation && (it as SafetyEvaluation.NeedsRemediation).targetPhase == RemediationTarget.PHASE_2_SYNTHESIS }
                transformed { it as SafetyEvaluation.NeedsRemediation }
        )
        edge(
            (nodeValidateSafety forwardTo nodeSafetyBlock)
                onCondition { it is SafetyEvaluation.Blocked }
                transformed { it as SafetyEvaluation.Blocked }
        )

        // 7. Remedial Return Loops
        edge(nodeRemediate forwardTo evidenceGathering)
        edge(nodeRemediateSynthesis forwardTo clinicalSynthesis)

        // 8. Safety Block Termination
        edge(nodeSafetyBlock forwardTo nodeFinish)
    }

    /**
     * Formats synthesis directives injecting silent clinical triage, active patient profile
     * contraindications, and verified evidence summary.
     */
    internal fun formatSynthesisPrompt(
        pendingInput: String,
        turnContext: SafetyValidator.TurnContext
    ): String {
        if (SafetyValidator.isNonClinicalResponse(turnContext) && turnContext.fetchedSections.isEmpty()) {
            return if (pendingInput.isNotBlank()) {
                "Please provide a helpful, courteous greeting or non-clinical response addressing: $pendingInput"
            } else {
                "Please provide a helpful, courteous greeting to the clinician and offer clinical assistance."
            }
        }

        if (pendingInput.startsWith("SYSTEM NOTICE")) {
            return pendingInput
        }

        val patientProfile = turnContext.patientProfile
        val patientBlock = if (patientProfile != null) {
            SystemPrompt.patientContextBlock(patientProfile)
        } else {
            ""
        }
        val patientProfileSection = if (patientBlock.isNotBlank()) {
            patientBlock
        } else {
            "No active patient profile or documented contraindications."
        }

        val evidenceSummary = evidenceSummaryFrom(turnContext.fetchedSections)

        return buildString {
            appendLine("CLINICAL SYNTHESIS DIRECTIVE (CLOSED-BOOK MODE — NO TOOLS ACTIVE):")
            appendLine("Synthesize a definitive, point-of-care clinical response for the inquiry below using ONLY the `<retrieved_evidence>` block and `<patient_profile>` constraints.")
            appendLine()
            appendLine("<patient_profile>")
            appendLine(patientProfileSection)
            appendLine("</patient_profile>")
            appendLine()
            appendLine("<retrieved_evidence>")
            appendLine(evidenceSummary)
            appendLine("</retrieved_evidence>")
            appendLine()
            appendLine("MANDATORY SYNTHESIS & VERIFICATION RULES:")
            appendLine("1. SILENT CLINICAL TRIAGE: Before writing, verify the candidate regimen against `<patient_profile>` allergies, renal markers (eGFR/CrCl), hepatic status, and active medications.")
            appendLine("2. ALLERGY EXCLUSION: If a first-line drug or drug class conflicts with a documented patient allergy in `<patient_profile>`, select a safe alternative from `<retrieved_evidence>`. Do NOT recommend or name the contraindicated drug as an option.")
            appendLine("3. VERBATIM QUANTITY MATCHING: Every clinical number, dosage, percentage, range, and lab threshold in your response must match an exact number and unit string inside `<retrieved_evidence>` (or echo the patient's own documented profile values). Do NOT convert units (e.g., g to mg) or calculate weight-multiplied doses not explicitly written in the text. If a number is absent from `<retrieved_evidence>`, write \"not specified in retrieved sections.\"")
            appendLine("4. POINT-OF-CARE STRUCTURE:")
            appendLine("   - Lead with a 1–2 sentence **bolded Bottom-Line Up Front (BLUF)** giving the exact patient-tailored regimen.")
            appendLine("   - Follow with concise bullet points under these three exact headers:")
            appendLine("     * **First-Line Regimen & Dosing**")
            appendLine("     * **Organ Function & Adjustments**")
            appendLine("     * **Contraindications & Critical Warnings**")
            appendLine("   - Write plain text only (no markdown links, no reference/source lists at the end).")

            if (pendingInput.isNotBlank() && !pendingInput.startsWith("CLINICAL SYNTHESIS DIRECTIVE")) {
                appendLine()
                appendLine("CLINICAL INQUIRY:")
                appendLine(pendingInput)
            }
        }
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
        return estimatedTokens >= (threshold * 0.85)
    }

    /**
     * Counts tokens for a collection of Koog prompt messages using [PromptTokenizer].
     */
    internal fun estimatePromptTokens(
        messages: List<Message>,
        tokenizer: PromptTokenizer = CachingTokenizer(SimpleRegexBasedTokenizer())
    ): Int {
        return messages.sumOf { tokenizer.tokenCountFor(it) }
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
                tokenCount >= (threshold * 0.85)
            }
        } catch (_: Throwable) {
            false
        }
    }



    /**
     * Determines whether an emitted response text is premature (i.e. clinical assertion
     * attempted before any database tools or section/graphic evidence were gathered).
     */
    internal fun isPrematureTurn(
        accumulator: TurnContextAccumulator,
        text: String,
        prematureInterceptions: Int,
        maxSafetyRetries: Int
    ): Boolean {
        if (prematureInterceptions >= maxSafetyRetries) return false
        if (accumulator.hasToolCalls || accumulator.hasEvidence) return false

        val context = accumulator.buildTurnContext(text)
        return !SafetyValidator.isNonClinicalResponse(context)
    }

    /**
     * Evaluates safety rules and orchestrates remediation or terminal blocking.
     */
    internal fun evaluateSafetyTurn(
        cleanText: String,
        turnContext: SafetyValidator.TurnContext,
        safetyValidator: SafetyValidator,
        retryCount: Int,
        maxSafetyRetries: Int,
        onPrepareRetry: () -> Unit
    ): SafetyEvaluation {
        val hasEvidence = turnContext.fetchedSections.isNotEmpty() || turnContext.graphicIds.isNotEmpty()

        // Blank-answer guard: a budget-forced (tool_round_boundary) exit or any
        // empty response MUST NOT complete the turn with an empty answer.
        val validation = if (cleanText.isBlank()) {
            SafetyValidator.ValidationResult(
                passed = false,
                warnings = emptyList(),
                blockedReason = if (hasEvidence) {
                    "No final clinical answer was synthesized; render the gathered evidence"
                } else {
                    "No clinical response was generated. Evidence retrieval is required."
                }
            )
        } else {
            safetyValidator.validate(turnContext)
        }

        if (validation.passed) {
            return SafetyEvaluation.Valid(cleanText)
        }

        if (retryCount >= maxSafetyRetries) {
            val blockedReason = if (cleanText.isBlank()) {
                if (hasEvidence) "No final clinical answer was synthesized after evidence gathering"
                else "No clinical response was generated after retries"
            } else {
                validation.blockedReason ?: "Clinical verification blocked after retries"
            }
            return SafetyEvaluation.Blocked(blockedReason)
        }

        onPrepareRetry()
        val evidenceSummary = evidenceSummaryFrom(turnContext.fetchedSections)

        val targetPhase = if (!hasEvidence) {
            RemediationTarget.PHASE_1_EVIDENCE
        } else {
            RemediationTarget.PHASE_2_SYNTHESIS
        }

        return SafetyEvaluation.NeedsRemediation(
            reason = validation.blockedReason ?: "Clinical verification failed",
            evidence = evidenceSummary,
            targetPhase = targetPhase
        )
    }

    /**
     * Evidence digest shared by the blank-answer guard and the validation remediation
     * branch, mirroring Python's evidence summary assembly for correction prompts.
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
