package com.clinref.app.adversarial

import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.SafetyValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdversarialRenalBoundaryTest {

    private val validator = SafetyValidator()

    private fun makeTurn(
        answer: String,
        profile: PatientProfile? = null,
        userQuestion: String = ""
    ) = SafetyValidator.TurnContext(
        toolCalls = listOf(
            SafetyValidator.ToolCallRecord(
                toolName = "getTopicSectionsText",
                arguments = mapOf("sectionId" to "H1"),
                result = answer,
                success = true
            )
        ),
        answer = answer,
        toolResults = listOf(answer),
        fetchedSections = listOf(
            SafetyValidator.FetchedSection(
                topicId = "test",
                topicTitle = "Test Topic",
                sectionId = "H1",
                sectionTitle = "Treatment",
                contentSnippet = answer
            )
        ),
        userQuestion = userQuestion,
        patientProfile = profile
    )

    // 1. Boundary eGFR Testing (15, 25, 29 vs 30 vs 35 vs 60)
    @Test
    fun testEgfrBoundaries() {
        // eGFR < 30 should block NSAIDs
        for (egfr in listOf(15, 25, 29)) {
            val profile = PatientProfile(conditions = listOf("CKD with eGFR $egfr mL/min"))
            val ctx = makeTurn("First-Line: Ibuprofen 400 mg every 6 hours.", profile)
            val res = validator.validate(ctx)
            assertFalse("Expected eGFR $egfr to block ibuprofen", res.passed)
            assertNotNull(res.blockedReason)
            assertTrue(res.blockedReason!!.contains("ibuprofen is contraindicated"))
        }

        // eGFR >= 30 should not block as severe renal impairment
        for (egfr in listOf(30, 35, 60)) {
            val profile = PatientProfile(conditions = listOf("eGFR $egfr mL/min"))
            val ctx = makeTurn("First-Line: Ibuprofen 400 mg every 6 hours.", profile)
            val res = validator.validate(ctx)
            assertTrue("Expected eGFR $egfr to pass ibuprofen", res.passed)
        }
    }

    // 2. Contraindicated drugs in severe renal impairment
    @Test
    fun testContraindicatedDrugsBlocked() {
        val profile = PatientProfile(conditions = listOf("eGFR 22 mL/min"))
        val drugs = listOf("ibuprofen", "indomethacin", "ketorolac", "metformin")
        for (drug in drugs) {
            val ctx = makeTurn("First-Line Regimen: $drug 500 mg daily.", profile)
            val res = validator.validate(ctx)
            assertFalse("Expected $drug to be blocked in severe renal impairment", res.passed)
            assertTrue(res.blockedReason!!.contains("contraindicated"))
        }
    }

    // 3. Avoidance of contraindicated drugs should pass
    @Test
    fun testAvoidancePasses() {
        val profile = PatientProfile(conditions = listOf("eGFR 18 mL/min"))
        val ctx = makeTurn(
            "Avoid ibuprofen due to severe renal impairment. Administer acetaminophen 500 mg.",
            profile
        )
        val res = validator.validate(ctx)
        assertTrue("Avoidance of ibuprofen should pass", res.passed)
    }

    // 4. Colchicine: unadjusted blocked, adjusted with 'renal dose' passes
    @Test
    fun testColchicineAdjustments() {
        val profile = PatientProfile(conditions = listOf("eGFR 20 mL/min"))

        // Unadjusted -> blocked
        val ctxUnadjusted = makeTurn("First-Line: Colchicine 1.2 mg followed by 0.6 mg.", profile)
        val resUnadjusted = validator.validate(ctxUnadjusted)
        assertFalse("Unadjusted colchicine should be blocked", resUnadjusted.passed)
        assertTrue(resUnadjusted.blockedReason!!.contains("colchicine requires renal dosage adjustment"))

        // 'renal dose' -> passes
        val ctxRenalDose = makeTurn("Colchicine 0.3 mg daily with renal dose adjustment.", profile)
        val resRenalDose = validator.validate(ctxRenalDose)
        assertTrue("Colchicine with 'renal dose' should pass", resRenalDose.passed)

        // 'CrCl < 30' -> passes
        val ctxCrCl = makeTurn("Colchicine 0.3 mg daily when CrCl < 30 mL/min.", profile)
        val resCrCl = validator.validate(ctxCrCl)
        assertTrue("Colchicine with 'CrCl < 30' should pass", resCrCl.passed)
    }

    // 5. Adversarial Check: 'reduce dose' phrasing on colchicine
    @Test
    fun testColchicineReduceDosePhrasing() {
        val profile = PatientProfile(conditions = listOf("eGFR 20 mL/min"))
        val ctx = makeTurn("Colchicine 0.3 mg daily, reduce dose due to low GFR.", profile)
        val res = validator.validate(ctx)
        // 'reduce dose' is recognized by RENAL_ADJUSTMENT_KEYWORDS_REGEX (mirrors SafetyValidatorTest rule-4 test)
        assertTrue(
            "'reduce dose' phrasing should satisfy colchicine renal adjustment",
            res.passed
        )
    }

    // 6. Adversarial Check: 'Stage 4 CKD' phrasing in conditions
    @Test
    fun testStage4CkdPhrasing() {
        val profile = PatientProfile(conditions = listOf("Stage 4 CKD"))
        val ctx = makeTurn("First-Line Regimen: Ibuprofen 600 mg TID.", profile)
        val res = validator.validate(ctx)
        // 'Stage 4 CKD' in conditions is detected as severe renal impairment (mirrors SafetyValidatorTest rule-4 test)
        assertTrue(
            "Stage 4 CKD condition must trigger severe-renal-impairment detection for ibuprofen",
            res.blockedReason!!.contains("renal contraindication", ignoreCase = true) &&
                res.blockedReason!!.contains("ibuprofen", ignoreCase = true)
        )
        assertFalse(
            "Ibuprofen must be blocked under Stage 4 CKD phrasing",
            res.passed
        )
    }

    // 7. Multi-threaded validator concurrency
    @Test
    fun testValidatorConcurrency() = runBlocking {
        val testProfiles = listOf(
            PatientProfile(conditions = listOf("eGFR 15 mL/min")),
            PatientProfile(conditions = listOf("eGFR 60 mL/min")),
            PatientProfile(allergies = listOf("penicillin")),
            null
        )

        val deferreds = (1..500).map { i ->
            async(Dispatchers.Default) {
                val profile = testProfiles[i % testProfiles.size]
                val drug = if (i % 2 == 0) "Ibuprofen 400 mg" else "Lisinopril 10 mg"
                val ctx = makeTurn("First-Line: $drug daily.", profile)
                val res = validator.validate(ctx)
                if (profile?.conditions?.firstOrNull() == "eGFR 15 mL/min" && drug.startsWith("Ibuprofen")) {
                    assertFalse(res.passed)
                } else if (profile?.allergies?.firstOrNull() == "penicillin" && drug.startsWith("Ibuprofen")) {
                    // Ibuprofen is in aspirin/nsaid cross-reactivity, not penicillin
                    assertTrue(res.passed)
                }
                res
            }
        }

        val results = deferreds.awaitAll()
        assertEquals(500, results.size)
    }
}
