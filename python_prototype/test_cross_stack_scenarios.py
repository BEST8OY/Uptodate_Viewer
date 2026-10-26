"""Cross-stack behavior parity record (2026-10-26 reconciliation round).

Mirrors line-for-line the Kotlin test
`shared/src/commonTest/kotlin/com/clinref/app/domain/ai/CrossStackScenarioParityTest.kt`
(class `CrossStackScenarioParityTest`). Each of the THREE canonical scenarios
drives the stack's PUBLIC entry points — the entry router (`router.small_talk`),
the deterministic validator (`SafetyValidator.validate`), and the Phase-2
revision template (`system_prompt.build_revision_prompt`) — never the agent
graphs. Question strings, evidence strings, answers, and expected verdicts MUST
stay identical across the two files so any future drift on either stack fails
its own suite.

Scenario corpus (fixed canonical inputs):
  0. greeting + clinical tail in one message   -> small_talk False (never the canned reply)
  1. narration-only answer with no tool calls  -> small_talk False + NO_TOOLS
  2. search-only turn (no section/table fetch) -> NO_SECTIONS
  3. unverified "10 mg" vs "1 mg" evidence     -> UNVERIFIED_QTY + "[UNVERIFIED_QTY]" in the revision prompt
"""

import pytest

from router import small_talk
from safety_validator import (
    NO_SECTIONS_BLOCKED_REASON,
    NO_TOOLS_BLOCKED_REASON,
    PatientProfile,
    SafetyValidator,
    ToolCallRecord,
    TurnContext,
)
from system_prompt import build_revision_prompt

validator = SafetyValidator()


def ctx(
    tool_calls=None,
    answer="",
    tool_results=None,
    user_question="",
    patient_profile=None,
):
    return TurnContext(
        tool_calls=tool_calls or [],
        answer=answer,
        tool_results=tool_results or [],
        user_question=user_question,
        patient_profile=patient_profile,
    )


def section_fetch(result):
    """A successful get_topic_sections_text call over `result`."""
    return ToolCallRecord(
        tool_name="get_topic_sections_text",
        arguments={"topic_id": "1", "section_ids": "H1"},
        result=result,
        success=True,
    )


class TestCrossStackScenarioParity:
    # ── Scenario 0 — greeting followed by a clinical request ───────────

    def test_scenario_0_greeting_with_clinical_tail_routes_clinical(self):
        """The question is clinical even though it opens with a greeting."""
        for question in (
            "hello, pulmonary embolism treatment",
            "thanks, dose of apixaban",
            "hi there, metformin in ckd",
        ):
            assert small_talk(question) is False, question
        # The bare greeting is the only form the entry router answers.
        assert small_talk("hello") is True

    # ── Scenario 1 — narration-only answer with no tool calls ──────────

    def test_scenario_1_narration_only_answer_routes_clinical_and_blocks_no_tools(self):
        question = "Tell me about hypertension management"
        answer = "Hypertension is best managed with lifestyle changes and medication."

        # Entry router: the clinical question is NOT small talk (never bypasses Rule 0).
        assert small_talk(question) is False

        result = validator.validate(ctx(user_question=question, answer=answer))
        assert result.passed is False
        assert len(result.violations) == 1
        assert result.violations[0].code == "NO_TOOLS"
        assert result.violations[0].target == "phase1"
        assert result.blocked_reason == NO_TOOLS_BLOCKED_REASON

    # ── Scenario 2 — search-only turn (no section/table fetch) ─────────

    def test_scenario_2_search_only_turn_routes_clinical_and_blocks_no_sections(self):
        question = "What is the apixaban dose?"

        assert small_talk(question) is False

        result = validator.validate(ctx(
            user_question=question,
            answer="Apixaban is an oral anticoagulant.",
            tool_calls=[ToolCallRecord(
                tool_name="search_topics",
                arguments={"query": "apixaban"},
                result='{"results": []}',
                success=True,
            )],
        ))
        assert result.passed is False
        assert len(result.violations) == 1
        assert result.violations[0].code == "NO_SECTIONS"
        assert result.violations[0].target == "phase1"
        assert result.blocked_reason == NO_SECTIONS_BLOCKED_REASON

    # ── Scenario 3 — unverified "10 mg" against "1 mg" evidence ────────

    def test_scenario_3_unverified_10_mg_versus_evidence_1_mg_blocks_unverified_qty_and_revises(self):
        question = "What is the apixaban dose?"
        evidence = "For atrial fibrillation, give apixaban 1 mg twice daily."
        answer = "Give apixaban 10 mg twice daily."

        assert small_talk(question) is False

        result = validator.validate(ctx(
            user_question=question,
            answer=answer,
            tool_results=[evidence],
            tool_calls=[section_fetch(evidence)],
        ))
        assert result.passed is False
        assert len(result.violations) == 1
        assert result.violations[0].code == "UNVERIFIED_QTY"
        assert result.violations[0].target == "phase2"
        assert result.violations[0].detail == (
            "Unverified clinical quantities found in response: 10 mg. "
            "Ensure every quantity or dosage matches the retrieved database section text exactly."
        )

        prompt = build_revision_prompt(result.violations, draft=answer, evidence_summary=evidence)
        assert "[UNVERIFIED_QTY] Unverified clinical quantities found in response: 10 mg." in prompt


if __name__ == "__main__":
    pytest.main([__file__])
