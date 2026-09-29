package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.extension.nodeExecuteTools
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.nodeLLMSendToolResults
import ai.koog.agents.core.dsl.extension.onTextMessage
import ai.koog.agents.core.dsl.extension.onToolCalls

/**
 * Strategy definitions for clinical retrieval workflows.
 *
 * Provides:
 * 1. [instance]: Standard ReAct retrieval strategy graph singleton.
 * 2. [createAutonomous]: Autonomous, self-healing state machine incorporating
 *    native in-graph safety validation and multi-turn self-correction loops.
 */
object ClinicalAgentStrategy {

    sealed class SafetyEvaluation {
        data class Valid(val content: String) : SafetyEvaluation()
        data class NeedsRemediation(val reason: String, val evidence: String) : SafetyEvaluation()
        data class Blocked(val reason: String) : SafetyEvaluation()
    }

    /**
     * Creates an autonomous self-healing clinical retrieval strategy graph.
     * Incorporates native in-graph safety validation and multi-turn self-correction loops.
     */
    fun create(
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2
    ): AIAgentGraphStrategy<String, String> = createAutonomous(safetyValidator, accumulator, maxSafetyRetries)

    fun createAutonomous(
        safetyValidator: SafetyValidator = SafetyValidator(),
        accumulator: TurnContextAccumulator = TurnContextAccumulator(),
        maxSafetyRetries: Int = 2
    ): AIAgentGraphStrategy<String, String> = strategy("clinical-autonomous") {
        var retryCount = 0

        val nodeSendInput by nodeLLMRequest()
        val nodeExecuteTool by nodeExecuteTools()
        val nodeSendToolResult by nodeLLMSendToolResults()

        val nodeValidateSafety by node<String, SafetyEvaluation>("validate_safety") { text ->
            val turnContext = accumulator.buildTurnContext(text)
            val validation = safetyValidator.validate(turnContext)
            if (validation.passed) {
                SafetyEvaluation.Valid(text)
            } else if (retryCount < maxSafetyRetries) {
                retryCount++
                val snapshot = accumulator.snapshotForCorrection()
                accumulator.prepareForCorrection()
                val evidenceSummary = snapshot.buildTurnContext("").fetchedSections
                    .joinToString("\n---\n") { sec ->
                        if (sec.contentSnippet.isNotBlank()) {
                            "Section [${sec.sectionId}] (${sec.sectionTitle}):\n${sec.contentSnippet}"
                        } else {
                            "Section [${sec.sectionId}] (${sec.sectionTitle}) from topic ${sec.topicTitle}"
                        }
                    }.ifBlank { "No section text was successfully fetched in the prior turn." }
                SafetyEvaluation.NeedsRemediation(
                    reason = validation.blockedReason ?: "Clinical verification failed",
                    evidence = evidenceSummary
                )
            } else {
                SafetyEvaluation.Blocked(
                    reason = validation.blockedReason ?: "Clinical verification blocked after retries"
                )
            }
        }

        val nodeRemediate by node<SafetyEvaluation.NeedsRemediation, String>("build_remediation") { rem ->
            SystemPrompt.buildCorrectionPrompt(rem.reason, rem.evidence)
        }

        val nodeSafetyBlock by node<SafetyEvaluation.Blocked, String>("safety_block") { blocked ->
            "Clinical Response Verification Blocked: ${blocked.reason}"
        }

        // 1. Initial input -> LLM
        edge(nodeStart forwardTo nodeSendInput)

        // 2. LLM response routing
        edge(nodeSendInput forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendInput forwardTo nodeValidateSafety onTextMessage { true })

        // 3. Tool execution loop
        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCalls { true })
        edge(nodeSendToolResult forwardTo nodeValidateSafety onTextMessage { true })

        // 4. In-graph safety evaluation & self-healing routing
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
        edge(nodeRemediate forwardTo nodeSendInput)

        edge(
            (nodeValidateSafety forwardTo nodeSafetyBlock)
                onCondition { it is SafetyEvaluation.Blocked }
                transformed { it as SafetyEvaluation.Blocked }
        )
        edge(nodeSafetyBlock forwardTo nodeFinish)
    }
}
