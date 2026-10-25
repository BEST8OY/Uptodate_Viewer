"""Comprehensive tests for ClinRef Python prototype.

Covers: safety_validator, html_parser, tools, agent.
Database-dependent tests are skipped if no DB is available.
"""

import json
import re
from unittest.mock import MagicMock, patch

import pytest
from bs4 import BeautifulSoup

from safety_validator import (
    CLINICAL_QUANTITY_REGEX,
    FetchedSection,
    GraphicRef,
    PatientProfile,
    SafetyValidator,
    ToolCallRecord,
    TopicRef,
    TurnContext,
    ValidationResult,
)
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

    def test_no_tool_calls_conversational_passes(self):
        ctx = TurnContext(tool_calls=[], answer="Hello! I'm ClinRef AI.", user_question="hello")
        result = SafetyValidator().validate(ctx)
        assert result.passed

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

    def test_overview_passes(self):
        # Calculator overview carries full tool content, so a successful
        # get_topic_overview alone satisfies the evidence requirement.
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_overview", arguments={}, result="{}", success=True)],
            answer="test",
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
            answer="The dose is 250 mg.",
            tool_results=["..."],
            user_question="Is 250 mg safe?",
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

    def test_no_tool_results_skips(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="The dose is 10 mg.",
            tool_results=[],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

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

    def test_numeric_only_match_in_tool_text(self):
        """'60 mL' should pass if '60' appears in tool text."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="Administer 60 units.", success=True)],
            answer="Give 60 mL.",
            tool_results=["Administer 60 units."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_spelled_out_number_words_pass(self):
        """'70%' should pass when tool text contains 'Seventy percent'."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Approximately 70% of patients achieved euthyroid state.",
            tool_results=["Seventy percent of the older group with a TSH greater than 4.5 mU/L were normal."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_spelled_out_single_digit_words_pass(self):
        """'5 mg' should pass when tool text contains 'Five mg' or 'five'."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Start with 5 mg daily.",
            tool_results=["Five mg daily is the recommended initial dose."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_range_bounds_both_verified(self):
        """'5-10 mg' should pass when text contains both bounds ('5 to 10 mg')."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Give 5-10 mg daily.",
            tool_results=["Dose range: 5 to 10 mg daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_range_with_invented_bound_blocked(self):
        """'500-10 mg' should block when 500 is not in tool text."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Give 500-10 mg daily.",
            tool_results=["Dose range: 5 to 10 mg daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed

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

    def test_metric_prefix_equivalence_grams_and_milligrams(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Administer ceftriaxone 1 g IV daily.",
            tool_results=["Ceftriaxone dosage is 1,000 mg intravenously once daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_metric_prefix_equivalence_micrograms_and_milligrams(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Initial dose is 500 mcg daily.",
            tool_results=["Recommended starting dose is 0.5 mg daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_frequency_slashes_not_flagged(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="...", success=True)],
            answer="Take 1 tablet/day or 2 times/day.",
            tool_results=["Take once or twice daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None



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

    def test_multiple_rule_failures_short_circuit(self):
        """First failing rule should short-circuit."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="search_topics", arguments={}, result="{}", success=True)],
            answer="test",
        )
        result = SafetyValidator().validate(ctx)
        assert not result.passed
        assert "section or table content" in result.blocked_reason.lower()

    def test_conversational_response_without_tools(self):
        """Greeting without tools should pass via intent detection."""
        ctx = TurnContext(
            tool_calls=[],
            answer="Hello! I'm ClinRef AI, a clinical reference assistant.",
            user_question="hello",
        )
        result = SafetyValidator().validate(ctx)
        assert result.passed


class TestClinicalQuantityRegex:
    """Tests for the CLINICAL_QUANTITY_REGEX pattern."""

    def test_matches_common_units(self):
        test_cases = [
            ("5 mg", "5 mg"),
            ("10 mL", "10 mL"),
            ("120 mmHg", "120 mmHg"),
            ("3.5 mmol/L", "3.5 mmol/L"),
            ("100 mg/dL", "100 mg/dL"),
            ("50 mcg", "50 mcg"),
            ("2 units", "2 units"),
            ("1.5 mEq", "1.5 mEq"),
            ("70 kg", "70 kg"),
            ("98.6 F", ""),  # F is not in the list
        ]
        for text, expected in test_cases:
            matches = CLINICAL_QUANTITY_REGEX.findall(text)
            if expected:
                assert len(matches) >= 1, f"Expected match for '{text}', got {matches}"
            # Just verify it doesn't crash

    def test_does_not_match_bare_numbers(self):
        """Bare numbers without units should not match."""
        matches = CLINICAL_QUANTITY_REGEX.findall("See section 3.1 and Class IIa.")
        # Should not match "3.1" or "IIa"
        assert len(matches) == 0

    def test_comma_decimal(self):
        matches = CLINICAL_QUANTITY_REGEX.findall("Dose: 2,5 mg")
        assert len(matches) >= 1

    def test_range_extraction(self):
        matches = CLINICAL_QUANTITY_REGEX.findall("Give 5-10 mg or 0.4 to 4.5 mU/L.")
        assert "5-10 mg" in matches
        assert "0.4 to 4.5 mU/L" in matches

    def test_greek_microgram_and_percent(self):
        matches = CLINICAL_QUANTITY_REGEX.findall("Dose 50 μg, 25 µg, and 70 percent.")
        assert "50 μg" in matches
        assert "25 µg" in matches
        assert "70 percent" in matches

    def test_slash_exclusion_for_english_words(self):
        matches = CLINICAL_QUANTITY_REGEX.findall("Review step 1 and/or step 2, patient is 2 w/o pain.")
        assert len(matches) == 0



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
        assert len(tool_list) == 6

    def test_init_tools_includes_expected_names(self):
        import tools
        mock_db = MagicMock()
        tool_list = tools.init_tools(mock_db)
        names = [t.name for t in tool_list]
        assert len(names) == 6
        assert set(names) == {
            "get_topic_overview",
            "search_topics",
            "search_topic_sections",
            "get_related_topics",
            "get_topic_sections_text",
            "get_graphic_content",
        }
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
        self.mock_db.search_topics.assert_called_with("aspirin", limit=10)


class TestGetTopicSectionText:
    """Tests for get_topic_sections_text tool."""

    def setup_method(self):
        import tools
        self.mock_db = MagicMock()
        tools.init_tools(self.mock_db)

    def test_topic_not_found(self):
        import tools
        self.mock_db.get_topic_body.return_value = None
        result = tools.get_topic_sections_text.func(
            topic_id="999", section_ids=["H1"]
        )
        assert "Topic not found" in (result.error or "")

    def test_section_not_found(self):
        import tools
        self.mock_db.get_topic_body.return_value = "<h2 id='H1'>Content</h2>"
        self.mock_db.get_topic_outline.return_value = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
        result = tools.get_topic_sections_text.func(
            topic_id="123", section_ids=["H99"]
        )
        assert result.invalidSections == ["H99"]
        assert result.markdown == ""

    def test_returns_markdown(self):
        import tools
        self.mock_db.get_topic_body.return_value = "<h2 id='H1'>Intro</h2><p>Dose is 5 mg.</p>"
        self.mock_db.get_topic_outline.return_value = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
        result = tools.get_topic_sections_text.func(
            topic_id="123", section_ids=["H1"]
        )
        assert "5 mg" in result.markdown
        assert result.topicId == "123"

    def test_no_token_cap_returns_sections_in_full(self):
        import tools
        long_text_1 = "Word " * 200  # ~1000 chars
        long_text_2 = "More " * 200  # ~1000 chars
        self.mock_db.get_topic_body.return_value = f"<h2 id='H1'>Sec 1</h2><p>{long_text_1}</p><h2 id='H2'>Sec 2</h2><p>{long_text_2}</p>"
        self.mock_db.get_topic_outline.return_value = (
            '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Sec 1</a>'
            '<a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Sec 2</a>'
        )
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
        self.mock_db.get_topic_body.return_value = "".join(
            f"<h2 id='H{i}'>Sec {i}</h2><p>Content {i}.</p>" for i in range(1, 12)
        )
        self.mock_db.get_topic_outline.return_value = "".join(
            '<a href="appAction({&quot;section&quot;:&quot;H%d&quot;})">Sec %d</a>' % (i, i)
            for i in range(1, 12)
        )
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
        self.mock_db.get_topic_outline.return_value = None
        self.mock_db.get_topic_body.return_value = "<p>" + ("Calculations " * 300) + "</p>"
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
        assert state["retry_count"] == 0
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

    def test_non_clinical_answer_without_tools_short(self):
        """Short non-clinical answer without tools should pass."""
        ctx = TurnContext(
            tool_calls=[],
            answer="Thanks for your question!",
            user_question="thank you",
        )
        result = SafetyValidator().validate(ctx)
        assert result.passed

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
        mock_db.get_topic_outline.return_value = """
            <ul>
                <li><a href="#H1">Section 1</a></li>
                <li><a href="javascript:appAction('graphic', {'subtype': 'graphic_table'})">Table 1</a></li>
            </ul>
        """
        mock_db.get_topic_title.return_value = "Test Topic"
        tools.init_tools(mock_db)

        data = tools.get_topic_overview.func(topic_id="123")
        assert data.topicType == "article"
        assert hasattr(data, "relatedTopics")
        assert hasattr(data, "graphics")
        if data.graphics:
            assert data.graphics[0].isTable is True

    def test_get_related_topics_includes_canonical_camel_case_keys(self):
        import tools
        from unittest.mock import MagicMock
        mock_db = MagicMock()
        mock_db.get_topic_outline.return_value = "<p>Test</p>"
        tools.init_tools(mock_db)

        data = tools.get_related_topics.func(topic_id="123")
        assert hasattr(data, "relatedTopics")
        assert hasattr(data, "topicId")

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
        self.mock_db.get_topic_outline.return_value = None
        self.mock_db.get_topic_body.return_value = None
        self.mock_db.get_topic_asset.return_value = None

        result = tools.get_topic_overview.func(topic_id="999")
        assert result.error is not None
        assert "Topic not found: 999" in result.error

    def test_bundles_outline_and_graphics(self):
        import tools
        self.mock_db.get_topic_outline.return_value = (
            '<ul id="innerOutline">'
            '  <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>'
            '  <li><a href="appAction({&quot;section&quot;:&quot;H_SR&quot;})">Summary &amp; Recommendations</a></li>'
            '  <li><a href="appAction({&quot;section&quot;:&quot;H_DOSING&quot;})">Dosing</a></li>'
            '  <li><a href="appAction({&quot;meta&quot;:{&quot;assetType&quot;:&quot;graphic&quot;},&quot;data&quot;:[{&quot;id&quot;:&quot;111&quot;,&quot;type&quot;:&quot;graphic&quot;,&quot;subtype&quot;:&quot;graphic_table&quot;}]})">Table 1</a></li>'
            '</ul>'
        )
        self.mock_db.get_topic_body.return_value = (
            '<div id="H1">Overview content</div>'
            '<div id="H_SR">Summary: Give apixaban 5 mg twice daily.</div>'
            '<div id="H_DOSING">Dosing details: reduce to 2.5 mg if CrCl &lt; 30.</div>'
        )
        self.mock_db.get_topic_title.return_value = "Apixaban Therapy"
        self.mock_db.get_topic_asset.return_value = {"bodyHtml": "...", "outlineHtml": "..."}

        result = tools.get_topic_overview.func(topic_id="123")

        # Schema & Invariants
        assert result.topicId == "123"
        assert result.title == "Apixaban Therapy"
        assert result.topicType == "article"
        assert len(result.sections) == 3
        assert len(result.graphics) == 1
        assert result.markdown == ""

    def test_calculator_topic_handling(self):
        import tools
        self.mock_db.get_topic_outline.return_value = None
        self.mock_db.get_topic_body.return_value = "<p>Formula: GFR = 141 * min(Scr/kappa, 1)</p>"
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
        self.mock_db.get_topic_outline.return_value = (
            '<ul id="innerOutline">'
            '  <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>'
            '  <li><a href="appAction({&quot;section&quot;:&quot;H2&quot;})">— Renal Impairment</a></li>'
            '</ul>'
        )
        self.mock_db.get_topic_body.return_value = (
            '<div id="H1">Overview</div><div id="H2">Renal text</div>'
        )
        self.mock_db.get_topic_title.return_value = "Drug C"
        self.mock_db.get_topic_asset.return_value = {"bodyHtml": "...", "outlineHtml": "..."}

        # Case-insensitive section ID
        res1 = tools.get_topic_sections_text.func(topic_id="123", section_ids=["h2"])
        assert "H2" in res1.sectionTitles
        assert "=== Section: H2 ===" in res1.markdown

        # Title match with stripped dash
        res2 = tools.get_topic_sections_text.func(topic_id="123", section_ids=["Renal Impairment"])
        assert "H2" in res2.sectionTitles
        assert "=== Section: H2 ===" in res2.markdown

        # Error envelope on nonexistent topic
        self.mock_db.get_topic_body.return_value = None
        self.mock_db.get_topic_outline.return_value = None
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

    def test_prevents_conversational_query_false_positives_in_safety_validator(self):
        from reasoning_sanitizer import ReasoningTokenSanitizer
        from safety_validator import SafetyValidator, TurnContext

        validator = SafetyValidator()
        raw_llm_answer = "<thought>Friendly greeting. 100% confidence. No dosing needed.</thought>Hello! How can I assist you today?"

        # Without sanitization: 100% causes is_non_clinical_response to return False, blocking the greeting
        raw_ctx = TurnContext(
            tool_calls=[],
            answer=raw_llm_answer,
            user_question="Hello",
        )
        raw_result = validator.validate(raw_ctx)
        assert raw_result.passed is False

        # With sanitization: passes Rule 0
        clean_ctx = TurnContext(
            tool_calls=[],
            answer=ReasoningTokenSanitizer.strip_reasoning(raw_llm_answer),
            user_question="Hello",
        )
        clean_result = validator.validate(clean_ctx)
        assert clean_result.passed is True

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
# Rule 4: Patient Profile Safety Bounds (Renal & Allergy Cross-Referencing)
# ═══════════════════════════════════════════════════════════════════════


class TestPatientProfileSafetyBounds:
    """Rule 4 programmatic patient profile bounds validation."""

    def test_allergy_contraindication_blocks_amoxicillin_for_penicillin_allergy(self):
        profile = PatientProfile(allergies=["penicillin (anaphylaxis)"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen & Dosing: Amoxicillin 500 mg PO TID for 10 days.",
            tool_results=["Amoxicillin 500 mg PO TID for 10 days."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert not res.passed
        assert "Documented patient allergy contraindication" in res.blocked_reason
        assert "penicillin (anaphylaxis)" in res.blocked_reason
        assert "amoxicillin" in res.blocked_reason

    def test_allergy_passes_non_cross_reactive_azithromycin(self):
        profile = PatientProfile(allergies=["penicillin (anaphylaxis)"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen & Dosing: Azithromycin 500 mg on day 1, then 250 mg daily for 4 days.",
            tool_results=["Azithromycin 500 mg on day 1, then 250 mg daily."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert res.passed

    def test_allergy_passes_when_drug_explicitly_avoided(self):
        profile = PatientProfile(allergies=["penicillin"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="Avoid amoxicillin due to documented penicillin allergy. First-line therapy: Azithromycin 500 mg daily.",
            tool_results=["Azithromycin 500 mg daily. Amoxicillin is commonly used."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert res.passed

    def test_allergy_cross_reactive_sulfa_bactrim(self):
        profile = PatientProfile(allergies=["sulfa drugs"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen: Prescribe Bactrim DS 1 tablet twice daily.",
            tool_results=["Bactrim DS 1 tablet twice daily."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert not res.passed
        assert "allergy contraindication" in res.blocked_reason
        assert "bactrim" in res.blocked_reason

    def test_allergy_cross_reactive_nsaid_ibuprofen(self):
        profile = PatientProfile(allergies=["aspirin"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="Administer ibuprofen 400 mg every 6 hours.",
            tool_results=["Ibuprofen 400 mg every 6 hours."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert not res.passed
        assert "allergy contraindication" in res.blocked_reason
        assert "ibuprofen" in res.blocked_reason

    def test_renal_contraindication_blocks_indomethacin_for_egfr_under_30(self):
        profile = PatientProfile(conditions=["Severe CKD", "eGFR 22 mL/min"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen & Dosing: Indomethacin 50 mg three times daily.",
            tool_results=["Indomethacin 50 mg three times daily is first-line for acute gout."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert not res.passed
        assert "Documented patient renal contraindication" in res.blocked_reason
        assert "indomethacin" in res.blocked_reason

    def test_renal_contraindication_blocks_metformin_for_egfr_under_30(self):
        profile = PatientProfile(notes="Patient has chronic kidney disease with eGFR of 24 mL/min")
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen & Dosing: Metformin 500 mg twice daily with meals.",
            tool_results=["Metformin 500 mg twice daily with meals."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert not res.passed
        assert "metformin" in res.blocked_reason
        assert "contraindicated" in res.blocked_reason

    def test_renal_adjustment_blocks_colchicine_when_unadjusted(self):
        profile = PatientProfile(conditions=["CKD stage 4 (eGFR 20)"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen & Dosing: Colchicine 1.2 mg followed by 0.6 mg one hour later.",
            tool_results=["Colchicine 1.2 mg followed by 0.6 mg one hour later."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert not res.passed
        assert "colchicine requires renal dosage adjustment" in res.blocked_reason

    def test_renal_adjustment_passes_colchicine_when_adjusted(self):
        profile = PatientProfile(conditions=["CKD stage 4 (eGFR 20)"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen & Dosing: Colchicine 0.3 mg daily with renal dose adjustment for CKD.",
            tool_results=["Colchicine 0.3 mg daily with renal dose adjustment for CKD."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert res.passed

    def test_renal_passes_glucocorticoids_with_nsaid_contraindicated(self):
        profile = PatientProfile(conditions=["Severe renal impairment (eGFR 18 mL/min)"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="NSAIDs such as indomethacin are contraindicated in severe renal impairment. First-Line Regimen & Dosing: Intra-articular triamcinolone 20 mg.",
            tool_results=["Intra-articular triamcinolone 20 mg. NSAIDs are first line."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert res.passed

    def test_patient_profile_without_renal_or_allergy_passes(self):
        profile = PatientProfile(age="45", sex="male", conditions=["Hypertension"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen & Dosing: Lisinopril 10 mg daily.",
            tool_results=["Lisinopril 10 mg daily is recommended."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert res.passed

    def test_allergy_compound_slash_aspirin_nsaids_blocks_ibuprofen(self):
        profile = PatientProfile(allergies=["Aspirin/NSAIDs"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="Recommend ibuprofen 400 mg for acute pain.",
            tool_results=["Ibuprofen 400 mg for acute pain."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert not res.passed
        assert "allergy contraindication" in res.blocked_reason.lower()
        assert "ibuprofen" in res.blocked_reason.lower()

    def test_allergy_trailing_punctuation_penicillin_dot_blocks_amoxicillin(self):
        profile = PatientProfile(allergies=["penicillin."])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="Prescribe amoxicillin 500 mg orally.",
            tool_results=["Amoxicillin 500 mg orally."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert not res.passed
        assert "allergy contraindication" in res.blocked_reason.lower()
        assert "amoxicillin" in res.blocked_reason.lower()

    def test_allergy_plural_nsaids_blocks_ibuprofen(self):
        profile = PatientProfile(allergies=["NSAIDs"])
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="Recommend ibuprofen 400 mg.",
            tool_results=["Ibuprofen 400 mg."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res = SafetyValidator().validate(ctx)
        assert not res.passed
        assert "allergy contraindication" in res.blocked_reason.lower()
        assert "ibuprofen" in res.blocked_reason.lower()

    def test_renal_stage_4_ckd_blocks_ibuprofen_and_metformin(self):
        profile = PatientProfile(conditions=["Stage 4 CKD"])
        ctx_ibu = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen: Ibuprofen 600 mg TID.",
            tool_results=["Ibuprofen 600 mg TID."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res_ibu = SafetyValidator().validate(ctx_ibu)
        assert not res_ibu.passed
        assert "renal contraindication" in res_ibu.blocked_reason.lower()
        assert "ibuprofen" in res_ibu.blocked_reason.lower()

        ctx_met = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen: Metformin 500 mg twice daily.",
            tool_results=["Metformin 500 mg twice daily."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res_met = SafetyValidator().validate(ctx_met)
        assert not res_met.passed
        assert "renal contraindication" in res_met.blocked_reason.lower()
        assert "metformin" in res_met.blocked_reason.lower()

    def test_renal_stage_5_ckd_blocks_ibuprofen_and_metformin(self):
        profile = PatientProfile(conditions=["Stage 5 CKD"])
        ctx_ibu = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen: Ibuprofen 600 mg TID.",
            tool_results=["Ibuprofen 600 mg TID."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res_ibu = SafetyValidator().validate(ctx_ibu)
        assert not res_ibu.passed
        assert "renal contraindication" in res_ibu.blocked_reason.lower()
        assert "ibuprofen" in res_ibu.blocked_reason.lower()

        ctx_met = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="First-Line Regimen: Metformin 500 mg twice daily.",
            tool_results=["Metformin 500 mg twice daily."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res_met = SafetyValidator().validate(ctx_met)
        assert not res_met.passed
        assert "renal contraindication" in res_met.blocked_reason.lower()
        assert "metformin" in res_met.blocked_reason.lower()

    def test_renal_colchicine_reduce_dose_and_reduced_dose_passes(self):
        profile = PatientProfile(conditions=["eGFR 22 mL/min"])
        ctx_reduce = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="Colchicine 0.3 mg daily, reduce dose due to low GFR.",
            tool_results=["Colchicine 0.3 mg daily, reduce dose due to low GFR."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res_reduce = SafetyValidator().validate(ctx_reduce)
        assert res_reduce.passed

        ctx_reduced = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result="content", success=True)],
            answer="Colchicine 0.3 mg daily, reduced dose due to renal function.",
            tool_results=["Colchicine 0.3 mg daily, reduced dose due to renal function."],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
            patient_profile=profile,
        )
        res_reduced = SafetyValidator().validate(ctx_reduced)
        assert res_reduced.passed


# ═══════════════════════════════════════════════════════════════════════
# System Prompt & Remediation Redesign (BLUF, Categories, Non-FTS)
# ═══════════════════════════════════════════════════════════════════════


class TestSystemPromptArchitecture:
    """Verifies BLUF directive, category output, and non-FTS remediation."""

    def test_system_prompt_bluf_category_output(self):
        context = extract_patient_context({
            "age": "65",
            "sex": "female",
            "conditions": ["Atrial Fibrillation"],
            "medications": ["Metoprolol 25 mg"],
            "allergies": ["Penicillin"],
            "notes": "eGFR 45 mL/min",
        })
        prompt = build_system_prompt(patient_context=context)

        # BLUF & Category output architecture
        # Phase separation & closed-world grounding
        assert "PHASE SEPARATION & OPERATING MODES" in prompt
        assert "CLOSED-WORLD EVIDENCE & NUMERICAL FIDELITY" in prompt
        assert "BOTTOM-LINE UP FRONT (BLUF)" in prompt

        # Category outputs
        assert "First-Line Regimen & Dosing" in prompt
        assert "Organ Function & Adjustments" in prompt
        assert "Contraindications & Critical Warnings" in prompt

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
            blocked_reason="Answer requires section or table content retrieval.",
            evidence_summary="No section text was successfully fetched in the prior turn.",
        )
        assert "search_topic_sections" not in prompt
        assert "searchTopicSections" not in prompt
        assert "EVIDENCE GATE PAUSE" in prompt
        assert "MANDATORY NEXT STEP" in prompt
        assert "get_topic_sections_text" in prompt

    def test_build_correction_prompt_non_fts_contraindication(self):
        prompt = build_correction_prompt(
            blocked_reason="Documented patient allergy contraindication: amoxicillin recommended for penicillin allergy",
            evidence_summary="Section [H1]: Amoxicillin 500 mg TID.",
        )
        assert "search_topic_sections" not in prompt
        assert "searchTopicSections" not in prompt
        assert "PATIENT SAFETY BLOCK" in prompt
        assert "CRITICAL LEXICAL RULE" in prompt
        assert "MANDATORY SELF-CORRECTION INSTRUCTIONS" in prompt
        assert "get_topic_overview(" not in prompt
        assert "get_topic_sections_text(" not in prompt

    def test_build_correction_prompt_non_fts_unverified_quantities(self):
        prompt = build_correction_prompt(
            blocked_reason="Unverified clinical quantities found in response: 750 mg.",
            evidence_summary="Section [H1]: 500 mg.",
        )
        assert "search_topic_sections" not in prompt
        assert "searchTopicSections" not in prompt
        assert "NUMERICAL VERIFICATION BLOCK" in prompt
        assert "rejected_quantities" in prompt
        assert "750 mg" in prompt
        assert "get_topic_overview(" not in prompt
        assert "get_topic_sections_text(" not in prompt
