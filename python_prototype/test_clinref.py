"""Comprehensive tests for ClinRef Python prototype.

Covers: safety_validator, html_parser, tools, agent.
Database-dependent tests are skipped if no DB is available.
"""

import json
import logging
import re
from unittest.mock import MagicMock, patch

import pytest

import safety_validator
from safety_validator import (
    FetchedSection,
    GraphicRef,
    PatientProfile,
    SafetyValidator,
    ToolCallRecord,
    TopicRef,
    TurnContext,
    ValidationResult,
    Violation,
)
from bs4 import BeautifulSoup
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from system_prompt import (
    build_correction_prompt,
    build_system_prompt,
    extract_patient_context,
)
from html_parser import (
    extract_graphics_from_outline,
    extract_outline_sections,
    extract_related_topics,
    extract_section_html,
    html_to_markdown,
    table_to_markdown,
)


# ═══════════════════════════════════════════════════════════════════════
# safety_validator.py
# ═══════════════════════════════════════════════════════════════════════


class TestToolCallRequired:
    """Rule 0: Intent-aware tool call check."""

    def test_no_tool_calls_clinical_blocked(self):
        ctx = TurnContext(tool_calls=[], answer="The dose is 5 mg.")
        result = SafetyValidator().validate(ctx)
        assert not result.passed
        assert "Clinical recommendations require database verification" in result.blocked_reason

    def test_no_tool_calls_answer_side_greeting_blocked(self):
        """Change 1: the answer-side conversational bypass is deleted — a zero-tool-call
        turn is NO_TOOLS regardless of its content; greetings route through the entry
        small-talk router, so the validator has deterministic block semantics."""
        ctx = TurnContext(tool_calls=[], answer="Hello! I'm ClinRef AI.", user_question="hello")
        result = SafetyValidator().validate(ctx)
        assert not result.passed
        assert result.violations[0].code == "NO_TOOLS"
        assert "No database tools were executed" in result.blocked_reason

    def test_with_tool_calls_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="search_topics", arguments={}, result="{}", success=True)],
            answer="test",
        )
        result = SafetyValidator().validate(ctx)
        # May fail on later rules, but not rule 0
        if not result.passed:
            assert "Clinical recommendations require database verification" not in (result.blocked_reason or "")


class TestSectionContentRequired:
    """Rule 1: Section or table content required."""

    def test_only_search_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="search_topics", arguments={}, result="{}", success=True)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is not None
        assert not result.passed

    def test_section_text_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is None

    def test_batch_section_text_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is None

    def test_graphic_content_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_graphic_content", arguments={}, result="...", success=True)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is None

    def test_failed_section_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Section not found.", success=False)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is not None
        assert not result.passed

    def test_overview_alone_blocked(self):
        # A topic outline lists section titles, not clinical text. Only the accumulator's
        # FULL section (calculator topics) or a real section/table fetch counts.
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_overview", arguments={}, result="{}", success=True)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is not None
        assert not result.passed

    def test_calculator_overview_full_section_passes(self):
        # Calculator topics return their full content from the overview, which the
        # accumulator records as a fetched FULL section.
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_overview", arguments={}, result="{}", success=True)],
            answer="Framingham risk is 12 percent.",
            fetched_sections=[
                FetchedSection(topic_id="95866", section_id="FULL", section_title="Calculator Tool Content",
                               content_snippet="Framingham risk is 12 percent.")
            ],
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is None

    def test_failed_overview_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_overview", arguments={}, result="{}", success=False)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is not None
        assert not result.passed


class TestInventedNumbers:
    """Rule 2: No invented clinical quantities."""

    def test_age_threshold_verifies_against_source_phrase(self):
        """'1 year' must verify against '1 year of age' — age cutoffs decide pediatric eligibility."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Honey", success=True)],
            answer="Honey is an option for children 1 year and older.",
            tool_results=["In children 1 year of age, we use honey to treat cough."],
        )
        assert SafetyValidator()._validate_no_invented_numbers(ctx) is None

    def test_invented_age_threshold_blocks(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Honey", success=True)],
            answer="Honey is an option for children 2 years and older.",
            tool_results=["In children 1 year of age, we use honey to treat cough."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert "2 years" in result.blocked_reason

    def test_hyphenated_and_spaced_year_forms_are_one_value(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="risk", success=True)],
            answer="This is a 10-year risk estimate.",
            tool_results=["Reported as a 10 year risk estimate."],
        )
        assert SafetyValidator()._validate_no_invented_numbers(ctx) is None

    def test_matching_quantity_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="The dose is 5 mg.", success=True)],
            answer="The dose is 5 mg.",
            tool_results=["The dose is 5 mg."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_unmatched_quantity_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="The dose is 5 mg.", success=True)],
            answer="The dose is 10 mg.",
            tool_results=["The dose is 5 mg."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed
        assert "10 mg" in result.blocked_reason

    def test_user_question_quantity_allowed(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="The patient weighs 70 kg and eGFR is 25 ml/min.",
            tool_results=["..."],
            user_question="Patient weighs 70 kg, eGFR is 25 ml/min.",
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_structural_numbers_not_flagged(self):
        """List markers, section numbers, years should not be flagged."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="See section 3.1. The year 2024 guidelines recommend 5 mg.",
            tool_results=["5 mg is recommended."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_no_tool_results_blocks_invented_quantities(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="The dose is 10 mg.",
            tool_results=[],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed
        assert "10 mg" in result.blocked_reason

    def test_boundary_check_no_partial_match(self):
        """'12' should not match as part of '123'."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Value: 123 mg", success=True)],
            answer="The value is 12 mg.",
            tool_results=["Value: 123 mg"],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed

    def test_numeric_only_match_in_tool_text_is_blocked(self):
        """'60 mL' should NOT pass if '60' appears in tool text with other units."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Administer 60 units.", success=True)],
            answer="Give 60 mL.",
            tool_results=["Administer 60 units."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed

    def test_range_bounds_both_verified(self):
        """'5-10 mg' should pass when text contains both bounds ('5 to 10 mg')."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Give 5-10 mg daily.",
            tool_results=["Dose range: 5 to 10 mg daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_decimal_boundary_does_not_match_subdecimal(self):
        """'2 mg' should NOT match as part of '0.2 mg' or '2.5 mg'."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="The dose is 2 mg.",
            tool_results=["The dose is 0.2 mg daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed

    def test_trailing_decimal_zero_matches_integer(self):
        """'2.0 mU/L' in answer should pass when tool text says '2 mU/L'."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Target TSH is 2.0 mU/L.",
            tool_results=["Target TSH is 2 mU/L in this group."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_english_slashes_not_flagged_as_clinical_quantities(self):
        """'step 1 and/or step 2' should not be flagged as an invented unit."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Follow step 1 and/or step 2.",
            tool_results=["Guidelines outline specific clinical protocols."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_metric_prefix_mismatch_grams_and_milligrams_is_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Administer ceftriaxone 1 g IV daily.",
            tool_results=["Ceftriaxone dosage is 1,000 mg intravenously once daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed
        assert "1 g" in result.blocked_reason

    def test_metric_prefix_mismatch_micrograms_and_milligrams_is_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Initial dose is 500 mcg daily.",
            tool_results=["Recommended starting dose is 0.5 mg daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed
        assert "500 mcg" in result.blocked_reason

    def test_frequency_slashes_not_flagged(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Take 1 tablet/day or 2 times/day.",
            tool_results=["Take 1 tablet/day or 2 times/day with water."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None


class TestDecimalCommaVerificationProbes:
    """Decimal-comma / thousand-separator verification probes (pre-'Change 6' baseline).

    Pins the CURRENT Rule-2 verdicts for loosely formatted numbers so the planned
    value+unit matching change (Change 6) has a recorded baseline. The pass-branches
    marked as holes must be closed — they let a 1000x-overdosed decimal-comma spelling
    verify against thousand-separated evidence.
    """

    @staticmethod
    def _validate(answer: str, tool_text: str):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result=tool_text, success=True)],
            answer=answer,
            tool_results=[tool_text],
        )
        return SafetyValidator()._validate_no_invented_numbers(ctx)

    def test_probe_a_thousand_separated_answer_not_verified_by_decimal_prefix(self):
        """(a) "1,500 mg" must NOT verify via a plain "1.5" in the evidence.

        Already correctly blocked today: the 1.5 readback inside _get_metric_equivalents
        only yields candidates "0.0015"/"1500", none of which match "1.5".
        """
        for evidence in ("1.5% of patients responded.", "1.5 mg daily.", "TSH greater than 1.5 mU/L."):
            assert self._validate("Take 1,500 mg daily.", evidence) is not None, evidence

    def test_probe_b_mg_to_mcg_equal_dose_blocked_without_explicit_unit(self):
        """Under Change 6: unit-equivalent generation is deleted. Exact value+unit matching."""
        assert self._validate("Dose: 0.002 mg.", "Threshold: 2 mcg.") is not None
        assert self._validate("Dose: 2 mcg.", "Dose: 0.002 mg.") is not None

    def test_probe_b_hundredfold_mg_mismatch_hole_closed(self):
        """0.002 mg vs 2 mg — 1000x-mismatched mg doses are blocked under Change 6."""
        assert self._validate("Dose: 0.002 mg.", "Threshold: 2 mg.") is not None

    def test_probe_b_neighbor_10x_mismatch_still_blocked(self):
        """Digit-free neighbors stay blocked: 0.002 mg != 0.2 mg."""
        assert self._validate("Dose: 0.002 mg.", "Threshold: 0.2 mg.") is not None

    def test_probe_c_decimal_comma_hundredfold_hole_closed(self):
        """(c) "1,5 mg" (1.5 mg) vs "1,500 mg" (1500 mg) — 1000x hole is closed."""
        assert self._validate("Take 1,5 mg daily.", "Dose: 1,500 mg daily.") is not None

    def test_probe_c_plain_1500_evidence_hole_closed(self):
        assert self._validate("Take 1,5 mg daily.", "Dose: 1500 mg daily.") is not None
        assert self._validate("Take 1.5 mg daily.", "Dose: 1,500 mg daily.") is not None

    def test_metric_prefix_mismatch_blocked_without_explicit_evidence(self):
        assert self._validate(
            "Administer ceftriaxone 1 g IV daily.",
            "Ceftriaxone dosage is 1,000 mg intravenously once daily.",
        ) is not None
        assert self._validate(
            "Initial dose is 500 mcg daily.",
            "Recommended starting dose is 0.5 mg daily.",
        ) is not None


class TestGraphicInterpretation:
    """Rule 3: No graphic interpretation."""

    def test_no_visual_language_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="The recommended treatment is aspirin.",
        )
        result = SafetyValidator()._validate_no_graphic_interpretation(ctx)
        assert result is None

    def test_visual_language_in_tool_text_passes(self):
        """Quoting retrieved text that contains visual language should pass."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="The x-ray shows opacity.",
            tool_results=["The x-ray shows opacity in the lower lobe."],
        )
        result = SafetyValidator()._validate_no_graphic_interpretation(ctx)
        assert result is None

    def test_visual_language_not_in_tool_text_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="The image shows a mass.",
            tool_results=["Normal lung fields."],
        )
        result = SafetyValidator()._validate_no_graphic_interpretation(ctx)
        assert result is not None
        assert not result.passed

    def test_figure_language_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="The figure demonstrates a fracture.",
            tool_results=["No fracture mentioned."],
        )
        result = SafetyValidator()._validate_no_graphic_interpretation(ctx)
        assert result is not None
        assert not result.passed

    def test_empty_tool_results_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="The scan reveals abnormality.",
            tool_results=[],
        )
        result = SafetyValidator()._validate_no_graphic_interpretation(ctx)
        assert result is not None
        assert not result.passed


class TestFullValidation:
    """Integration tests for SafetyValidator.validate()."""

    def test_successful_turn(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={"section_id": "H1"}, result="Dose is 5 mg.", success=True)],
            answer="The dose is 5 mg.",
            tool_results=["Dose is 5 mg."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            structured_topic_refs=[TopicRef(topic_id="1", section_id="H1", label="Dosing", topic_title="Drug X")],
        )
        result = SafetyValidator().validate(ctx)
        assert result.passed
        assert len(result.topic_refs) == 1

    def test_multiple_rule_failures_collect_all(self):
        """Change 2 collect-all: Rule 2 (UNVERIFIED_QTY) and Rule 3 (VISUAL) both fire on
        one turn — no short-circuit — and the blocked reason joins both textures with " | "
        in rule order."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Routine follow-up.", success=True)],
            answer="Efficacy is 99.4% and the scan reveals a mass.",
            tool_results=["Routine follow-up."],
        )
        result = SafetyValidator().validate(ctx)
        assert not result.passed
        assert [v.code for v in result.violations] == ["UNVERIFIED_QTY", "VISUAL"]
        assert "99.4%" in result.blocked_reason
        assert "visual details" in result.blocked_reason
        # " | " separator between the two textures, Rule 2 before Rule 3
        assert result.blocked_reason.index("Unverified clinical quantities") < result.blocked_reason.index(" | ")
        assert result.blocked_reason.index("describes visual details") > result.blocked_reason.index(" | ")
        assert result.blocked_reason.count(" | ") >= 1

    def test_answer_side_greeting_without_tools_blocks(self):
        """Change 1 collect-all: greetings WITHOUT tool calls block (NO_TOOLS); the
        former intent-detection bypass never fires from answer content."""
        ctx = TurnContext(
            tool_calls=[],
            answer="Hello! I'm ClinRef AI, a clinical reference assistant.",
            user_question="hello",
        )
        result = SafetyValidator().validate(ctx)
        assert not result.passed
        assert all(v.code == "NO_TOOLS" for v in result.violations)






# ═══════════════════════════════════════════════════════════════════════
# router.py — Change 1 entry router
# ═══════════════════════════════════════════════════════════════════════


class TestSmallTalkRouter:
    """Change 1: routing happens on the QUESTION at entry — closed phrase vocabulary only."""

    @pytest.mark.parametrize(
        "question,expected",
        [
            ("hello", True),
            ("HI!", True),
            ("hi there", True),
            ("hello doctor", True),
            ("good morning", True),
            ("good evening, doctor", True),
            ("thanks", True),
            ("thank you very much", True),
            ("who are you", True),
            ("what can you do", True),
            ("what are your capabilities", True),
            ("help", True),
            ("HELP ME", True),
            ("What is the apixaban dose?", False),
            ("hello doctor, my patient is allergic to penicillin and needs an alternative plan", False),
            ("hi give 5 mg of something", False),
            ("hellos", False),
            ("", False),
        ],
    )
    def test_small_talk_predicate(self, question, expected):
        from router import small_talk
        assert small_talk(question) is expected

    def test_small_talk_greeting_with_long_shop_phrase_false(self):
        from router import small_talk
        assert small_talk("thanksgivings are dreadful") is False

    def test_small_talk_no_length_heuristic_for_clinical_phrases(self):
        """No length-based heuristic: long questions route to the clinical graph."""
        from router import small_talk
        assert small_talk("hello, this emergency room visit is for a suspected pulmonary embolism") is False

    def test_small_talk_greeting_plus_clinical_tail_routes_clinical(self):
        """The regression class the closed vocabulary exists to prevent.

        A "greeting head plus N tail words" rule reads every one of these as small talk and
        answers it with the canned reply, silently discarding the question.
        """
        from router import small_talk
        for question in (
            "hello, pulmonary embolism treatment",
            "thanks, dose of apixaban",
            "hi there, metformin in ckd",
            "hello doctor, what is the apixaban dose",
            "hey what is the dose of aspirin",
        ):
            assert small_talk(question) is False, question

    def test_small_talk_allows_only_one_trailing_address_word(self):
        from router import small_talk
        assert small_talk("hello doctor") is True
        assert small_talk("good morning everyone") is True
        assert small_talk("hi doctor friend") is False
        assert small_talk("hello 5 mg") is False

    def test_small_talk_reply_node_emits_canned_line_on_empty_reply(self):
        """D4 graph behavior: an LLM exception (reply "") routes through the output
        check and surfaces the canned fallback, never an empty assistant message."""
        from agent import create_clinical_agent, create_initial_state
        from router import CANNED_SMALL_TALK_REPLY

        class FailingLLM:
            def bind_tools(self, tools):
                return self

            def invoke(self, messages, config=None):
                raise RuntimeError("provider outage")

        graph = create_clinical_agent(llm=FailingLLM(), tools=[], system_prompt="Test")
        state = create_initial_state("hello", "Test")
        final_state = graph.invoke(state)
        last_msg = final_state["messages"][-1]
        assert last_msg.content == CANNED_SMALL_TALK_REPLY


# ═══════════════════════════════════════════════════════════════════════
# html_parser.py
# ═══════════════════════════════════════════════════════════════════════


class TestExtractOutlineSections:
    """Tests for extract_outline_sections()."""

    def test_basic_sections(self):
        html = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Introduction</a>'
        sections = extract_outline_sections(html)
        assert len(sections) == 1
        assert sections[0]["id"] == "H1"
        assert sections[0]["title"] == "Introduction"

    def test_multiple_sections(self):
        html = (
            '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
            '<a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Methods</a>'
            '<a href="appAction({&quot;section&quot;:&quot;H3&quot;})">Results</a>'
        )
        sections = extract_outline_sections(html)
        assert len(sections) == 3
        assert [s["id"] for s in sections] == ["H1", "H2", "H3"]

    def test_deduplicates_ids(self):
        html = (
            '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
            '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro Again</a>'
        )
        sections = extract_outline_sections(html)
        assert len(sections) == 1

    def test_skips_empty_titles(self):
        html = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})"></a>'
        sections = extract_outline_sections(html)
        assert len(sections) == 0

    def test_simple_section_param(self):
        html = '<a href="section=SEC123">My Section</a>'
        sections = extract_outline_sections(html)
        assert len(sections) == 1
        assert sections[0]["id"] == "SEC123"

    def test_no_sections(self):
        html = '<a href="https://example.com">Link</a>'
        sections = extract_outline_sections(html)
        assert len(sections) == 0

    def test_strips_legacy_topic_view_hyphen_span(self):
        html = '<a href="appAction({&quot;section&quot;:&quot;H2&quot;})"><span class="legacyTopicViewHyphen">- </span>Incision and drainage</a>'
        sections = extract_outline_sections(html)
        assert len(sections) == 1
        assert sections[0]["id"] == "H2"
        assert sections[0]["title"] == "Incision and drainage"


class TestExtractRelatedTopics:
    """Tests for extract_related_topics()."""

    def test_basic_related_topic(self):
        html = '<a href="appAction({&quot;id&quot;:&quot;12345&quot;})">Aspirin</a>'
        topics = extract_related_topics(html)
        assert len(topics) == 1
        assert topics[0]["id"] == "12345"
        assert topics[0]["title"] == "Aspirin"

    def test_skips_section_links(self):
        html = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;,&quot;id&quot;:&quot;12345&quot;})">Link</a>'
        topics = extract_related_topics(html)
        assert len(topics) == 0

    def test_skips_graphic_links(self):
        html = '<a href="appAction({&quot;type&quot;:&quot;graphic&quot;,&quot;id&quot;:&quot;12345&quot;})">Figure</a>'
        topics = extract_related_topics(html)
        assert len(topics) == 0


class TestExtractGraphicsFromOutline:
    """Tests for extract_graphics_from_outline()."""

    def test_table_graphic(self):
        html = '<a href="appAction({&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;,&quot;id&quot;:&quot;12345&quot;})">Table 1</a>'
        graphics = extract_graphics_from_outline(html)
        assert len(graphics) == 1
        assert graphics[0]["id"] == "12345"
        assert graphics[0]["is_table"] is True
        assert graphics[0]["type"] == "graphic_table"

    def test_figure_graphic(self):
        html = '<a href="appAction({&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_figure&quot;,&quot;id&quot;:&quot;67890&quot;})">Figure 1</a>'
        graphics = extract_graphics_from_outline(html)
        assert len(graphics) == 1
        assert graphics[0]["is_table"] is False
        assert graphics[0]["type"] == "graphic_figure"

    def test_non_graphic_excluded(self):
        html = '<a href="appAction({&quot;type&quot;:&quot;medical&quot;,&quot;id&quot;:&quot;99999&quot;})">Topic</a>'
        graphics = extract_graphics_from_outline(html)
        assert len(graphics) == 0

    def test_deduplicates_ids(self):
        html = (
            '<a href="appAction({&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;,&quot;id&quot;:&quot;123&quot;})">T1</a>'
            '<a href="appAction({&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;,&quot;id&quot;:&quot;123&quot;})">T1 Again</a>'
        )
        graphics = extract_graphics_from_outline(html)
        assert len(graphics) == 1

    def test_multiple_graphics(self):
        html = (
            '<a href="appAction({&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;,&quot;id&quot;:&quot;111&quot;})">Table 1</a>'
            '<a href="appAction({&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_figure&quot;,&quot;id&quot;:&quot;222&quot;})">Figure 1</a>'
            '<a href="appAction({&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_algorithm&quot;,&quot;id&quot;:&quot;333&quot;})">Algorithm 1</a>'
        )
        graphics = extract_graphics_from_outline(html)
        assert len(graphics) == 3
        assert graphics[0]["is_table"] is True
        assert graphics[1]["is_table"] is False
        assert graphics[2]["is_table"] is False

    def test_strips_legacy_topic_view_hyphen_span(self):
        html = '<a href="appAction({&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;,&quot;id&quot;:&quot;12345&quot;})"><span class="legacyTopicViewHyphen">- </span>Diagnosis criteria</a>'
        graphics = extract_graphics_from_outline(html)
        assert len(graphics) == 1
        assert graphics[0]["id"] == "12345"
        assert graphics[0]["title"] == "Diagnosis criteria"

    def test_empty_html(self):
        graphics = extract_graphics_from_outline("")
        assert len(graphics) == 0

    def test_no_graphics(self):
        html = '<a href="https://example.com">Regular Link</a>'
        graphics = extract_graphics_from_outline(html)
        assert len(graphics) == 0


class TestExtractSectionHtml:
    """Tests for extract_section_html()."""

    def test_extracts_section_by_id(self):
        body = '<h2 id="H1">Intro</h2><p>Content 1</p><h2 id="H2">Methods</h2><p>Content 2</p>'
        outline = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a><a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Methods</a>'
        result = extract_section_html(body, outline, "H1")
        assert result is not None
        assert "Content 1" in result
        assert "Content 2" not in result

    def test_returns_none_for_missing_id(self):
        body = '<h2 id="H1">Intro</h2><p>Content</p>'
        outline = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
        result = extract_section_html(body, outline, "H99")
        assert result is None

    def test_extracts_to_references_if_no_next_section(self):
        body = '<h2 id="H1">Intro</h2><p>Content</p><div id="references">Refs</div>'
        outline = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
        result = extract_section_html(body, outline, "H1")
        assert result is not None
        assert "Content" in result
        assert "references" not in result


class TestHtmlToMarkdown:
    """Tests for html_to_markdown()."""

    def test_headings(self):
        html = "<h2>Title</h2><p>Content</p>"
        md = html_to_markdown(html)
        assert "Title" in md
        assert "Content" in md

    def test_bold(self):
        html = "<p><strong>Bold text</strong></p>"
        md = html_to_markdown(html)
        assert "**Bold text**" in md

    def test_italic(self):
        html = "<p><em>Italic text</em></p>"
        md = html_to_markdown(html)
        # html2text uses _ for italic, not *
        assert "Italic text" in md
        assert "_" in md or "*" in md

    def test_lists(self):
        html = "<ul><li>Item 1</li><li>Item 2</li></ul>"
        md = html_to_markdown(html)
        assert "Item 1" in md
        assert "Item 2" in md

    def test_empty_input(self):
        assert html_to_markdown("") == ""

    def test_strips_html_tags(self):
        html = "<div><p>Hello <b>world</b></p></div>"
        md = html_to_markdown(html)
        assert "<" not in md
        assert ">" not in md

    def test_normalizes_whitespace(self):
        html = "<p>Line 1</p><p>Line 2</p><p>Line 3</p>"
        md = html_to_markdown(html)
        assert "\n\n\n" not in md


class TestTableToMarkdown:
    """Tests for table_to_markdown()."""

    def test_basic_table(self):
        html = "<table><tr><th>Name</th><th>Value</th></tr><tr><td>A</td><td>1</td></tr></table>"
        md = table_to_markdown(html)
        assert "| Name | Value |" in md
        assert "| --- | --- |" in md
        assert "| A | 1 |" in md

    def test_empty_input(self):
        assert table_to_markdown("") == ""

    def test_no_table_tag(self):
        html = "<p>Just text</p>"
        md = table_to_markdown(html)
        # Falls back to html_to_markdown
        assert "Just text" in md

    def test_pads_uneven_columns(self):
        html = "<table><tr><th>A</th><th>B</th><th>C</th></tr><tr><td>1</td><td>2</td></tr></table>"
        md = table_to_markdown(html)
        assert "| 1 | 2 |  |" in md

    def test_strips_nested_tags(self):
        html = "<table><tr><td><strong>Bold</strong></td></tr></table>"
        md = table_to_markdown(html)
        assert "Bold" in md
        assert "<" not in md


# ═══════════════════════════════════════════════════════════════════════
# tools.py
# ═══════════════════════════════════════════════════════════════════════


class TestBatchSectionTitleParsing:
    """Tests for section title extraction from structured batch tool output."""

    def test_batch_tool_returns_json_with_titles(self):
        """Verify batch tool returns TopicSectionsResponse with topicTitle and sectionTitles."""
        from tools import TopicSectionsResponse
        data = TopicSectionsResponse(
            topicTitle="Atrial Fibrillation",
            sectionTitles={"H1": "Dosing", "H2": "Monitoring"},
            markdown="content",
        )
        assert data.topicTitle == "Atrial Fibrillation"
        assert data.sectionTitles["H1"] == "Dosing"
        assert data.sectionTitles["H2"] == "Monitoring"

    def test_agent_parses_batch_json_result(self):
        """Verify agent extracts titles from batch TopicSectionsResponse and stores them."""
        from tools import TopicSectionsResponse
        from safety_validator import TurnContext, FetchedSection, ToolCallRecord
        tc = TurnContext()
        batch_data = TopicSectionsResponse(
            topicId="1",
            topicTitle="Drug X",
            sectionTitles={"H1": "Dosing", "H2": "Safety"},
            markdown="content",
        )
        topic_id = batch_data.topicId
        tc.topic_titles[topic_id] = batch_data.topicTitle
        tc.outline_sections[topic_id] = batch_data.sectionTitles
        # Simulate section tracking
        section_map = tc.outline_sections.get(topic_id, {})
        for sid in ["H1", "H2"]:
            tc.fetched_sections.append(FetchedSection(
                topic_id=topic_id,
                topic_title=tc.topic_titles.get(topic_id, ""),
                section_id=sid,
                section_title=section_map.get(sid, ""),
                content_snippet="content",
            ))
        assert tc.fetched_sections[0].section_title == "Dosing"
        assert tc.fetched_sections[1].section_title == "Safety"
        assert tc.fetched_sections[0].topic_title == "Drug X"


class TestSectionsTextResponseSerialization:
    """get_topic_sections_text JSON must carry exactly ONE key per field, in camelCase.

    Kotlin kotlinx.serialization emits a single camelCase property per field; the old
    Python model dumped the payload twice (snake_case pydantic fields + camelCase comment
    fields), diverging the Rule-2 verification corpus from the Kotlin one.
    """

    def test_serialized_json_has_single_camelcase_keys_no_duplicates(self):
        from tools import TopicSectionsResponse

        resp = TopicSectionsResponse(
            topicId="123",
            topicTitle="Drug X",
            sectionTitles={"H1": "Dosing"},
            markdown="Dose is 5 mg.",
            invalidSections=["H99"],
            omittedSections=["H98"],
        )
        # object_pairs_hook keeps duplicate keys visible (json.loads would silently keep the last)
        pairs = json.loads(resp.model_dump_json(), object_pairs_hook=lambda p: p)
        keys = [k for k, _ in pairs]
        assert keys.count("invalidSections") == 1
        assert keys.count("omittedSections") == 1
        assert "invalid_sections" not in keys
        assert "omitted_sections" not in keys
        assert keys.count("error") == 1
        assert keys.count("markdown") == 1

        payload = dict(pairs)
        assert payload["invalidSections"] == ["H99"]
        assert payload["omittedSections"] == ["H98"]
        assert payload["topicId"] == "123"
        assert payload["topicTitle"] == "Drug X"

    def test_str_and_model_dump_json_are_identical(self):
        from tools import TopicSectionsResponse

        resp = TopicSectionsResponse(topicId="1", invalidSections=["H1"])
        assert str(resp) == resp.model_dump_json()
        assert "invalidSections" in str(resp)
        assert "invalid_sections" not in str(resp)

    def test_model_dump_testhelper_style_has_no_snake_duplicates(self):
        """test_adversarial_stress.to_dict uses model_dump(exclude_none=True, by_alias=False)."""
        from tools import TopicSectionsResponse

        resp = TopicSectionsResponse(topicId="1", omittedSections=["H11"])
        data = resp.model_dump(exclude_none=True, by_alias=False)
        assert data["omittedSections"] == ["H11"]
        assert "omitted_sections" not in data
        assert "invalid_sections" not in data

    def test_snake_case_attributes_still_available_for_internal_consumers(self):
        from tools import TopicSectionsResponse

        resp = TopicSectionsResponse(invalidSections=["H99"], omittedSections=["H11"], topicId="2")
        assert resp.invalid_sections == ["H99"]
        assert resp.omitted_sections == ["H11"]

    def test_snake_dict_and_json_input_still_validate(self):
        from tools import TopicSectionsResponse

        by_dict = TopicSectionsResponse.model_validate({"topicId": "3", "invalid_sections": ["X"], "omitted_sections": ["Y"]})
        assert by_dict.invalidSections == ["X"]
        assert by_dict.omittedSections == ["Y"]
        by_json = TopicSectionsResponse.model_validate_json(
            '{"topicId": "4", "invalidSections": ["A"], "omittedSections": ["B"]}'
        )
        assert by_json.invalid_sections == ["A"]
        assert by_json.omittedSections == ["B"]


class TestEvidenceSummaryTopicTitle:
    """Evidence digest renders the resolved topic title, falling back to the bare id."""

    def test_snippet_branch_omits_topic_clause(self):
        from agent import _build_evidence_summary

        summary = _build_evidence_summary(
            [
                FetchedSection(
                    topic_id="123",
                    topic_title="Atrial Fibrillation Management",
                    section_id="H1",
                    section_title="Dosing",
                    content_snippet="Apixaban 5 mg BD.",
                )
            ]
        )
        assert "Section [H1] (Dosing):\nApixaban 5 mg BD." in summary
        assert "from topic" not in summary

    def test_no_snippet_renders_resolved_topic_title(self):
        from agent import _build_evidence_summary

        summary = _build_evidence_summary(
            [
                FetchedSection(
                    topic_id="123",
                    topic_title="Atrial Fibrillation Management",
                    section_id="H1",
                    section_title="Dosing",
                    content_snippet="",
                )
            ]
        )
        assert "Section [H1] (Dosing) from topic Atrial Fibrillation Management" in summary

    def test_no_snippet_falls_back_to_topic_id_when_title_missing(self):
        from agent import _build_evidence_summary

        summary = _build_evidence_summary(
            [FetchedSection(topic_id="999", topic_title="", section_id="H2", section_title="Overview")]
        )
        assert "Section [H2] (Overview) from topic 999" in summary

    def test_correction_prompt_embeds_resolved_topic_title(self):
        from agent import _build_evidence_summary
        from system_prompt import build_correction_prompt

        evidence = _build_evidence_summary(
            [
                FetchedSection(
                    topic_id="123",
                    topic_title="Atrial Fibrillation Management",
                    section_id="H1",
                    section_title="Dosing",
                    content_snippet="",
                )
            ]
        )
        prompt = build_correction_prompt(
            violations=[Violation.of("UNVERIFIED_QTY", "Unverified clinical quantities found in response: 750 mg.", target="phase2")],
            evidence_summary=evidence,
        )
        assert "from topic Atrial Fibrillation Management" in prompt


class TestGraphicTitleExtraction:
    """Tests for graphic title extraction from tool output."""

    def test_extract_title_from_graphic_result(self):
        """Verify graphic title is extracted from ### Graphic Table: {title} format."""
        import re
        result = "### Graphic Table: INR Monitoring Table\n\n| Dose | Rate |"
        m = re.match(r"### Graphic Table:\s*(.+)", result)
        assert m is not None
        assert m.group(1).strip() == "INR Monitoring Table"


class TestToolsInit:
    """Tests for tool initialization."""

    def test_init_tools_returns_list(self):
        import tools
        mock_db = MagicMock()
        tool_list = tools.init_tools(mock_db)
        assert isinstance(tool_list, list)
        assert len(tool_list) == 5

    def test_init_tools_includes_expected_names(self):
        import tools
        mock_db = MagicMock()
        tool_list = tools.init_tools(mock_db)
        names = [t.name for t in tool_list]
        assert len(names) == 5
        assert set(names) == {
            "getTopicOverview",
            "searchTopics",
            "searchTopicSections",
            "getTopicSectionsText",
            "getGraphicContent",
        }
        assert "get_topic_brief" not in names
        assert "get_topic_outline" not in names
        assert "submit_clinical_answer" not in names
        assert "get_graphic_info" not in names

    def test_init_tools_sets_global_db(self):
        import tools
        mock_db = MagicMock()
        tools.init_tools(mock_db)
        assert tools._db is mock_db


class TestSearchTopics:
    """Tests for search_topics tool."""

    def setup_method(self):
        import tools
        self.mock_db = MagicMock()
        self.mock_db.get_topic_outline.return_value = None
        tools.init_tools(self.mock_db)

    def test_empty_query(self):
        import tools
        result = tools.search_topics.func(query="")
        assert hasattr(result, "results")
        assert result.results == []

    def test_returns_results(self):
        import tools
        self.mock_db.search_topics.return_value = [{"id": "123", "title": "Aspirin"}]
        self.mock_db.get_suggestions.return_value = ["aspirin dose"]
        result = tools.search_topics.func(query="aspirin")
        assert len(result.results) == 1
        assert result.results[0].id == "123"

    def test_search_topics_returns_pure_candidate_results(self):
        import tools
        self.mock_db.search_topics.return_value = [{"id": "123", "title": "Aspirin"}]
        self.mock_db.get_suggestions.return_value = ["aspirin dose"]
        result = tools.search_topics.func(query="aspirin")
        assert len(result.results) == 1
        assert result.results[0].id == "123"
        assert not hasattr(result.results[0], "outline")

    def test_no_results_returns_suggestions(self):
        import tools
        self.mock_db.search_topics.return_value = []
        self.mock_db.get_suggestions.return_value = ["aspirin", "ibuprofen"]
        result = tools.search_topics.func(query="xyz")
        assert result.results == []
        assert len(result.refine_with) == 2

    def test_strips_query(self):
        import tools
        self.mock_db.search_topics.return_value = []
        self.mock_db.get_suggestions.return_value = []
        tools.search_topics.func(query="  aspirin  ")
        self.mock_db.search_topics.assert_called_with("aspirin", limit=8)


class TestGetTopicSectionText:
    """Tests for get_topic_sections_text tool."""

    def setup_method(self):
        import tools
        self.mock_db = MagicMock()
        tools.init_tools(self.mock_db)

    def test_topic_not_found(self):
        import tools
        self.mock_db.get_topic_asset.return_value = None
        result = tools.get_topic_sections_text.func(
            topic_id="999", section_ids=["H1"]
        )
        assert "Topic not found" in (result.error or "")

    def test_section_not_found(self):
        import tools
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "medical"},
            "bodyHtml": "<h2 id='H1'>Content</h2>",
            "outlineHtml": '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>',
        }
        result = tools.get_topic_sections_text.func(
            topic_id="123", section_ids=["H99"]
        )
        assert result.invalidSections == ["H99"]
        assert result.markdown == ""

    def test_returns_markdown(self):
        import tools
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "medical"},
            "bodyHtml": "<h2 id='H1'>Intro</h2><p>Dose is 5 mg.</p>",
            "outlineHtml": '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>',
        }
        result = tools.get_topic_sections_text.func(
            topic_id="123", section_ids=["H1"]
        )
        assert "5 mg" in result.markdown
        assert result.topicId == "123"

    def test_no_token_cap_returns_sections_in_full(self):
        import tools
        long_text_1 = "Word " * 200  # ~1000 chars
        long_text_2 = "More " * 200  # ~1000 chars
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "medical"},
            "bodyHtml": f"<h2 id='H1'>Sec 1</h2><p>{long_text_1}</p><h2 id='H2'>Sec 2</h2><p>{long_text_2}</p>",
            "outlineHtml": (
                '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Sec 1</a>'
                '<a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Sec 2</a>'
            ),
        }
        # No token cap: sections come back whole.
        result = tools.get_topic_sections_text.func(
            topic_id="123", section_ids=["H1", "H2"]
        )
        assert result.truncated is None
        assert not result.omittedSections
        assert set(result.sectionTitles.keys()) == {"H1", "H2"}
        md = result.markdown
        # converter rstrips trailing whitespace, so match the body run without the final space
        assert long_text_1.rstrip() in md
        assert long_text_2.rstrip() in md
        assert "[Section truncated due to token limit]" not in md

    def test_sections_beyond_ten_per_call_omitted(self):
        import tools
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "medical"},
            "bodyHtml": "".join(
                f"<h2 id='H{i}'>Sec {i}</h2><p>Content {i}.</p>" for i in range(1, 12)
            ),
            "outlineHtml": "".join(
                '<a href="appAction({&quot;section&quot;:&quot;H%d&quot;})">Sec %d</a>' % (i, i)
                for i in range(1, 12)
            ),
        }
        result = tools.get_topic_sections_text.func(
            topic_id="123", section_ids=[f"H{i}" for i in range(1, 12)]
        )
        # Count guard: at most 10 sections per call; the 11th is reported, never silently dropped
        assert set(result.sectionTitles.keys()) == {f"H{i}" for i in range(1, 11)}
        assert result.omittedSections == ["H11"]
        assert result.truncated is None
        assert "=== Section: H11 ===" not in result.markdown

    def test_calculator_returns_full_text_without_cap(self):
        import tools
        self.mock_db.get_topic_title.return_value = "Calculator"
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "calc"},
            "bodyHtml": "<p>" + ("Calculations " * 300) + "</p>",
            "outlineHtml": ""
        }
        result = tools.get_topic_sections_text.func(
            topic_id="148929", section_ids=["FULL"]
        )
        # No token cap: the whole calculator body is returned
        assert result.truncated is None
        assert "[Content truncated due to token limit]" not in result.markdown
        assert result.markdown.count("Calculations") == 300


class TestGetGraphicContent:
    """Tests for get_graphic_content tool."""

    def setup_method(self):
        import tools
        self.mock_db = MagicMock()
        tools.init_tools(self.mock_db)

    def test_graphic_not_found(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = None
        result = tools.get_graphic_content.func(graphic_id="999")
        assert result.error is not None

    def test_non_table_type_blocked(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = {"graphicInfo": {"subtype": "graphic_figure", "title": "Fig 1"}}
        result = tools.get_graphic_content.func(graphic_id="123")
        assert result.error is not None
        assert "not a table" in result.error

    def test_empty_content_blocked(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = {"graphicInfo": {"subtype": "graphic_table", "title": "T"}, "imageHtml": ""}
        result = tools.get_graphic_content.func(graphic_id="123")
        assert result.error is not None

    def test_table_content_returns_markdown(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = {
            "graphicInfo": {"subtype": "graphic_table", "title": "Dosing Table"},
            "imageHtml": "<table><tr><th>Dose</th><th>Rate</th></tr><tr><td>5mg</td><td>2x/day</td></tr></table>",
        }
        result = tools.get_graphic_content.func(graphic_id="123")
        assert "Dosing Table" in result.title
        assert "| Dose | Rate |" in result.markdown

    def test_strips_graphic_prefix(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = {
            "graphicInfo": {"subtype": "graphic_table", "title": "Table"},
            "imageHtml": "<table><tr><td>A</td></tr></table>",
        }
        tools.get_graphic_content.invoke({"graphic_id": "Graphic-123"})
        # Should look up "123" after stripping prefix
        self.mock_db.get_graphic_asset.assert_any_call("123")



# ═══════════════════════════════════════════════════════════════════════
# agent.py
# ═══════════════════════════════════════════════════════════════════════


class TestAgentGraph:
    """Tests for agent graph creation."""

    def test_create_agent_returns_compiled_graph(self):
        from agent import create_clinical_agent
        from langchain_core.tools import tool

        @tool
        def dummy_tool(query: str) -> str:
            """Dummy tool."""
            return "ok"

        mock_llm = MagicMock()
        graph = create_clinical_agent(
            llm=mock_llm,
            tools=[dummy_tool],
            system_prompt="You are a clinical assistant.",
        )
        assert graph is not None
        assert "small_talk_reply" in graph.nodes
        assert "node_evidence_gathering" in graph.nodes
        assert "node_clinical_synthesis" in graph.nodes
        assert "execute_tools" in graph.nodes
        assert "intercept_premature" in graph.nodes
        assert "validate_evidence" in graph.nodes
        assert "node_compress_history" in graph.nodes
        assert "validate_safety" in graph.nodes
        assert "safety_block" in graph.nodes

    def test_create_initial_state(self):
        from agent import create_initial_state
        state = create_initial_state("What is aspirin?", "System prompt")
        assert state["user_question"] == "What is aspirin?"
        assert state["gate1_retries"] == 0
        assert state["gate2_revisions"] == 0
        assert state["validation_result"] is None
        assert len(state["messages"]) == 2  # System + Human

    def test_max_tool_retries_default(self):
        from agent import create_clinical_agent
        from langchain_core.tools import tool

        @tool
        def dummy_tool(query: str) -> str:
            """Dummy."""
            return "ok"

        # Just verify it doesn't crash with default params and creates subgraphs
        graph = create_clinical_agent(
            llm=MagicMock(),
            tools=[dummy_tool],
            system_prompt="test",
        )
        assert graph is not None
        assert "node_evidence_gathering" in graph.nodes
        assert "node_clinical_synthesis" in graph.nodes


class TestSynthesisRemediationRouting:
    """node_clinical_synthesis treats any "SYSTEM NOTICE" message (colon or em-dash header)
    as a remediation passthrough — parity with Kotlin's loose
    `!clean.startsWith("SYSTEM NOTICE")`. The em-dash headers written by
    buildCorrectionPrompt ("SYSTEM NOTICE — EVIDENCE GATE PAUSE", "— PATIENT SAFETY BLOCK",
    "— NUMERICAL VERIFICATION BLOCK") must flow into the revision branch and never be
    re-greeted or silently dropped.
    """

    @staticmethod
    def _make_graph():
        from agent import create_clinical_agent

        llm = MagicMock()
        llm.invoke.return_value = AIMessage(content="Revised clinical answer.")
        graph = create_clinical_agent(llm=llm, tools=[], system_prompt="test")
        return graph.nodes["node_clinical_synthesis"], llm

    @staticmethod
    def _state(messages, tc):
        return {
            "messages": messages,
            "turn_context": tc.model_dump(),
            "user_question": "What is apixaban dosing?",
            "retry_count": 1,
        }

    def _clinical_tc(self):
        """TurnContext reflecting a gathered, clinically answered draft (first synth attempt,
        mirroring validate_evidence's tc.answer/fetched_sections population)."""
        return TurnContext(
            tool_calls=[
                ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Apixaban 5 mg.", success=True)
            ],
            answer="Apixaban 10 mg twice daily.",
            fetched_sections=[
                FetchedSection(
                    topic_id="123",
                    topic_title="Apixaban Therapy",
                    section_id="H1",
                    section_title="Dosing",
                    content_snippet="Apixaban 5 mg twice daily.",
                )
            ],
        )

    def test_em_dash_correction_notice_is_remediation_passthrough(self):
        node, llm = self._make_graph()
        notice = (
            "SYSTEM NOTICE — NUMERICAL VERIFICATION BLOCK:\n"
            "<rejected_quantities>10 mg</rejected_quantities>\n"
            "<retrieved_evidence>Apixaban 5 mg.</retrieved_evidence>"
        )
        messages = [
            SystemMessage(content="You are a clinical assistant."),
            HumanMessage(content="What is apixaban dosing?"),
            AIMessage(content="Apixaban 10 mg."),
            HumanMessage(content=notice),
        ]
        state = self._state(messages, self._clinical_tc())

        result = node.invoke(state)

        assert result is not None, "Synthesis must not be skipped for an em-dash notice"
        assert result["messages"][0].content == "Revised clinical answer."
        sent = llm.invoke.call_args[0][0]
        # Passthrough: the notice itself ends the request; no extra synthesis directive is appended
        assert len(sent) == len(messages)
        assert sent[-1].content == notice
        assert not any(
            isinstance(m, HumanMessage) and str(m.content).startswith("CLINICAL SYNTHESIS DIRECTIVE")
            for m in sent
        )

    def test_em_dash_notice_not_silently_dropped_without_tool_context(self):
        """A state whose pending_input starts with the em-dash header must not be re-greeted
        (the old strict-colon predicate made non-clinical flows drop the turn entirely)."""
        node, llm = self._make_graph()
        notice = "SYSTEM NOTICE — PATIENT SAFETY BLOCK (CONTRAINDICATION DETECTED):\nVIOLATION: allergy"
        messages = [
            SystemMessage(content="You are a clinical assistant."),
            HumanMessage(content="What is apixaban dosing?"),
            HumanMessage(content=notice),
        ]
        state = self._state(messages, TurnContext())

        result = node.invoke(state)

        assert result is not None, "Em-dash notice must flow into the revision branch"
        assert result["messages"][0].content == "Revised clinical answer."
        sent = llm.invoke.call_args[0][0]
        assert sent[-1].content == notice

    def test_colon_premature_notice_still_remediation(self):
        node, llm = self._make_graph()
        notice = "SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED.\nRetrieve evidence first."
        messages = [
            SystemMessage(content="You are a clinical assistant."),
            HumanMessage(content="What is apixaban dosing?"),
            AIMessage(content="It depends."),
            HumanMessage(content=notice),
        ]
        state = self._state(messages, self._clinical_tc())

        result = node.invoke(state)

        assert result["messages"][0].content == "Revised clinical answer."
        sent = llm.invoke.call_args[0][0]
        assert len(sent) == len(messages)
        assert sent[-1].content == notice

    def test_plain_draft_still_gets_synthesis_directive_appended(self):
        """Regression guard: non-notice turns start Phase 2 from a fresh context with closed-book directive (Change 3)."""
        node, llm = self._make_graph()
        messages = [
            SystemMessage(content="You are a clinical assistant."),
            HumanMessage(content="What is apixaban dosing?"),
            AIMessage(content="Apixaban 10 mg twice daily."),
        ]
        state = self._state(messages, self._clinical_tc())

        node.invoke(state)

        sent = llm.invoke.call_args[0][0]
        assert len(sent) == 2
        assert isinstance(sent[-1], HumanMessage)
        assert sent[-1].content.startswith("CLINICAL SYNTHESIS DIRECTIVE")

    def test_synthesis_directive_uses_original_user_question(self):
        """Change 1: synthesis is always the clinical directive around the ORIGINAL
        question — the discarded Phase-1 draft is never re-synthesized and no answer-side
        greeting bypass groups the turn away (the old skip path is deleted)."""
        node, llm = self._make_graph()
        draft_text = "Hello! How can I help you today?"
        messages = [
            SystemMessage(content="You are a clinical assistant."),
            HumanMessage(content="What is apixaban dosing?"),
            AIMessage(content=draft_text),
        ]
        state = self._state(messages, self._clinical_tc())
        state["user_question"] = "What is apixaban dosing?"

        result = node.invoke(state)

        assert result is not None, "A non-notice turn must always synthesize now"
        assert result["messages"][0].content == "Revised clinical answer."
        sent = llm.invoke.call_args[0][0]
        directive = sent[-1].content
        assert directive.startswith("CLINICAL SYNTHESIS DIRECTIVE")
        # The inquiry is the original question — never the rendered greeting draft
        assert "CLINICAL INQUIRY:\nWhat is apixaban dosing?" in directive
        assert "How can I help you today?" not in directive


# ═══════════════════════════════════════════════════════════════════════
# Edge cases and regression tests
# ═══════════════════════════════════════════════════════════════════════


class TestEdgeCases:
    """Regression tests for known bugs."""

    def test_answer_with_only_structural_numbers(self):
        """Answer with section numbers, years, list markers should not trigger invented numbers."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Guidelines 2024.", success=True)],
            answer=(
                "1. Introduction\n"
                "2. Methods\n"
                "See section 3.1.\n"
                "The year 2024 guidelines recommend treatment.\n"
                "Class IIa recommendation."
            ),
            tool_results=["Guidelines 2024."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_empty_answer_with_tools(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="",
            tool_results=["..."],
        )
        result = SafetyValidator().validate(ctx)
        # Empty answer with tools should pass all rules (no quantities to check)
        assert result.passed

    def test_html_entities_in_tool_result(self):
        """Tool results with HTML entities should not cause false invented-number blocks."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Dose &gt; 5 mg", success=True)],
            answer="The dose is 5 mg.",
            tool_results=["Dose &gt; 5 mg"],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_graphic_content_as_only_fetch(self):
        """getGraphicContent should satisfy section content requirement."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_graphic_content", arguments={}, result="...", success=True)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is None

    def test_short_greeting_without_tools_blocked(self):
        """Short greeting without tools also blocks (Rule 0 never bypasses)."""
        ctx = TurnContext(
            tool_calls=[],
            answer="Thanks for your question!",
            user_question="thank you",
        )
        result = SafetyValidator().validate(ctx)
        assert not result.passed

    def test_clinical_answer_without_tools_blocked(self):
        """Clinical answer with dosing quantities but no tools should block."""
        ctx = TurnContext(
            tool_calls=[],
            answer="The recommended dose is 5 mg twice daily.",
        )
        result = SafetyValidator().validate(ctx)
        assert not result.passed
        assert "Clinical recommendations require database verification" in result.blocked_reason

    def test_slashed_and_compound_units_validation(self):
        """Test compound slashed units like 5 u/x, 10 U/L, 0.5 mcg/kg/min, 100 mg/m2."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Give 5 u/x IV, 10 U/L, and 0.5 mcg/kg/min.", success=True)],
            answer="Recommended rates are 5 u/x, 10 U/L, and 0.5 mcg/kg/min.",
            tool_results=["Give 5 u/x IV, 10 U/L, and 0.5 mcg/kg/min."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None


class TestAutoPopulateRefs:
    """Tests for _auto_populate_refs — only valid sections with titles should produce refs."""

    def test_skips_sections_with_empty_title(self):
        """Sections whose section_title is empty should be excluded from topic_refs."""
        from agent import _auto_populate_refs
        from safety_validator import TurnContext, FetchedSection, ToolCallRecord, ToolCallRecord

        ctx = TurnContext(
            fetched_sections=[
                FetchedSection(
                    topic_id="94",
                    topic_title="Acute ST-elevation MI: Initial antiplatelet therapy",
                    section_id="H4",
                    section_title="ASPIRIN",
                ),
                FetchedSection(
                    topic_id="94",
                    topic_title="Acute ST-elevation MI: Initial antiplatelet therapy",
                    section_id="H5",
                    section_title="",
                ),
                FetchedSection(
                    topic_id="94",
                    topic_title="Acute ST-elevation MI: Initial antiplatelet therapy",
                    section_id="H6",
                    section_title="",
                ),
            ],
        )
        _auto_populate_refs(ctx, "{}")
        assert len(ctx.structured_topic_refs) == 1
        assert ctx.structured_topic_refs[0].label == "ASPIRIN"
        assert ctx.structured_topic_refs[0].section_id == "H4"

    def test_strips_leading_dashes_from_section_title(self):
        """Sections with leading dashes should be stripped to clean labels."""
        from agent import _auto_populate_refs
        from safety_validator import TurnContext, FetchedSection, ToolCallRecord

        ctx = TurnContext(
            fetched_sections=[
                FetchedSection(
                    topic_id="94",
                    topic_title="Antiplatelet Therapy",
                    section_id="H1",
                    section_title="-Antiplatelet Agents",
                ),
                FetchedSection(
                    topic_id="94",
                    topic_title="Antiplatelet Therapy",
                    section_id="H2",
                    section_title="—Special Considerations",
                ),
            ],
        )
        _auto_populate_refs(ctx, "{}")
        assert len(ctx.structured_topic_refs) == 2
        assert ctx.structured_topic_refs[0].label == "Antiplatelet Agents"
        assert ctx.structured_topic_refs[1].label == "Special Considerations"

    def test_resolves_section_title_from_outline_sections(self):
        """If fetched_section.section_title is empty, fallback to outline_sections."""
        from agent import _auto_populate_refs
        from safety_validator import TurnContext, FetchedSection, ToolCallRecord

        ctx = TurnContext(
            fetched_sections=[
                FetchedSection(
                    topic_id="7891",
                    topic_title="Laboratory assessment of thyroid function",
                    section_id="sec_1",
                    section_title="",
                ),
            ],
            outline_sections={"7891": {"sec_1": "Serum TSH Tests"}},
        )
        _auto_populate_refs(ctx, "{}")
        assert len(ctx.structured_topic_refs) == 1
        assert ctx.structured_topic_refs[0].label == "Serum TSH Tests"
        assert ctx.structured_topic_refs[0].topic_title == "Laboratory assessment of thyroid function"

    def test_attributes_parent_topic_when_graphic_table_fetched(self):
        """When a graphic table is fetched, its parent topic from outline is attributed."""
        from agent import _auto_populate_refs
        from safety_validator import TurnContext

        ctx = TurnContext(
            graphic_ids={"67935"},
            graphic_titles={"67935": "Assessment of thyroid function"},
            graphic_to_topic={"67935": "7891"},
            topic_titles={"7891": "Laboratory assessment of thyroid function"},
        )
        _auto_populate_refs(ctx, "{}")
        assert len(ctx.structured_topic_refs) == 1
        assert ctx.structured_topic_refs[0].topic_id == "7891"
        assert ctx.structured_topic_refs[0].topic_title == "Laboratory assessment of thyroid function"
        assert ctx.structured_topic_refs[0].label == "Laboratory assessment of thyroid function"
        assert len(ctx.structured_graphic_refs) == 1
        assert ctx.structured_graphic_refs[0].graphic_id == "67935"
        assert ctx.structured_graphic_refs[0].label == "Assessment of thyroid function"

    def test_graphic_id_prefix_does_not_create_duplicate_refs(self):
        """Clean graphic ID prevents duplicate refs when graphic_id has 'graphic-' prefix."""
        from agent import _auto_populate_refs
        from safety_validator import TurnContext

        ctx = TurnContext(
            graphic_ids={"67935"},
            graphic_titles={"67935": "Assessment of thyroid function", "graphic-67935": "Assessment of thyroid function"},
            graphic_to_topic={"67935": "7891", "graphic-67935": "7891"},
            topic_titles={"7891": "Laboratory assessment of thyroid function"},
        )
        _auto_populate_refs(ctx, "{}")
        assert len(ctx.structured_graphic_refs) == 1
        assert ctx.structured_graphic_refs[0].graphic_id == "67935"
        assert ctx.structured_graphic_refs[0].topic_id == "7891"


class TestSchemaParityAndSafetyBlock:
    """Verify schema parity with Kotlin responses and in-graph safety blocking."""

    def test_get_topic_overview_includes_canonical_camel_case_keys(self):
        import tools
        from unittest.mock import MagicMock
        mock_db = MagicMock()
        mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "medical"},
            "outlineHtml": '<ul><li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Section 1</a></li></ul>',
            "bodyHtml": "<div id='H1'>text</div>",
            "relatedGraphics": [{"graphics": [
                {"graphicInfo": {"id": "111", "displayName": "Table 1", "subtype": "graphic_table"}},
                {"graphicInfo": {"id": "222", "displayName": "Photo 1", "subtype": "graphic_picture"}},
            ]}],
            "relatedTopics": {"topics": [{"id": "999", "title": "Related"}]},
        }
        mock_db.get_topic_title.return_value = "Test Topic"
        tools.init_tools(mock_db)

        data = tools.get_topic_overview.func(topic_id="123")
        assert data.topicType == "medical"
        assert [g.id for g in data.graphics] == ["111"]
        assert data.graphics[0].isTable is True
        assert [t.id for t in data.relatedTopics] == ["999"]

    def test_validate_safety_emits_blocked_ai_message_when_retries_exhausted(self):
        from langchain_core.messages import AIMessage, HumanMessage
        from agent import create_clinical_agent
        from safety_validator import TurnContext

        # Test agent graph validation node logic
        class DummyLLM:
            def bind_tools(self, tools):
                return self
            def invoke(self, messages, config=None):
                # Return unverified clinical answer with numbers
                return AIMessage(content="The recommended dose is 50 mg twice daily.")

        dummy_llm = DummyLLM()
        agent = create_clinical_agent(
            llm=dummy_llm,
            tools=[],
            system_prompt="Test",
            max_safety_retries=0,  # 0 retries to exhaust immediately
        )
        initial_state = {
            "messages": [HumanMessage(content="What is the dose?")],
            "system_prompt": "Test",
            "turn_context": TurnContext(answer="").model_dump(),
            "user_question": "What is the dose?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
        }
        final_state = agent.invoke(initial_state)
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert last_msg.content.startswith("Clinical Response Verification Blocked:")

    def test_clinical_source_hierarchy_and_conversions(self):
        from clinical_source import (
            SectionRef,
            ClinicalArticle,
            ClinicalTable,
            group_topic_refs_to_articles,
            graphic_refs_to_tables,
        )
        from safety_validator import TopicRef, GraphicRef

        topic_refs = [
            TopicRef(topic_id="100", section_id="secA", label="Section A", topic_title="Topic 100 Title"),
            TopicRef(topic_id="100", section_id="secB", label="Section B", topic_title="Topic 100 Title"),
            TopicRef(topic_id="200", section_id="secC", label="Section C", topic_title="Topic 200 Title"),
        ]

        articles = group_topic_refs_to_articles(topic_refs)
        assert len(articles) == 2
        assert articles[0].topic_id == "100"
        assert articles[0].display_title == "Topic 100 Title"
        assert len(articles[0].sections) == 2
        assert articles[0].sections[0].section_id == "secA"
        assert articles[0].sections[0].section_title == "Section A"

        graphic_refs = [
            GraphicRef(graphic_id="g123", label="Table 1 Dosing", topic_id="100"),
        ]
        tables = graphic_refs_to_tables(graphic_refs, {"100": "Topic 100 Title"})
        assert len(tables) == 1
        assert tables[0].graphic_id == "g123"
        assert tables[0].display_title == "Table 1 Dosing"
        assert tables[0].parent_topic_id == "100"
        assert tables[0].parent_topic_title == "Topic 100 Title"

        # Verify FULL section ID is filtered out from section list
        calc_refs = [
            TopicRef(topic_id="148929", section_id="FULL", label="AHA Calculator", topic_title="AHA Calculator")
        ]
        calc_articles = group_topic_refs_to_articles(calc_refs)
        assert len(calc_articles) == 1
        assert calc_articles[0].topic_id == "148929"
        assert len(calc_articles[0].sections) == 0

    def test_direct_text_answer_auto_populates_refs(self):
        from agent import create_clinical_agent
        from safety_validator import TurnContext, FetchedSection, ToolCallRecord
        from langchain_core.messages import AIMessage, HumanMessage

        class DirectTextLLM:
            def bind_tools(self, tools):
                return self
            def invoke(self, messages, config=None):
                return AIMessage(content="According to the database, normal saline is indicated.")

        llm = DirectTextLLM()
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test")
        tc = TurnContext()
        tc.tool_calls.append(
            ToolCallRecord(
                tool_name="get_topic_sections_text",
                arguments={"topic_id": "123", "section_ids": ["sec1"]},
                result="Normal saline is indicated.",
                success=True,
            )
        )
        tc.fetched_sections.append(
            FetchedSection(
                topic_id="123",
                section_id="sec1",
                section_title="Indications",
                topic_title="Fluids",
                content_snippet="Normal saline is indicated.",
            )
        )
        tc.topic_titles["123"] = "Fluids"
        tc.outline_sections["123"] = {"sec1": "Indications"}

        state = {
            "messages": [HumanMessage(content="What fluid?")],
            "system_prompt": "Test",
            "turn_context": tc.model_dump(),
            "user_question": "What fluid?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
        }
        res = agent.invoke(state)
        final_tc = TurnContext(**res["turn_context"])
        assert len(final_tc.structured_topic_refs) == 1
        assert final_tc.structured_topic_refs[0].topic_id == "123"
        assert final_tc.structured_topic_refs[0].label == "Indications"
        assert res["validation_result"]["passed"] is True


class TestGetTopicOverview:
    """Unit tests for get_topic_overview compound retrieval tool."""

    def setup_method(self):
        import tools
        self.mock_db = MagicMock()
        tools.init_tools(self.mock_db)

    def test_topic_not_found(self):
        import tools
        self.mock_db.get_topic_asset.return_value = None

        result = tools.get_topic_overview.func(topic_id="999")
        assert result.error is not None
        assert "Topic not found: 999" in result.error

    def test_bundles_outline_and_graphics(self):
        import tools
        self.mock_db.get_topic_title.return_value = "Apixaban Therapy"
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "medical"},
            "outlineHtml": (
                '<ul id="innerOutline">'
                '  <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>'
                '  <li><a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})">Summary &amp; Recommendations</a></li>'
                '  <li><a href="appAction({&quot;section&quot;:&quot;H_DOSING&quot;})">Dosing</a></li>'
                '</ul>'
            ),
            "bodyHtml": (
                '<div id="H1">Overview content</div>'
                '<div id="H_SR">Summary: Give apixaban 5 mg twice daily.</div>'
                '<div id="H_DOSING">Dosing details: reduce to 2.5 mg if CrCl &lt; 30.</div>'
            ),
            "relatedGraphics": [{"graphics": [
                {"graphicInfo": {"id": "111", "displayName": "Dosing table", "subtype": "graphic_table"}},
                {"graphicInfo": {"id": "222", "displayName": "Rash photo", "subtype": "graphic_picture"}},
                {"graphicInfo": {"id": "57469", "displayName": "UpToDate Lexidrug clinical abbreviations", "subtype": "graphic_table"}},
            ]}],
            "relatedTopics": {"topics": [{"id": "456", "title": "Apixaban: Pediatric"}]},
        }

        result = tools.get_topic_overview.func(topic_id="123")

        # Schema & Invariants
        assert result.topicId == "123"
        assert result.title == "Apixaban Therapy"
        assert result.topicType == "medical"
        assert len(result.sections) == 3
        # Only graphic_table survives, and Lexidrug appendix tables are filtered out.
        assert [g.id for g in result.graphics] == ["111"]
        assert [t.id for t in result.relatedTopics] == ["456"]
        assert result.markdown == ""

    def test_calculator_topic_handling(self):
        import tools
        self.mock_db.get_topic_title.return_value = "eGFR Calculator"
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "calc"},
            "bodyHtml": "<p>Formula: GFR = 141 * min(Scr/kappa, 1)</p>",
            "outlineHtml": ""
        }

        result = tools.get_topic_overview.func(topic_id="148929")

        assert result.topicType == "calc"
        assert len(result.sections) == 1
        assert result.sections[0].id == "FULL"
        assert "=== Calculator: eGFR Calculator ===" in result.markdown
        assert "GFR = 141" in result.markdown

    def test_smart_section_resolution_fallback(self):
        import tools
        self.mock_db.get_topic_title.return_value = "Drug C"
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "drug"},
            "outlineHtml": (
                '<ul id="innerOutline">'
                '  <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>'
                '  <li><a href="appAction({&quot;section&quot;:&quot;H2&quot;})">— Renal Impairment</a></li>'
                '</ul>'
            ),
            "bodyHtml": '<div id="H1">Overview</div><div id="H2">Renal text</div>',
        }

        # Case-insensitive section ID
        res1 = tools.get_topic_sections_text.func(topic_id="123", section_ids=["h2"])
        assert "H2" in res1.sectionTitles
        assert "=== Section: H2 ===" in res1.markdown

        # Title match with stripped dash
        res2 = tools.get_topic_sections_text.func(topic_id="123", section_ids=["Renal Impairment"])
        assert "H2" in res2.sectionTitles
        assert "=== Section: H2 ===" in res2.markdown

        # Error envelope on nonexistent topic
        self.mock_db.get_topic_asset.return_value = None
        res3 = tools.get_topic_sections_text.func(topic_id="999", section_ids=["H1"])
        assert res3.error is not None
        assert "Topic not found: 999" in res3.error


class TestReasoningTokenSanitizer:
    """Unit tests for reasoning token hygiene and chain-of-thought sanitization."""

    def test_strips_thought_tags_cleanly(self):
        from reasoning_sanitizer import ReasoningTokenSanitizer
        input_text = "<thought>Check eGFR 22 mL/min and dose 500mg</thought>Amoxicillin 250 mg orally every 12 hours."
        sanitized = ReasoningTokenSanitizer.sanitize(input_text)
        assert sanitized.clean_text == "Amoxicillin 250 mg orally every 12 hours."
        assert sanitized.reasoning_text == "Check eGFR 22 mL/min and dose 500mg"

    def test_strips_think_tags(self):
        from reasoning_sanitizer import ReasoningTokenSanitizer
        input_text = "<think>Patient is 70yo, CrCl is 45 mL/min</think>The recommended dosage is 50 mg daily."
        clean = ReasoningTokenSanitizer.strip_reasoning(input_text)
        assert clean == "The recommended dosage is 50 mg daily."

    def test_strips_reasoning_tags_with_attributes(self):
        from reasoning_sanitizer import ReasoningTokenSanitizer
        input_text = '<thought type="clinical_step">Consider penicillin allergy.</thought>Azithromycin 500 mg day 1.'
        sanitized = ReasoningTokenSanitizer.sanitize(input_text)
        assert sanitized.clean_text == "Azithromycin 500 mg day 1."
        assert sanitized.reasoning_text == "Consider penicillin allergy."

    def test_strips_unclosed_thought_block_at_end(self):
        from reasoning_sanitizer import ReasoningTokenSanitizer
        input_text = "<thought>Evaluating patient symptoms: fever, cough..."
        clean = ReasoningTokenSanitizer.strip_reasoning(input_text)
        assert clean == ""
        assert ReasoningTokenSanitizer.extract_reasoning(input_text) == "Evaluating patient symptoms: fever, cough..."

    def test_preserves_clinical_less_than_comparisons(self):
        from reasoning_sanitizer import ReasoningTokenSanitizer
        input_text = "eGFR < 30 mL/min requires dose reduction to 250 mg. Hemoglobin < 7 g/dL indicates transfusion."
        clean = ReasoningTokenSanitizer.strip_reasoning(input_text)
        assert clean == input_text
        assert not ReasoningTokenSanitizer.contains_reasoning(input_text)

    def test_prevents_number_validation_false_positives_in_safety_validator(self):
        from reasoning_sanitizer import ReasoningTokenSanitizer
        from safety_validator import SafetyValidator, ToolCallRecord, TurnContext

        validator = SafetyValidator()
        tool_result = "Dosing in renal impairment: 250 mg orally every 12 hours."
        tool_call = ToolCallRecord(
            tool_name="get_topic_sections_text",
            arguments={"topic_id": "123"},
            result=tool_result,
            success=True,
        )

        raw_llm_answer = "<thought>Standard dose is 1000 mg, but patient eGFR is 20 mL/min. Adjust to 250 mg.</thought>Give 250 mg orally."

        # Without sanitization: Fails because 1000 mg is not in toolResult
        unhygienic_ctx = TurnContext(
            tool_calls=[tool_call],
            answer=raw_llm_answer,
            tool_results=[tool_result],
        )
        unhygienic_result = validator.validate(unhygienic_ctx)
        assert unhygienic_result.passed is False
        assert "1000 mg" in (unhygienic_result.blocked_reason or "") or "Unverified" in (unhygienic_result.blocked_reason or "")

        # With sanitization: Passes because 1000 mg is stripped from answer
        clean_answer = ReasoningTokenSanitizer.strip_reasoning(raw_llm_answer)
        hygienic_ctx = TurnContext(
            tool_calls=[tool_call],
            answer=clean_answer,
            tool_results=[tool_result],
        )
        hygienic_result = validator.validate(hygienic_ctx)
        assert hygienic_result.passed is True

    def test_rule0_blocks_answer_side_greetings_with_or_without_thoughts(self):
        from reasoning_sanitizer import ReasoningTokenSanitizer

        validator = SafetyValidator()
        raw_llm_answer = "<thought>Friendly greeting. 100% confidence. No dosing needed.</thought>Hello! How can I assist you today?"

        # Change 1: Rule 0 is content-blind — a zero-tool-call turn is NO_TOOLS whether
        # or not reasoning digits leaked into the answer (greetings route at entry now)
        raw_ctx = TurnContext(
            tool_calls=[],
            answer=raw_llm_answer,
            user_question="Hello",
        )
        raw_result = validator.validate(raw_ctx)
        assert raw_result.passed is False
        assert [v.code for v in raw_result.violations] == ["NO_TOOLS"]

        clean_ctx = TurnContext(
            tool_calls=[],
            answer=ReasoningTokenSanitizer.strip_reasoning(raw_llm_answer),
            user_question="Hello",
        )
        clean_result = validator.validate(clean_ctx)
        assert clean_result.passed is False
        assert [v.code for v in clean_result.violations] == ["NO_TOOLS"]
        assert "No database tools were executed" in (clean_result.blocked_reason or "")

    def test_streaming_filter_delta_processing(self):
        from reasoning_sanitizer import ReasoningStreamFilter

        stream_filter = ReasoningStreamFilter()
        chunks = ["<tho", "ught>checking eGFR<", "/thought>Amoxicillin ", "500 mg."]
        clean_accum = []
        reasoning_accum = []

        for chunk in chunks:
            c, r = stream_filter.process_delta(chunk)
            if c:
                clean_accum.append(c)
            if r:
                reasoning_accum.append(r)

        assert "".join(clean_accum) == "Amoxicillin 500 mg."
        assert "".join(reasoning_accum) == "checking eGFR"


# ═══════════════════════════════════════════════════════════════════════
# Change 6 — ported case-for-case from
# shared/src/commonTest/kotlin/com/clinref/app/domain/ai/SafetyValidatorTest.kt
# ═══════════════════════════════════════════════════════════════════════


class _ChangeParityBase:
    """Helpers mirroring the Kotlin test fixtures ctx()/toolCall()/section()."""

    @staticmethod
    def tool_call():
        return ToolCallRecord(
            tool_name="getTopicSectionsText",
            arguments={"sectionId": "H1"},
            result="content",
            success=True,
        )

    @staticmethod
    def section():
        return FetchedSection(
            topic_id="1",
            topic_title="Topic Title",
            section_id="H1",
            section_title="Section 1",
        )

    def ctx(self, answer, tool_results, patient_profile=None, user_question=""):
        return TurnContext(
            tool_calls=[self.tool_call()],
            answer=answer,
            tool_results=tool_results,
            fetched_sections=[self.section()],
            user_question=user_question,
            patient_profile=patient_profile,
        )


class TestChange6Rule2BoundaryFixAndCap(_ChangeParityBase):
    """Change 6: Rule-2 boundary fix + report cap + SHADOW semantics.

    Ported from the Change-6 battery in SafetyValidatorTest.kt. Production
    stays the legacy lenient matcher (RULE2_ENFORCE_REWRITE = False); the
    deny-by-default engine only runs in shadow (logging) mode.
    """

    def test_rule_2_boundary_fix_blocks_12_mg_from_a_q12h_corpus(self):
        res = SafetyValidator().validate(self.ctx(
            answer="Give 12 mg daily.",
            tool_results=["Dose: 50 mg q12h."],
        ))
        assert not res.passed, "12 must never verify from the '12' inside q12h"
        assert "12 mg" in res.blocked_reason

    def test_rule_2_boundary_fix_blocks_12_mg_from_an_h12_corpus(self):
        res = SafetyValidator().validate(self.ctx(
            answer="Give 12 mg daily.",
            tool_results=["Room H12 holds 50 mg vials."],
        ))
        assert not res.passed, "12 must never verify from the '12' inside H12"
        assert "12 mg" in res.blocked_reason

    def test_rule_2_report_cap_raised_to_ten_unverified_quantities(self):
        quantities = " ".join(f"{i} mg" for i in range(1, 13))
        res = SafetyValidator().validate(self.ctx(
            answer=f"Take {quantities} daily.",
            tool_results=["No dosing text available."],
        ))
        assert not res.passed
        reason = res.blocked_reason
        assert "10 mg" in reason, f"the 10th metric must be reported: {reason}"
        assert "11 mg" not in reason, f"metrics past the cap must be truncated: {reason}"
        assert "12 mg" not in reason, f"metrics past the cap must be truncated: {reason}"

    def test_rule_2_enforces_deny_by_default_in_production(self):
        res = SafetyValidator().validate(self.ctx(
            answer="Administer 0.002 mg daily.",
            tool_results=["The dose is 2 mg once daily."],
        ))
        assert not res.passed
        assert "0.002 mg" in res.blocked_reason


# ═══════════════════════════════════════════════════════════════════════
# System Prompt & Remediation Redesign (BLUF, answer shapes, Non-FTS)
# ═══════════════════════════════════════════════════════════════════════


class TestSystemPromptArchitecture:
    """Verifies the BLUF directive, question-shaped answer headers, and non-FTS remediation."""

    def test_system_prompt_bluf_and_answer_shapes(self):
        context = extract_patient_context({
            "age": "65",
            "sex": "female",
            "conditions": ["Atrial Fibrillation"],
            "medications": ["Metoprolol 25 mg"],
            "allergies": ["Penicillin"],
            "notes": "eGFR 45 mL/min",
        })
        prompt = build_system_prompt(patient_context=context)

        # Retrieval protocol and closed-world grounding come before formatting
        assert "RETRIEVE FIRST" in prompt
        assert "FIDELITY" in prompt
        assert "BLUF:" in prompt
        assert prompt.index("RETRIEVE FIRST") < prompt.index("FIDELITY") < prompt.index("ANSWER STRUCTURE") < prompt.index("OUTPUT MECHANICS")
        assert "If nothing relevant was retrieved" in prompt, "whole-answer abstention must be stated"

        # Structure follows the question; headings are named by the question or the source,
        # never by a closed list of shapes baked into the prompt.
        assert "ANSWER STRUCTURE" in prompt
        assert "retrieved section's own title" in prompt
        assert "Never force a heading" in prompt
        assert "instead of filling it" in prompt
        assert "No preamble" in prompt
        assert "When to Escalate" not in prompt, "no fixed shape taxonomy"
        assert "First-Line Regimen & Dosing" not in prompt, "no fixed dosing template"

        # Patient profile parsing
        assert "Age: 65" in prompt
        assert "Sex: female" in prompt
        assert "Atrial Fibrillation" in prompt
        assert "Metoprolol 25 mg" in prompt
        assert "Penicillin" in prompt
        assert "eGFR 45 mL/min" in prompt

    def test_extract_patient_context_gender_and_sex(self):
        ctx1 = extract_patient_context({"age": "50", "sex": "male"})
        assert "Sex: male" in ctx1

        ctx2 = extract_patient_context({"age": "50", "gender": "female"})
        assert "Sex: female" in ctx2

    def test_extract_patient_context_allergies_and_notes(self):
        ctx = extract_patient_context({
            "allergies": ["sulfa", "penicillin"],
            "notes": "Renal transplant recipient",
        })
        assert "Allergies: sulfa, penicillin" in ctx
        assert "Notes: Renal transplant recipient" in ctx

    def test_build_correction_prompt_non_fts_missing_sections(self):
        prompt = build_correction_prompt(
            violations=[Violation.of("NO_SECTIONS", "Answer requires section or table content retrieval.")],
            evidence_summary="No section text was successfully fetched in the prior turn.",
        )
        assert "search_topic_sections" not in prompt
        assert "searchTopicSections" not in prompt
        assert "EVIDENCE GATE PAUSE" in prompt
        assert "MANDATORY NEXT STEP" in prompt
        # RECONCILIATION 2026-10-26: the legacy 3.4a branch renders camelCase tool
        # names per the Kotlin registry (it wrote snake_case before the template fix).
        assert "get_topic_sections_text" not in prompt
        assert "getTopicSectionsText(topicId, sectionIds)" in prompt
        assert "getGraphicContent(graphicId)" in prompt

    def test_build_correction_prompt_non_fts_contraindication(self):
        prompt = build_correction_prompt(
            violations=[Violation.of("VISUAL", "Answer describes visual details of a graphic.", target="phase2")],
            evidence_summary="Section [H1]: Content.",
        )
        assert "search_topic_sections" not in prompt
        assert "SAFETY REVISION" in prompt
        assert "VISUAL" in prompt
        assert "REVISION RULES" in prompt
        assert "get_topic_overview(" not in prompt
        assert "get_topic_sections_text(" not in prompt

    def test_build_correction_prompt_non_fts_unverified_quantities(self):
        prompt = build_correction_prompt(
            violations=[Violation.of("UNVERIFIED_QTY", "Unverified clinical quantities found in response: 750 mg.", target="phase2")],
            evidence_summary="Section [H1]: 500 mg.",
        )
        assert "search_topic_sections" not in prompt
        assert "SAFETY REVISION" in prompt
        assert "UNVERIFIED_QTY" in prompt
        assert "750 mg" in prompt
        assert "get_topic_overview(" not in prompt
        assert "get_topic_sections_text(" not in prompt


# ═══════════════════════════════════════════════════════════════════════
# Change 2: typed violations, exact remediation templates, QTY top-up
# ═══════════════════════════════════════════════════════════════════════


class TestViolationTargetMap:
    """Violation codes map onto the phase target that routes remediation."""

    def test_targets(self):
        from safety_validator import (
            PHASE1_TARGET,
            PHASE2_TARGET,
            VIOLATION_TARGETS,
        )
        assert VIOLATION_TARGETS["NO_TOOLS"] == PHASE1_TARGET
        assert VIOLATION_TARGETS["NO_SECTIONS"] == PHASE1_TARGET
        assert VIOLATION_TARGETS["BLANK_NO_EVIDENCE"] == PHASE1_TARGET
        assert VIOLATION_TARGETS["BLANK_WITH_EVIDENCE"] == PHASE2_TARGET
        assert VIOLATION_TARGETS["UNVERIFIED_QTY"] == PHASE2_TARGET
        assert VIOLATION_TARGETS["VISUAL"] == PHASE2_TARGET

    def test_violation_of_defaults_target(self):
        from safety_validator import Violation
        assert Violation.of("NO_SECTIONS", "detail").target == "phase1"
        assert Violation.of("BLANK_WITH_EVIDENCE", "detail").target == "phase2"


class TestChange2TemplateRendering:
    """The exact Change-2 templates (must not be paraphrased)."""

    def test_evidence_gate_pause_prompt_exact_shape(self):
        from system_prompt import build_evidence_gate_pause_prompt
        prompt = build_evidence_gate_pause_prompt("")
        self._assert_evidence_gate_pause(prompt, summary="Nothing has been retrieved yet.")

    def test_evidence_gate_pause_prompt_carries_summary(self):
        from system_prompt import build_evidence_gate_pause_prompt
        prompt = build_evidence_gate_pause_prompt("Section [H1] (Dosing):\n500 mg.")
        self._assert_evidence_gate_pause(prompt, summary="Section [H1] (Dosing):\n500 mg.")

    @staticmethod
    def _assert_evidence_gate_pause(prompt: str, summary: str) -> None:
        assert "SYSTEM NOTICE — EVIDENCE GATE PAUSE:" in prompt
        assert summary in prompt
        assert "MANDATORY NEXT STEP (tool calls only, no text):" in prompt
        # camelCase tool-name literals (Kotlin registry text), never the Python snake_case registry
        assert "getTopicSectionsText(" in prompt
        assert "getGraphicContent(" in prompt
        assert "searchTopics(" in prompt
        assert "getTopicOverview(" in prompt
        assert "get_topic_sections_text" not in prompt
        assert "get_topic_overview" not in prompt

    def test_revision_prompt_renders_one_line_per_violation(self):
        from safety_validator import Violation
        from system_prompt import build_revision_prompt
        violations = [
            Violation.of("UNVERIFIED_QTY", "Unverified clinical quantities found in response: 10 mg. Ensure every quantity matches."),
            Violation.of("VISUAL", "Answer describes visual details of a graphic or scan that were not retrieved as text."),
        ]
        prompt = build_revision_prompt(
            violations,
            draft="Apixaban 10 mg twice daily.",
            evidence_summary="Section [H1] (Dosing):\nApixaban 5 mg twice daily.",
        )
        assert "SYSTEM NOTICE — SAFETY REVISION (NO TOOLS ACTIVE):" in prompt
        assert "[UNVERIFIED_QTY] Unverified clinical quantities found in response: 10 mg. Ensure every quantity matches." in prompt
        assert "[VISUAL] Answer describes visual details of a graphic or scan that were not retrieved as text." in prompt
        assert "Apixaban 10 mg twice daily." in prompt
        assert "Apixaban 5 mg twice daily." in prompt
        assert "REVISION RULES:" in prompt
        assert "[BLANK_WITH_EVIDENCE]" in prompt
        assert "Apixaban 10 mg twice daily." in prompt
        assert "Apixaban 5 mg twice daily." in prompt
        assert "REVISION RULES:" in prompt
        assert "[BLANK_WITH_EVIDENCE]" in prompt

    def test_revision_prompt_empty_draft_placeholder(self):
        from safety_validator import Violation
        from system_prompt import build_revision_prompt
        prompt = build_revision_prompt(
            [Violation.of("VISUAL", "Answer describes visual details of a graphic or scan that were not retrieved as text.")],
            draft="",
            evidence_summary="Section [T1] (Table).",
        )
        assert "(none: no answer was produced)" in prompt

    def test_revision_prompt_accepts_dict_violations(self):
        from system_prompt import build_revision_prompt
        prompt = build_revision_prompt(
            [{"code": "VISUAL", "detail": "Answer describes visual details."}],
            draft="d",
            evidence_summary="e",
        )
        assert "[VISUAL] Answer describes visual details." in prompt

    def test_premature_interception_prompt_exact_shape(self):
        from system_prompt import build_premature_interception_prompt
        prompt = build_premature_interception_prompt()
        assert "SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED." in prompt
        assert "Write no text. Respond ONLY with tool calls:" in prompt
        # snake_case names matched to the Python Phase-1 tool registry
        assert "search_topics(" in prompt
        assert "get_topic_overview(" in prompt
        assert "get_topic_sections_text(" in prompt
        assert "get_graphic_content(" in prompt
        assert "You will write the answer after the section text is in context." in prompt
        # The exact notice takes no argument and is consumer-agnostic to the model attempt
        assert "{attempt}" not in prompt


# ═══════════════════════════════════════════════════════════════════════
# Prompt conformance evaluation (eval_prompt.py) — deterministic scoring
# ═══════════════════════════════════════════════════════════════════════


class TestPromptEvaluation:
    """The scorer that turns 'is the prompt good?' into measured checks."""

    def _case(self, case_id: str) -> dict:
        import eval_prompt

        return next(c for c in eval_prompt.load_cases() if c["id"] == case_id)

    def test_case_file_is_well_formed(self):
        import eval_prompt

        cases = eval_prompt.load_cases()
        assert len(cases) >= 8
        ids = [c["id"] for c in cases]
        assert len(ids) == len(set(ids)), "case ids must be unique"
        for case in cases:
            assert case["question"]
            assert "shapes" not in case, "structure is no longer scored against a taxonomy"

    def test_compliant_dose_answer_scores_clean(self):
        import eval_prompt

        answer = (
            "**Start apixaban 5 mg twice daily.**\n"
            "**First-Line Regimen & Dosing**\n- 5 mg twice daily.\n"
            "**Organ Function & Adjustments**\n- CrCl 15 to 29 mL/min: 2.5 mg twice daily.\n"
            "**Contraindications & Critical Warnings**\n- Active bleeding."
        )
        row = eval_prompt.score_turn(self._case("apixaban-af-dosing"), answer, True, 2)
        assert all(row["checks"].values()), row
        assert "First-Line Regimen & Dosing" in row["headers"]

    def test_fabricated_dosing_table_is_caught_by_grounding(self):
        """The regression that motivated the audit: numbers the validator could not trace."""
        import eval_prompt

        answer = (
            "**Use symptomatic therapy.**\n"
            "**First-Line Regimen & Dosing**\n- Dextromethorphan 60 mg every 4 to 6 hours.\n"
            "- Oxymetazoline 0.03% nasal spray."
        )
        row = eval_prompt.score_turn(self._case("cold-adult-management"), answer, False, 2)
        assert row["checks"]["grounded"] is False

    def test_retrieved_doses_are_acceptable_on_a_management_question(self):
        """Fetching the drug topics is the better answer, not a violation."""
        import eval_prompt

        answer = (
            "**Symptomatic therapy may be used.**\n"
            "**Dosing**\n- Acetaminophen 325 to 650 mg every 4 to 6 hours."
        )
        row = eval_prompt.score_turn(self._case("cold-adult-management"), answer, True, 3)
        assert row["checks"]["grounded"] is True
        assert row["checks"]["bluf"] is True

    def test_unanswerable_question_accepts_withheld_or_declared_unknown(self):
        import eval_prompt

        case = self._case("unfindable-drug")

        withheld = "Clinical Response Verification Blocked: no evidence retrieved"
        assert all(eval_prompt.score_turn(case, withheld, False, 0)["checks"].values())

        declared = "**Zilofemide is not referenced in the retrieved sections; no dosing information is available.**"
        assert all(eval_prompt.score_turn(case, declared, True, 3)["checks"].values())

        drafted = "**Start zilofemide 10 mg daily.**\n**First-Line Regimen & Dosing**\n- 10 mg daily."
        row = eval_prompt.score_turn(case, drafted, True, 1)
        assert row["checks"]["no_doses"] is False
        assert row["checks"]["declared_unknown"] is False

    def test_withheld_turn_on_answerable_case_fails_format(self):
        import eval_prompt

        withheld = "Clinical Response Verification Blocked: boom"
        row = eval_prompt.score_turn(self._case("apixaban-af-dosing"), withheld, False, 1)
        assert row["checks"]["bluf"] is False

    def test_bold_title_is_not_a_bluf(self):
        """A heading in the bottom-line slot tells the reader nothing."""
        import eval_prompt

        titled = "**Management of the common cold in adults:**\n- Analgesics for pain."
        assert eval_prompt._is_bluf(titled) is False

        real = "**Start symptomatic therapy; antibiotics are not indicated.**\n- Analgesics."
        assert eval_prompt._is_bluf(real) is True

        labelled = "**BLUF:** start symptomatic therapy.\n- Analgesics."
        assert eval_prompt._is_bluf(labelled) is True

    def test_preamble_and_missing_bluf_are_flagged(self):
        import eval_prompt

        answer = "Based on the retrieved evidence, **start symptomatic therapy.**\n**Preferred**\n- Analgesics."
        row = eval_prompt.score_turn(self._case("cold-adult-management"), answer, True, 1)
        assert row["checks"]["bluf"] is False
        assert row["checks"]["no_preamble"] is False

    def test_header_extraction_is_informational_only(self):
        import eval_prompt

        answer = "**BLUF:** start here.\n### **Common Etiologies**\n- malignancy\n## **Cautions**\n- none"
        assert eval_prompt.extract_headers(answer) == ["BLUF", "Common Etiologies", "Cautions"]
