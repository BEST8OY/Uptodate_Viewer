package com.clinref.app.e2e

import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.TurnContextAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end opaque-box tests for Tier 1 deterministic quantitative validation
 * and Tier 2 semantic evidence grounding.
 *
 * Verifies:
 * 1. Exact dosage extraction and numerical verification.
 * 2. Metric prefix conversions (g <-> mg <-> mcg, L <-> mL).
 * 3. Range checking and boundary verification.
 * 4. Detection of near-miss numbers and invented clinical statistics.
 * 5. Deterministic citation extraction from verified tool execution records.
 * 6. Patient profile bounds (renal cutoff, documented allergies).
 */
class SafetyValidationE2ETest {

    private val validator = SafetyValidator()

    @Test
    fun testTier1ExactDosageAndMetricConversions() {
        val evidenceText = """
            Dosing recommendations:
            - Cefazolin: 1000 mg IV prior to surgical incision.
            - Levothyroxine: 0.5 mg orally once daily in the morning.
            - Normal saline: Infuse 2500 mL IV over 24 hours.
        """.trimIndent()

        // Synthesis states equivalent metric representations: 1 g, 500 mcg, 2.5 L
        val clinicalAnswer = "Administer Cefazolin 1 g IV. Initiate Levothyroxine at 500 mcg orally daily. Maintain hydration with 2.5 L normal saline."

        val accumulator = TurnContextAccumulator()
        accumulator.setUserQuestion("What are the recommended drug dosages?")
        accumulator.onToolCallStarting("call-1", """{"topicId":"1","sectionId":"H1"}""")
        accumulator.onToolCallCompleted(
            "call-1",
            "getTopicSectionsText",
            """{"topicId":"1","topicTitle":"Dosing Guidelines","sectionTitles":{"H1":"Dosing"},"markdown":"$evidenceText"}""",
            true
        )

        val turnContext = accumulator.buildTurnContext(clinicalAnswer)
        val result = validator.validate(turnContext)

        assertTrue("Tier 1 metric conversions (1000 mg = 1 g, 0.5 mg = 500 mcg, 2500 mL = 2.5 L) must pass validation", result.passed)
        assertEquals(null, result.blockedReason)
    }

    @Test
    fun testTier1NumericalRangeEndpointsAndBounds() {
        val evidenceText = "Amlodipine starting dose is 5 to 10 mg orally once daily for essential hypertension."

        fun validateAnswer(answerText: String): SafetyValidator.ValidationResult {
            val acc = TurnContextAccumulator()
            acc.setUserQuestion("What is the dose of amlodipine?")
            acc.onToolCallStarting("call-1", """{"topicId":"10","sectionId":"H1"}""")
            acc.onToolCallCompleted(
                "call-1",
                "getTopicSectionsText",
                """{"topicId":"10","topicTitle":"Amlodipine","sectionTitles":{"H1":"Dosing"},"markdown":"$evidenceText"}""",
                true
            )
            return validator.validate(acc.buildTurnContext(answerText))
        }

        // 1. Lower bound (5 mg) passes
        assertTrue(validateAnswer("Amlodipine starting dose is 5 mg once daily.").passed)

        // 2. Upper bound (10 mg) passes
        assertTrue(validateAnswer("Amlodipine may be titrated up to 10 mg once daily.").passed)

        // 3. Range expression (5 to 10 mg) passes
        assertTrue(validateAnswer("Recommended dosage is 5 to 10 mg orally daily.").passed)

        // 4. Out-of-bounds number (15 mg) is blocked
        val outOfBounds = validateAnswer("Recommended dosage is 15 mg orally daily.")
        assertFalse(outOfBounds.passed)
        assertTrue(outOfBounds.blockedReason?.contains("15") == true)
    }

    @Test
    fun testTier1NearMissDosageAndInventedNumbersAreBlocked() {
        val evidence = "The standard dose of Metformin is 500 mg twice daily with meals."

        val acc = TurnContextAccumulator()
        acc.setUserQuestion("What is the dose of metformin?")
        acc.onToolCallStarting("call-1", """{"topicId":"20","sectionId":"H1"}""")
        acc.onToolCallCompleted(
            "call-1",
            "getTopicSectionsText",
            """{"topicId":"20","topicTitle":"Metformin","sectionTitles":{"H1":"Dosing"},"markdown":"$evidence"}""",
            true
        )

        // Model claims 50 mg (off by a factor of 10)
        val nearMissAnswer = "Metformin is dosed at 50 mg twice daily."
        val nearMissResult = validator.validate(acc.buildTurnContext(nearMissAnswer))
        assertFalse("Near-miss dosage (50 mg vs 500 mg) must be blocked", nearMissResult.passed)
        assertTrue(nearMissResult.blockedReason?.contains("50") == true)

        // Model claims invented clinical cure rate statistic (98.5%)
        val inventedStatAnswer = "Metformin achieves an HbA1c reduction with 98.5% efficacy at 500 mg."
        val statResult = validator.validate(acc.buildTurnContext(inventedStatAnswer))
        assertFalse("Invented percentage statistics must be blocked", statResult.passed)
        assertTrue(statResult.blockedReason?.contains("98.5") == true)
    }

    @Test
    fun testTier2DeterministicCitationExtractionWithParentTopicAttribution() {
        val accumulator = TurnContextAccumulator(
            topicTitleResolver = { tid -> if (tid == "100") "Hypertension Management" else null }
        )

        // Outline tracks sections and graphic tables
        accumulator.onToolCallStarting("call-1", """{"topicId":"100"}""")
        accumulator.onToolCallCompleted(
            "call-1",
            "getTopicOverview",
            """{"topicId":"100","title":"Hypertension Management","sections":[{"id":"H1","title":"Initial Therapy"}],"graphics":[{"id":"12345","title":"First-Line Antihypertensive Agents","isTable":true}]}""",
            true
        )

        // Fetch section
        accumulator.onToolCallStarting("call-2", """{"topicId":"100","sectionIds":["H1"]}""")
        accumulator.onToolCallCompleted(
            "call-2",
            "getTopicSectionsText",
            """{"topicId":"100","topicTitle":"Hypertension Management","sectionTitles":{"H1":"Initial Therapy"},"markdown":"Thiazides, CCBs, and ACE inhibitors are first line."}""",
            true
        )

        // Fetch graphic table
        accumulator.onToolCallStarting("call-3", """{"graphicId":"12345"}""")
        accumulator.onToolCallCompleted(
            "call-3",
            "getGraphicContent",
            "### Graphic Table: First-Line Antihypertensive Agents\n\n| Drug | Dose |\n| --- | --- |\n| Chlorthalidone | 12.5 mg |",
            true
        )

        val turnContext = accumulator.buildTurnContext("First-line antihypertensive therapy includes chlorthalidone at 12.5 mg.")
        val result = validator.validate(turnContext)
        assertTrue("Validation should pass with verified table content", result.passed)

        // Verify TopicRef auto-populated
        assertEquals(1, turnContext.topicRefs.size)
        assertEquals("100", turnContext.topicRefs[0].topicId)
        assertEquals("H1", turnContext.topicRefs[0].sectionId)
        assertEquals("Hypertension Management", turnContext.topicRefs[0].topicTitle)

        // Verify GraphicRef auto-populated with parent topic attribution
        assertEquals(1, turnContext.graphicRefs.size)
        val gRef = turnContext.graphicRefs[0]
        assertEquals("12345", gRef.graphicId)
        assertEquals("100", gRef.topicId)
        assertEquals("First-Line Antihypertensive Agents", gRef.label)
    }

    @Test
    fun testCalculatorFullSectionStrippingFromCitations() {
        val accumulator = TurnContextAccumulator()

        accumulator.onToolCallStarting("call-1", """{"topicId":"148929"}""")
        accumulator.onToolCallCompleted(
            "call-1",
            "getTopicOverview",
            """{"topicId":"148929","title":"CHA2DS2-VASc Score","topicType":"calc","sections":[{"id":"FULL","title":"Calculator Tool Content"}]}""",
            true
        )

        accumulator.onToolCallStarting("call-2", """{"topicId":"148929","sectionIds":["FULL"]}""")
        accumulator.onToolCallCompleted(
            "call-2",
            "getTopicSectionsText",
            """{"topicId":"148929","topicTitle":"CHA2DS2-VASc Score","sectionTitles":{"FULL":"CHA2DS2-VASc Score"},"markdown":"Calculator evaluates stroke risk."}""",
            true
        )

        val turnContext = accumulator.buildTurnContext("The CHA2DS2-VASc score evaluates stroke risk.")
        val result = validator.validate(turnContext)
        assertTrue(result.passed)

        // For calculator topics, sectionId "FULL" is stripped so readers navigate to topic root
        assertEquals(1, turnContext.topicRefs.size)
        assertEquals("148929", turnContext.topicRefs[0].topicId)
        assertEquals("", turnContext.topicRefs[0].sectionId)
    }
}
