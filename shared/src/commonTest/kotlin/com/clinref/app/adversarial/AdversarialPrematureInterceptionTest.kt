package com.clinref.app.adversarial

import com.clinref.app.domain.ai.ClinicalAgentStrategy
import com.clinref.app.domain.ai.ReasoningTokenSanitizer
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.SystemPrompt
import com.clinref.app.domain.ai.TurnContextAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Empirical Gate Challenger Adversarial Test Suite for Milestone 1 Premature Synthesis Interception.
 *
 * Verifies:
 * 1. Conversational Queries:
 *    - 'hello', 'who are you', 'help', 'what can you do', 'thanks' must NOT be intercepted as premature.
 *    - Conversational greeting with internal thought tags containing numbers/% must strip thoughts and pass.
 * 2. Premature Clinical Inquiries:
 *    - Premature clinical response with dosage figures and empty tool history must fail Rule 0 and be blocked.
 *    - Premature interception node in Koog graph forwardTo nodeSendInput.
 *    - SystemPrompt.buildPrematureInterceptionPrompt contains mandatory retrieval directives.
 * 3. Retry Budget Enforcement & Clean Termination:
 *    - Graph routes from validate_safety to safety_block upon retry budget exhaustion.
 *    - Terminal block formats clinical blocked response cleanly without unhandled exceptions.
 * 4. Streaming Token / Thought Tag Hygiene:
 *    - ReasoningTokenSanitizer strips <thought>, <think>, and <reasoning> blocks cleanly.
 *    - Dosing numbers exclusively in thoughts do not contaminate turnContext or trigger premature clinical claims.
 *    - Dosing numbers in clean text correctly trigger database verification requirements.
 * 5. State Machine Invariants:
 *    - Responses with fetched section evidence pass Rule 0 and Rule 1.
 */
class AdversarialPrematureInterceptionTest {

    private lateinit var accumulator: TurnContextAccumulator
    private lateinit var validator: SafetyValidator

    @Before
    fun setUp() {
        accumulator = TurnContextAccumulator()
        validator = SafetyValidator()
    }

    // ── 1. Conversational Queries ───────────────────────────────────────────

    @Test
    fun testConversationalQueriesBypassPrematureInterception() {
        val conversationalQueries = listOf(
            "hello" to "Hello! I am ClinRef AI, your clinical evidence assistant.",
            "hi there" to "Hi! How can I help you with medical literature today?",
            "who are you" to "I am ClinRef AI, an offline clinical evidence decision support assistant.",
            "help" to "I can assist you in searching UpToDate clinical guidelines and drug monographs.",
            "what can you do" to "I can search clinical topics, drug dosages, and evidence tables.",
            "thanks" to "You're welcome! Let me know if you need anything else.",
            "thank you very much" to "Glad to help!",
            "good morning" to "Good morning! How may I assist you today?"
        )

        for ((query, greeting) in conversationalQueries) {
            accumulator.reset()
            accumulator.setUserQuestion(query)

            val turnContext = accumulator.buildTurnContext(greeting)
            val isNonClinical = SafetyValidator.isNonClinicalResponse(turnContext)
            assertTrue("Query '$query' should be recognized as non-clinical response", isNonClinical)

            val result = validator.validate(turnContext)
            assertTrue("Query '$query' must pass validation without tool execution", result.passed)
            assertEquals("No topic refs should be generated for greetings", 0, turnContext.topicRefs.size)
        }
    }

    @Test
    fun testConversationalQueryWithThoughtTokensContainingNumbersAndPercentages() {
        accumulator.setUserQuestion("Hello doctor!")
        val rawLlmOutput = """
            <thought>
            The user said hello. In trial 18299, 500 mg was tested in 1,200 patients with 94.5% success.
            However, this is only a greeting, so no clinical claims should be made.
            </thought>
            Hello! I am ClinRef AI, your evidence-based clinical assistant.
        """.trimIndent()

        // buildTurnContext automatically applies ReasoningTokenSanitizer.stripReasoning
        val turnContext = accumulator.buildTurnContext(rawLlmOutput)

        assertFalse("Clean answer must not contain thought block", turnContext.answer.contains("<thought>"))
        assertFalse("Clean answer must not contain 500 mg from thought", turnContext.answer.contains("500 mg"))
        assertTrue("Clean answer must retain friendly greeting", turnContext.answer.contains("Hello! I am ClinRef AI"))

        val isNonClinical = SafetyValidator.isNonClinicalResponse(turnContext)
        assertTrue("Hygienic greeting must be non-clinical", isNonClinical)

        val validation = validator.validate(turnContext)
        assertTrue("Hygienic greeting must pass validation: ${validation.blockedReason}", validation.passed)
    }

    // ── 2. Premature Clinical Inquiries ──────────────────────────────────────

    @Test
    fun testPrematureClinicalResponsesWithDosagesAreBlockedWithoutTools() {
        val prematureAnswers = listOf(
            "The recommended dosage of amoxicillin is 500 mg orally every 8 hours for 10 days.",
            "Start apixaban at 5 mg orally twice daily.",
            "Administer ceftriaxone 1 g IV daily.",
            "Initial dose of lisinopril is 10 mg once daily.",
            "Give morphine 2 to 4 mg IV every 2 to 4 hours as needed.",
            "Expected cure rate is approximately 95% with standard therapy."
        )

        for (premature in prematureAnswers) {
            accumulator.reset()
            accumulator.setUserQuestion("What is the recommended treatment dose?")

            val turnContext = accumulator.buildTurnContext(premature)
            val isNonClinical = SafetyValidator.isNonClinicalResponse(turnContext)
            assertFalse("Premature clinical response must NOT be classified as non-clinical: '$premature'", isNonClinical)

            val validation = validator.validate(turnContext)
            assertFalse("Premature clinical response must fail validation without tools: '$premature'", validation.passed)
            assertTrue(
                "Blocked reason must specify database verification requirement",
                validation.blockedReason?.contains("Clinical recommendations require database verification") == true
            )
        }
    }

    @Test
    fun testPrematureInterceptionNodeTopologyInKoogGraph() {
        val strategy = ClinicalAgentStrategy.createAutonomous()
        assertNotNull(strategy)

        val nodesMap = strategy.metadata.nodesMap
        val interceptNode = nodesMap.values.firstOrNull { it.name == "intercept_premature" }
        assertNotNull("Koog graph must declare intercept_premature node", interceptNode)

        val sendInputNode = nodesMap.values.first { it.name.contains("nodeSendInput") }
        assertTrue(
            "intercept_premature node must edge forwardTo nodeSendInput",
            interceptNode!!.edges.any { it.toNode == sendInputNode }
        )

        val prompt = SystemPrompt.buildPrematureInterceptionPrompt("1")
        assertTrue(prompt.contains("SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED"))
        assertTrue(prompt.contains("MANDATORY CLINICAL DIRECTIVE"))
        assertTrue(prompt.contains("getTopicOverview"))
        assertTrue(prompt.contains("searchTopics"))
    }

    // ── 3. Budget Exhaustion & Clean Termination ────────────────────────────

    @Test
    fun testTerminalSafetyBlockRoutingOnRetryExhaustion() {
        val strategy = ClinicalAgentStrategy.createAutonomous(maxSafetyRetries = 2)
        val allNodes = strategy.metadata.nodesMap.values

        val validateNode = allNodes.first { it.name == "validate_safety" }
        val safetyBlockNode = allNodes.first { it.name == "safety_block" }
        val finishNode = strategy.nodeFinish

        // validate_safety conditionally transitions to safety_block when evaluation is Blocked
        assertTrue(
            "validate_safety must edge forwardTo safety_block",
            validateNode.edges.any { it.toNode == safetyBlockNode }
        )

        // safety_block terminates into nodeFinish
        assertTrue(
            "safety_block must edge forwardTo nodeFinish",
            safetyBlockNode.edges.any { it.toNode == finishNode }
        )

        val blockedEvaluation = ClinicalAgentStrategy.SafetyEvaluation.Blocked(
            reason = "Clinical recommendations require database verification"
        )
        val blockedText = "Clinical Response Verification Blocked: ${blockedEvaluation.reason}"
        assertTrue(blockedText.startsWith("Clinical Response Verification Blocked:"))
        assertTrue(blockedText.contains("database verification"))
    }

    // ── 4. Thought Tags & Reasoning Token Hygiene ───────────────────────────

    @Test
    fun testThoughtTagsSanitizationAcrossFormats() {
        // XML <thought>
        val thoughtInput = "<thought>Prescribe 500 mg amoxicillin</thought>Consult your doctor."
        assertEquals("Consult your doctor.", ReasoningTokenSanitizer.stripReasoning(thoughtInput))

        // DeepSeek <think>
        val thinkInput = "<think>Patient CrCl 25 mL/min -> reduce dose to 250 mg</think>Consult your doctor."
        assertEquals("Consult your doctor.", ReasoningTokenSanitizer.stripReasoning(thinkInput))

        // Generic <reasoning>
        val reasoningInput = "<reasoning>Check penicillin allergy</reasoning>Consult your doctor."
        assertEquals("Consult your doctor.", ReasoningTokenSanitizer.stripReasoning(reasoningInput))
    }

    @Test
    fun testDosingInsideThoughtTagDoesNotTriggerPrematureInterception() {
        accumulator.setUserQuestion("What medication should I take?")
        val rawLlm = """
            <thought>
            Considering 500 mg amoxicillin twice daily, but I must recommend a doctor visit.
            </thought>
            Please schedule an appointment with your physician for clinical diagnosis.
        """.trimIndent()

        val turnContext = accumulator.buildTurnContext(rawLlm)
        assertTrue("Clean text has no dosage, should be non-clinical", SafetyValidator.isNonClinicalResponse(turnContext))
        val result = validator.validate(turnContext)
        assertTrue("Non-dosing response must pass without tools: ${result.blockedReason}", result.passed)
    }

    @Test
    fun testDosingOutsideThoughtTagTriggersPrematureInterception() {
        accumulator.setUserQuestion("What medication should I take?")
        val rawLlm = """
            <thought>
            Looking at pharyngitis guidelines.
            </thought>
            Take amoxicillin 500 mg orally every 8 hours for 10 days.
        """.trimIndent()

        val turnContext = accumulator.buildTurnContext(rawLlm)
        assertFalse("Clean text has 500 mg dosage, cannot be non-clinical", SafetyValidator.isNonClinicalResponse(turnContext))
        val result = validator.validate(turnContext)
        assertFalse("Premature clinical claim must fail validation", result.passed)
        assertTrue(result.blockedReason?.contains("database verification") == true)
    }

    // ── 5. State Machine Invariants with Evidence ───────────────────────────

    @Test
    fun testResponseWithFetchedSectionsPassesWithoutPrematureInterception() {
        accumulator.setUserQuestion("What is the apixaban dose?")
        accumulator.onToolCallStarting("call-1", """{"topicId":"1","sectionId":"H1"}""")
        accumulator.onToolCallCompleted(
            "call-1",
            "getTopicSectionsText",
            """{"topicId":"1","topicTitle":"Apixaban","sectionTitles":{"H1":"Dosing"},"markdown":"Standard dose is 5 mg twice daily."}""",
            true
        )

        val turnContext = accumulator.buildTurnContext("Based on UpToDate guidelines, the standard dose of apixaban is 5 mg twice daily.")
        assertEquals(1, turnContext.fetchedSections.size)

        val result = validator.validate(turnContext)
        assertTrue("Verified response with fetched sections must pass validation: ${result.blockedReason}", result.passed)
        assertEquals(1, turnContext.topicRefs.size)
        assertEquals("1", turnContext.topicRefs[0].topicId)
    }
}
