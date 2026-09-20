package com.clinref.app.domain.ai

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
        val nodeNames = strategy.metadata.nodesMap.values.map { it.name }.toSet()

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

        // Remediation must loop back to nodeSendInput
        val sendInputNode = allNodes.first { it.name.contains("nodeSendInput") }
        val remediateNode = allNodes.first { it.name == "build_remediation" }
        assertTrue(
            "Remediation node must edge forwardTo nodeSendInput",
            remediateNode.edges.any { it.toNode == sendInputNode }
        )

        // Safety validation node must edge to finish, remediation, and safety_block
        val validateNode = allNodes.first { it.name == "validate_safety" }
        val safetyBlockNode = allNodes.first { it.name == "safety_block" }

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
