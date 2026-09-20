package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.collectGraphData
import org.junit.Assert.assertEquals
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
        val graph = strategy.collectGraphData()
        val nodeNames = graph.nodes.values.map { it.name }.toSet()

        assertTrue("Must contain nodeSendInput", nodeNames.any { it.contains("nodeSendInput") })
        assertTrue("Must contain nodeExecuteTool", nodeNames.any { it.contains("nodeExecuteTool") })
        assertTrue("Must contain nodeSendToolResult", nodeNames.any { it.contains("nodeSendToolResult") })
        assertTrue("Must contain validate_safety", nodeNames.contains("validate_safety"))
        assertTrue("Must contain build_remediation", nodeNames.contains("build_remediation"))
        assertTrue("Must contain safety_block", nodeNames.contains("safety_block"))
    }

    @Test
    fun `strategy defines edges connecting start, tool loop, validation, remediation, and finish`() {
        val strategy = ClinicalAgentStrategy.create()
        val graph = strategy.collectGraphData()

        assertTrue("Strategy must have multiple directed edges", graph.edges.isNotEmpty())

        // Start node connects to LLM input
        assertTrue(
            "An edge must originate from startNode",
            graph.edges.any { it.fromNode.id == strategy.nodeStart.id }
        )

        // Finish node must be targeted by safety valid path and safety block path
        val finishEdges = graph.edges.filter { it.toNode.id == strategy.nodeFinish.id }
        assertTrue("At least one edge must target nodeFinish", finishEdges.isNotEmpty())

        // Remediation must loop back to nodeSendInput
        val sendInputNode = graph.nodes.values.first { it.name.contains("nodeSendInput") }
        val remediateNode = graph.nodes.values.first { it.name == "build_remediation" }
        assertTrue(
            "Remediation node must edge forwardTo nodeSendInput",
            graph.edges.any { it.fromNode.id == remediateNode.id && it.toNode.id == sendInputNode.id }
        )

        // Safety validation node must edge to finish, remediation, and safety_block
        val validateNode = graph.nodes.values.first { it.name == "validate_safety" }
        val safetyBlockNode = graph.nodes.values.first { it.name == "safety_block" }

        assertTrue(
            "validate_safety must connect to nodeFinish",
            graph.edges.any { it.fromNode.id == validateNode.id && it.toNode.id == strategy.nodeFinish.id }
        )
        assertTrue(
            "validate_safety must connect to build_remediation",
            graph.edges.any { it.fromNode.id == validateNode.id && it.toNode.id == remediateNode.id }
        )
        assertTrue(
            "validate_safety must connect to safety_block",
            graph.edges.any { it.fromNode.id == validateNode.id && it.toNode.id == safetyBlockNode.id }
        )
    }

    @Test
    fun `safety evaluation sealed class holds correct models`() {
        val valid = ClinicalAgentStrategy.SafetyEvaluation.Valid("Verified response")
        assertEquals("Verified response", valid.content)

        val remediation = ClinicalAgentStrategy.SafetyEvaluation.NeedsRemediation("Missing unit", "Evidence text")
        assertEquals("Missing unit", remediation.reason)
        assertEquals("Evidence text", remediation.evidence)

        val blocked = ClinicalAgentStrategy.SafetyEvaluation.Blocked("Invented numbers")
        assertEquals("Invented numbers", blocked.reason)
    }
}
