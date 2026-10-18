package com.clinref.app.e2e

import com.clinref.app.domain.ai.ClinicalAgentStrategy
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.SystemPrompt
import com.clinref.app.domain.ai.TurnContextAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end tests for the JetBrains Koog autonomous agent strategy graph.
 *
 * Verifies:
 * 1. Graph topology: node declarations and directed edges across all lifecycle phases.
 * 2. Closed-loop remediation topology: nodeRemediate routes directly back to nodeSendInput.
 * 3. Terminal safety block routing: nodeSafetyBlock connects to nodeFinish.
 * 4. In-graph safety evaluation sealed class models and data payloads.
 * 5. Thinking token and XML thought tag hygiene across streaming and validation boundaries.
 */
class KoogAgentStrategyE2ETest {

    @Test
    fun testKoogGraphTopologyAndCompleteStateTransitions() {
        val strategy = ClinicalAgentStrategy.createAutonomous()
        assertNotNull("Strategy should instantiate successfully", strategy)
        assertEquals("clinical-autonomous", strategy.name)

        val nodesMap = strategy.metadata.nodesMap
        val allNodeNames = nodesMap.values.map { it.name }.toSet()

        // 1. All functional nodes present in graph
        assertTrue("Must contain nodeSendInput", allNodeNames.any { it.contains("nodeSendInput") })
        assertTrue("Must contain nodeExecuteTool", allNodeNames.any { it.contains("nodeExecuteTool") })
        assertTrue("Must contain nodeSendToolResult", allNodeNames.any { it.contains("nodeSendToolResult") })
        assertTrue("Must contain intercept_premature", allNodeNames.contains("intercept_premature"))
        assertTrue("Must contain clinical_synthesis", allNodeNames.contains("clinical_synthesis"))
        assertTrue("Must contain validate_safety", allNodeNames.contains("validate_safety"))
        assertTrue("Must contain build_remediation", allNodeNames.contains("build_remediation"))
        assertTrue("Must contain safety_block", allNodeNames.contains("safety_block"))

        // 2. Topology and edge verification
        val allEdges = nodesMap.values.flatMap { it.edges }

        // Start node forwards to input
        assertTrue("Start node must have outgoing edge", strategy.nodeStart.edges.isNotEmpty())

        val sendInputNode = nodesMap.values.first { it.name.contains("nodeSendInput") }
        val remediateNode = nodesMap.values.first { it.name == "build_remediation" }
        val interceptNode = nodesMap.values.first { it.name == "intercept_premature" }
        val synthesisNode = nodesMap.values.first { it.name == "clinical_synthesis" }
        val validateNode = nodesMap.values.first { it.name == "validate_safety" }

        // Remediation loops back to nodeSendInput
        assertTrue(
            "Remediation node must edge directly forwardTo nodeSendInput",
            remediateNode.edges.any { it.toNode == sendInputNode }
        )

        // Intercept premature node loops back to nodeSendInput
        assertTrue(
            "intercept_premature node must edge forwardTo nodeSendInput",
            interceptNode.edges.any { it.toNode == sendInputNode }
        )

        // Clinical synthesis node routes to validate_safety
        assertTrue(
            "clinical_synthesis node must edge forwardTo validate_safety",
            synthesisNode.edges.any { it.toNode == validateNode }
        )

        // Safety block routes to nodeFinish
        val safetyBlockNode = nodesMap.values.first { it.name == "safety_block" }
        assertTrue(
            "Safety block node must edge forwardTo nodeFinish",
            safetyBlockNode.edges.any { it.toNode == strategy.nodeFinish }
        )

        // Validate safety node has conditional transitions to finish, remediation, and safety block
        val targetsFromValidate = validateNode.edges.map { it.toNode }.toSet()
        assertTrue("validate_safety must target nodeFinish", targetsFromValidate.contains(strategy.nodeFinish))
        assertTrue("validate_safety must target build_remediation", targetsFromValidate.contains(remediateNode))
        assertTrue("validate_safety must target safety_block", targetsFromValidate.contains(safetyBlockNode))
    }

    @Test
    fun testSafetyEvaluationModelInvariants() {
        // 1. Valid model
        val valid = ClinicalAgentStrategy.SafetyEvaluation.Valid("Verified answer: 5 mg daily.")
        assertEquals("Verified answer: 5 mg daily.", valid.content)

        // 2. NeedsRemediation model
        val needsRemediation = ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation(
            reason = "Unverified clinical quantity: 15 mg",
            evidence = "Section [H1] (Dosing): Standard dose is 5 mg."
        )
        assertEquals("Unverified clinical quantity: 15 mg", needsRemediation.reason)
        assertTrue(needsRemediation.evidence.contains("Standard dose is 5 mg"))

        // 3. Blocked model
        val blocked = ClinicalAgentStrategy.SafetyEvaluation.Blocked(
            reason = "Clinical recommendations require database verification"
        )
        assertTrue(blocked.reason.contains("database verification"))
    }

    @Test
    fun testThinkingTokenAndReasoningBufferHygiene() {
        // Verify XML thought tag stripping logic ensuring intermediate thoughts
        // do not trigger false positive number validation failures in SafetyValidator
        val rawLlmOutputWithThoughts = """
            <thought>
            The user is asking about apixaban. Let me check the clinical numbers.
            Atrial fibrillation trial 18299 studied 15000 patients and found 99.4% efficacy.
            Wait, I should not invent trial statistics. I must only state verified facts.
            </thought>
            The recommended standard dose of apixaban is 5 mg twice daily.
        """.trimIndent()

        // Strip thought tags
        val thoughtTagRegex = Regex("""<thought>.*?</thought>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val cleanedText = rawLlmOutputWithThoughts.replace(thoughtTagRegex, "").trim()

        assertEquals("The recommended standard dose of apixaban is 5 mg twice daily.", cleanedText)

        // When cleanedText is evaluated against evidence containing "5 mg twice daily",
        // the intermediate thought numbers (18299, 15000, 99.4%) are completely stripped
        // and do not fail Rule 2!
        val acc = TurnContextAccumulator()
        acc.setUserQuestion("What is the apixaban dose?")
        acc.onToolCallStarting("call-1", """{"topicId":"1","sectionId":"H1"}""")
        acc.onToolCallCompleted(
            "call-1",
            "getTopicSectionsText",
            """{"topicId":"1","topicTitle":"Apixaban","sectionTitles":{"H1":"Dosing"},"markdown":"Standard dose is 5 mg twice daily."}""",
            true
        )

        val turnContext = acc.buildTurnContext(cleanedText)
        val validator = SafetyValidator()
        val result = validator.validate(turnContext)

        assertTrue("Cleaned text without thought tags must pass validation", result.passed)
    }
}
