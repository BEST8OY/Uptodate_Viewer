package com.clinref.app.domain.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entry-router semantics (python router.py parity): routing happens on the
 * QUESTION against a closed phrase vocabulary. Everything the vocabulary does not
 * cover — including a greeting followed by a clinical request — enters the
 * clinical graph.
 */
class QuestionRouterTest {

    // ══════════════════════════════════════════════════════════════════
    // Greetings / thanks
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `bare greetings and thanks classify as small talk`() {
        for (phrase in listOf(
            "hi", "hello", "hey", "thanks", "thank you",
            "greetings", "good morning", "good afternoon", "good evening"
        )) {
            assertTrue("'$phrase' must be small talk", QuestionRouter.smallTalk(phrase))
        }
    }

    @Test
    fun `case and whitespace variants of greetings classify as small talk`() {
        assertTrue(QuestionRouter.smallTalk("Hi"))
        assertTrue(QuestionRouter.smallTalk("HELLO"))
        assertTrue(QuestionRouter.smallTalk("Good MORNING"))
        assertTrue(QuestionRouter.smallTalk("  hi  "))
        // Punctuation and whitespace runs are normalized away.
        assertTrue(QuestionRouter.smallTalk("Hi!"))
        assertTrue(QuestionRouter.smallTalk("Thanks!"))
        assertTrue(QuestionRouter.smallTalk("Hello   there"))
    }

    @Test
    fun `one address word may follow a greeting`() {
        assertTrue(QuestionRouter.smallTalk("hello doctor"))
        assertTrue(QuestionRouter.smallTalk("Hello, doctor."))
        assertTrue(QuestionRouter.smallTalk("Hey — doctor!"))
        assertTrue(QuestionRouter.smallTalk("Good morning everyone"))
        assertTrue(QuestionRouter.smallTalk("thanks doctor"))
        assertTrue(QuestionRouter.smallTalk("hi everyone"))
        assertTrue(QuestionRouter.smallTalk("hello again"))
        assertTrue(QuestionRouter.smallTalk("hello there again"))
    }

    @Test
    fun `only a vocabulary word may follow a greeting`() {
        // The trailing token must itself be an address word; any other tail is clinical content.
        assertFalse(QuestionRouter.smallTalk("hi doctor friend"))
        assertFalse(QuestionRouter.smallTalk("thanks doctor very"))
        assertFalse(QuestionRouter.smallTalk("hello 5 mg"))
    }

    @Test
    fun `greeting followed by a clinical request routes to the clinical graph`() {
        // The regression class the closed vocabulary exists to prevent: a greeting head plus a
        // short clinical tail must never be answered with the canned reply.
        assertFalse(QuestionRouter.smallTalk("hello, pulmonary embolism treatment"))
        assertFalse(QuestionRouter.smallTalk("thanks, dose of apixaban"))
        assertFalse(QuestionRouter.smallTalk("hi there, metformin in ckd"))
        assertFalse(QuestionRouter.smallTalk("hello doctor, what is the apixaban dose"))
        assertFalse(QuestionRouter.smallTalk("hey what is the dose of aspirin"))
        assertFalse(QuestionRouter.smallTalk("hello doctor, my patient is allergic to penicillin"))
        assertFalse(QuestionRouter.smallTalk("Hi doctor, how are you today?"))
        assertFalse(QuestionRouter.smallTalk("hello how are you there"))
    }

    @Test
    fun `greeting head must end at a word boundary`() {
        assertFalse("Word-boundary rule: 'hiccup' is not a greeting", QuestionRouter.smallTalk("hiccup"))
        assertFalse(QuestionRouter.smallTalk("hither"))
        assertFalse(QuestionRouter.smallTalk("thanksa"))
    }

    // ══════════════════════════════════════════════════════════════════
    // Capability phrases
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `exact capability phrases classify as small talk`() {
        assertTrue(QuestionRouter.smallTalk("who are you"))
        assertTrue(QuestionRouter.smallTalk("What can you do"))
        assertTrue(QuestionRouter.smallTalk("what are your capabilities"))
        assertTrue(QuestionRouter.smallTalk("help"))
        assertTrue(QuestionRouter.smallTalk("Help me"))
        assertTrue(QuestionRouter.smallTalk("  HELP  "))
        assertTrue(QuestionRouter.smallTalk("who are you?"))
    }

    @Test
    fun `capability phrases require the exact full string`() {
        assertFalse(QuestionRouter.smallTalk("help me out"))
        assertFalse(QuestionRouter.smallTalk("what can you do for hypertension"))
        assertFalse(QuestionRouter.smallTalk("who are you and what is the dose"))
    }

    // ══════════════════════════════════════════════════════════════════
    // Clinical questions never route to small talk
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `clinical questions route to the clinical graph`() {
        assertFalse(QuestionRouter.smallTalk("What is the dose of aspirin for a 45 year old?"))
        assertFalse(QuestionRouter.smallTalk("metformin in stage 4 ckd"))
        assertFalse(QuestionRouter.smallTalk(" give me the dosing "))
        assertFalse(QuestionRouter.smallTalk("regarding hypertension management in elderly patients"))
    }

    @Test
    fun `empty questions never classify as small talk`() {
        assertFalse(QuestionRouter.smallTalk(""))
        assertFalse(QuestionRouter.smallTalk("   "))
    }

    @Test
    fun `canned fallback reply matches expected string`() {
        assertEquals(
            "Hello. Ask me a clinical question and I will look it up in UpToDate.",
            QuestionRouter.CANNED_FALLBACK_REPLY
        )
    }
}
