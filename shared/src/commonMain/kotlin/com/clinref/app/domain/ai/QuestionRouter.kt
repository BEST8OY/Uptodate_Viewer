package com.clinref.app.domain.ai

/**
 * Entry guard for non-clinical turns.
 *
 * A turn that asks no clinical question must not enter the evidence graph: Gate 1
 * ([SafetyValidator.validateEvidence]) hard-fails any answer produced without a tool call, so a
 * greeting routed through retrieval is intercepted, retried, and finally withheld — burning three
 * LLM calls to produce an error message. The guard therefore answers small talk deterministically
 * before the model runs: zero calls, zero tokens.
 *
 * Matching is a closed vocabulary, never a heuristic:
 * - the normalized message must be exactly one of [SMALL_TALK_PHRASES], optionally followed by one
 *   [VOCATIVES] word ("hello doctor", "good morning everyone").
 * - There is deliberately no prefix match, no word budget, and no keyword scan. A "greeting head
 *   plus N tail words" rule classifies "hello, pulmonary embolism treatment" as small talk and
 *   silently swallows the question; an exact match cannot: the tail is not in the vocabulary, so
 *   the turn takes the clinical pipeline.
 *
 * Anything the vocabulary does not cover is a clinical question. The failure mode of a missed
 * greeting is therefore a wasted retrieval turn, never a lost question.
 */
object QuestionRouter {

    /** Deterministic reply for the small-talk path. */
    const val CANNED_FALLBACK_REPLY = "Hello. Ask me a clinical question and I will look it up in UpToDate."

    private val SMALL_TALK_PHRASES = setOf(
        // Greetings
        "hi", "hello", "hey", "greetings",
        "good morning", "good afternoon", "good evening",
        "hi there", "hello there", "hey there",
        // How are you
        "how are you", "how are you doing", "hi how are you", "hello how are you",
        // Thanks
        "thanks", "thank you", "thanks a lot", "thanks so much", "many thanks",
        "thank you so much", "thank you very much",
        // Capability and help
        "who are you", "what can you do", "what do you do", "what are your capabilities",
        "help", "help me"
    )

    /**
     * Address words that may follow a greeting. Exactly one may appear, and only as the last word —
     * "hello doctor" is small talk, "hello doctor my patient is allergic" is not.
     */
    private val VOCATIVES = setOf("doctor", "doctors", "dr", "everyone", "all", "sir", "madam", "team", "again")

    /**
     * True only when [question] is a known non-clinical utterance in its entirety.
     * Case, punctuation, and whitespace runs are ignored.
     */
    fun smallTalk(question: String): Boolean {
        val normalized = normalize(question)
        if (normalized.isEmpty()) return false
        if (normalized in SMALL_TALK_PHRASES) return true
        val withoutVocative = normalized.substringBeforeLast(' ', missingDelimiterValue = "")
        return withoutVocative.isNotEmpty() &&
            normalized.substringAfterLast(' ') in VOCATIVES &&
            withoutVocative in SMALL_TALK_PHRASES
    }

    /** Lowercase, and collapse everything that is not a letter or digit to single spaces. */
    private fun normalize(question: String): String =
        question.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
