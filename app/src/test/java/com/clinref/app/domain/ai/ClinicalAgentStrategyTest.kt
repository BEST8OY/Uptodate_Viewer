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
        val nodeNames = strategy.nodes.map { it.name }.toSet()

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

        assertTrue("Strategy must have multiple directed edges", strategy.edges.isNotEmpty())

        // Start node connects to LLM input
        assertTrue(
            "An edge must originate from startNode",
            strategy.edges.any { it.from == strategy.nodeStart }
        )

        // Finish node must be targeted by safety valid path and safety block path
        val finishEdges = strategy.edges.filter { it.to == strategy.nodeFinish }
        assertTrue("At least one edge must target nodeFinish", finishEdges.isNotEmpty())

        // Remediation must loop back to nodeSendInput
        val sendInputNode = strategy.nodes.first { it.name.contains("nodeSendInput") }
        val remediateNode = strategy.nodes.first { it.name == "build_remediation" }
        assertTrue(
            "Remediation node must edge forwardTo nodeSendInput",
            strategy.edges.any { it.from == remediateNode && it.to == sendInputNode }
        )

        // Safety validation node must edge to finish, remediation, and safety_block
        val validateNode = strategy.nodes.first { it.name == "validate_safety" }
        val safetyBlockNode = strategy.nodes.first { it.name == "safety_block" }

        assertTrue(
            "validate_safety must connect to nodeFinish",
            strategy.edges.any { it.from == validateNode && it.to == strategy.nodeFinish }
        )
        assertTrue(
            "validate_safety must connect to build_remediation",
            strategy.edges.any { it.from == validateNode && it.to == remediateNode }
        )
        assertTrue(
            "validate_safety must connect to safety_block",
            strategy.edges.any { it.from == validateNode && it.to == safetyBlockNode }
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
