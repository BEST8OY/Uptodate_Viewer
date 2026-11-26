"""Canonical entry router for ClinRef AI (mirror of Kotlin `QuestionRouter.kt`).

A turn that asks no clinical question must not enter the evidence graph: Gate 1
(`SafetyValidator.validate_evidence`) hard-fails any answer produced without a tool call, so a
greeting routed through retrieval is intercepted, retried, and finally withheld. The router
therefore answers small talk deterministically before the model runs: zero calls, zero tokens.

Matching is a closed vocabulary, never a heuristic:
  - the normalized question must be exactly one of `_SMALL_TALK_PHRASES`, optionally followed by
    one `_VOCATIVES` word ("hello doctor", "good morning everyone").
  - There is deliberately no prefix match, no word budget, and no keyword scan. A "greeting head
    plus N tail words" rule classifies "hello, pulmonary embolism treatment" as small talk and
    silently swallows the question; an exact match cannot.

Anything the vocabulary does not cover is a clinical question. The failure mode of a missed
greeting is therefore a wasted retrieval turn, never a lost question.
"""

import re

# Deterministic reply for the small-talk path.
CANNED_SMALL_TALK_REPLY = "Hello. Ask me a clinical question and I will look it up in UpToDate."

_SMALL_TALK_PHRASES = frozenset({
    # Greetings
    "hi", "hello", "hey", "greetings",
    "good morning", "good afternoon", "good evening",
    "hi there", "hello there", "hey there",
    # How are you
    "how are you", "how are you doing", "hi how are you", "hello how are you",
    # Thanks
    "thanks", "thank you", "thanks a lot", "thanks so much", "many thanks",
    "thank you so much", "thank you very much",
    # Capability and help
    "who are you", "what can you do", "what do you do", "what are your capabilities",
    "help", "help me",
})

# Address words that may follow a greeting. Exactly one may appear, and only as the last word —
# "hello doctor" is small talk, "hello doctor my patient is allergic" is not.
_VOCATIVES = frozenset({"doctor", "doctors", "dr", "everyone", "all", "sir", "madam", "team", "again"})

_NON_WORD = re.compile(r"[\W_]+", re.UNICODE)


def _normalize(question: str) -> str:
    """Lowercase, and collapse everything that is not a letter or digit to single spaces."""
    return " ".join(_NON_WORD.sub(" ", (question or "").lower()).split())


def small_talk(question: str) -> bool:
    """True only when `question` is a known non-clinical utterance in its entirety."""
    normalized = _normalize(question)
    if not normalized:
        return False
    if normalized in _SMALL_TALK_PHRASES:
        return True
    head, _, last = normalized.rpartition(" ")
    return bool(head) and last in _VOCATIVES and head in _SMALL_TALK_PHRASES
