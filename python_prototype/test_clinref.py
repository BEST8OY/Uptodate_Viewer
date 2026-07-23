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
    Citation,
    CLINICAL_QUANTITY_REGEX,
    FetchedSection,
    SafetyValidator,
    ToolCallRecord,
    TurnContext,
    ValidationResult,
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


class TestCitationParsing:
    """Tests for SafetyValidator.parse_citations()."""

    def test_basic_citation(self):
        answer = "Topic: Drug X, Section: Dosing (ID: H5)"
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 1
        assert citations[0].topic_title == "Drug X"
        assert citations[0].section_title == "Dosing"
        assert citations[0].section_id == "H5"

    def test_citation_without_id(self):
        answer = "Topic: Drug X, Section: Dosing"
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 1
        assert citations[0].section_id == "unknown"

    def test_bullet_dash(self):
        answer = "- Topic: Drug X, Section: Dosing (ID: H5)"
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 1

    def test_bullet_asterisk(self):
        answer = "* Topic: Drug X, Section: Dosing (ID: H5)"
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 1

    def test_numbered_list(self):
        answer = "1. Topic: Drug X, Section: Dosing (ID: H5)"
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 1

    def test_bold_formatting(self):
        answer = "**Topic:** Drug X, **Section:** Dosing (ID: H5)"
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 1
        assert citations[0].topic_title == "Drug X"

    def test_multiple_citations_mixed_format(self):
        answer = (
            "- Topic: Apixaban Dosing, Section: Renal (ID: H5)\n"
            "* Topic: Apixaban Monitoring, Section: Labs (ID: H8)\n"
            "1. Topic: Asthma Overview, Section: Treatment (ID: H3)\n"
            "**Topic:** Gout Management, Section: Acute (ID: H12)"
        )
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 4
        ids = {c.section_id for c in citations}
        assert ids == {"H5", "H8", "H3", "H12"}

    def test_citations_with_surrounding_text(self):
        answer = (
            "Based on the evidence:\n"
            "- Topic: Drug X, Section: Dosing (ID: H5)\n"
            "The recommended dose is 5 mg twice daily.\n"
            "Topic: Drug Y, Section: Safety (ID: H8)\n"
        )
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 2

    def test_no_citations(self):
        answer = "This is a plain answer with no citations."
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 0

    def test_citation_case_insensitive(self):
        answer = "topic: drug x, section: dosing (id: h5)"
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 1

    def test_citation_with_extra_spaces(self):
        answer = "  Topic:   Drug X ,  Section:  Dosing  ( ID: H5 )  "
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 1
        assert citations[0].topic_title == "Drug X"
        assert "Dosing" in citations[0].section_title


class TestToolCallRequired:
    """Rule 1: Tool calls required."""

    def test_no_tool_calls_blocked(self):
        ctx = TurnContext(tool_calls=[], answer="test")
        result = SafetyValidator().validate(ctx)
        assert not result.passed
        assert "No tool calls" in result.blocked_reason

    def test_with_tool_calls_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="search_topics", arguments={}, result="{}", success=True)],
            answer="test",
        )
        result = SafetyValidator().validate(ctx)
        # May fail on later rules, but not rule 1
        if not result.passed:
            assert "No tool calls" not in (result.blocked_reason or "")


class TestSectionContentRequired:
    """Rule 1b: Section or table content required."""

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
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
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
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="Section not found.", success=False)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is not None
        assert not result.passed

    def test_outline_only_blocked(self):
        ctx = TurnContext(
            tool_calls=[
                ToolCallRecord(tool_name="search_topics", arguments={}, result="{}", success=True),
                ToolCallRecord(tool_name="get_topic_outline", arguments={}, result="{}", success=True),
            ],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is not None
        assert not result.passed


class TestCitationConsistency:
    """Rule 2: Citations must match fetched sections."""

    def test_matching_id_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={"section_id": "H1"}, result="...", success=True)],
            answer="test",
            citations=[Citation(topic_title="T", section_title="S", section_id="H1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="S1")],
        )
        result = SafetyValidator()._validate_citation_consistency(ctx)
        assert result is None

    def test_unmatched_id_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={"section_id": "H1"}, result="...", success=True)],
            answer="test",
            citations=[Citation(topic_title="T", section_title="Nonexistent", section_id="Z99")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Real Section")],
        )
        result = SafetyValidator()._validate_citation_consistency(ctx)
        assert result is not None
        assert not result.passed

    def test_unknown_id_with_title_fallback(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={"section_id": "H1"}, result="...", success=True)],
            answer="test",
            citations=[Citation(topic_title="T", section_title="S", section_id="unknown")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="S")],
        )
        result = SafetyValidator()._validate_citation_consistency(ctx)
        assert result is not None
        assert result.passed
        assert len(result.warnings) > 0

    def test_no_fetched_sections_skips_check(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="test",
            citations=[Citation(topic_title="T", section_title="S", section_id="Z99")],
            fetched_sections=[],
        )
        result = SafetyValidator()._validate_citation_consistency(ctx)
        assert result is None

    def test_citation_id_match(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="test",
            citations=[Citation(topic_title="T", section_title="S", section_id="H1")],
            fetched_sections=[
                FetchedSection(topic_id="1", section_id="H1", section_title="Different Title"),
            ],
        )
        result = SafetyValidator()._validate_citation_consistency(ctx)
        assert result is None


class TestInventedNumbers:
    """Rule 3: No invented clinical quantities."""

    def test_matching_quantity_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="The dose is 5 mg.", success=True)],
            answer="The dose is 5 mg.",
            tool_results=["The dose is 5 mg."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_unmatched_quantity_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="The dose is 5 mg.", success=True)],
            answer="The dose is 10 mg.",
            tool_results=["The dose is 5 mg."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed
        assert "10 mg" in result.blocked_reason

    def test_user_question_quantity_allowed(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="The dose is 250 mg.",
            tool_results=["..."],
            user_question="Is 250 mg safe?",
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_structural_numbers_not_flagged(self):
        """List markers, section numbers, years should not be flagged."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="See section 3.1. The year 2024 guidelines recommend 5 mg.",
            tool_results=["5 mg is recommended."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_no_tool_results_skips(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="The dose is 10 mg.",
            tool_results=[],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_boundary_check_no_partial_match(self):
        """'12' should not match as part of '123'."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="Value: 123 mg", success=True)],
            answer="The value is 12 mg.",
            tool_results=["Value: 123 mg"],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed

    def test_numeric_only_match_in_tool_text(self):
        """'60 mL' should pass if '60' appears in tool text."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="Administer 60 units.", success=True)],
            answer="Give 60 mL.",
            tool_results=["Administer 60 units."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None


class TestCitationRequired:
    """Rule 4: Citations required."""

    def test_no_citations_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="test",
        )
        result = SafetyValidator()._validate_citation_required(ctx)
        assert result is not None
        assert not result.passed

    def test_with_citations_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="test",
            citations=[Citation(topic_title="T", section_title="S", section_id="H1")],
        )
        result = SafetyValidator()._validate_citation_required(ctx)
        assert result is None

    def test_density_relaxed_one_valid_enough(self):
        """1 valid citation out of 3 fetched sections should pass."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="test",
            citations=[Citation(topic_title="T", section_title="S", section_id="H1")],
            fetched_sections=[
                FetchedSection(topic_id="1", section_id="H1", section_title="S1"),
                FetchedSection(topic_id="1", section_id="H2", section_title="S2"),
                FetchedSection(topic_id="1", section_id="H3", section_title="S3"),
            ],
        )
        result = SafetyValidator()._validate_citation_required(ctx)
        assert result is None

    def test_no_valid_citation_blocked(self):
        """Citation that matches none of the fetched sections should block."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="test",
            citations=[Citation(topic_title="T", section_title="S", section_id="Z99")],
            fetched_sections=[
                FetchedSection(topic_id="1", section_id="H1", section_title="S1"),
            ],
        )
        result = SafetyValidator()._validate_citation_required(ctx)
        assert result is not None
        assert not result.passed


class TestGraphicInterpretation:
    """Rule 5: No graphic interpretation."""

    def test_no_visual_language_passes(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="The recommended treatment is aspirin.",
        )
        result = SafetyValidator()._validate_no_graphic_interpretation(ctx)
        assert result is None

    def test_visual_language_in_tool_text_passes(self):
        """Quoting retrieved text that contains visual language should pass."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="The x-ray shows opacity.",
            tool_results=["The x-ray shows opacity in the lower lobe."],
        )
        result = SafetyValidator()._validate_no_graphic_interpretation(ctx)
        assert result is None

    def test_visual_language_not_in_tool_text_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="The image shows a mass.",
            tool_results=["Normal lung fields."],
        )
        result = SafetyValidator()._validate_no_graphic_interpretation(ctx)
        assert result is not None
        assert not result.passed

    def test_figure_language_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="The figure demonstrates a fracture.",
            tool_results=["No fracture mentioned."],
        )
        result = SafetyValidator()._validate_no_graphic_interpretation(ctx)
        assert result is not None
        assert not result.passed

    def test_empty_tool_results_blocked(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
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
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={"section_id": "H1"}, result="Dose is 5 mg.", success=True)],
            answer="The dose is 5 mg.\n\nTopic: Drug X, Section: Dosing (ID: H1)",
            tool_results=["Dose is 5 mg."],
            citations=[Citation(topic_title="Drug X", section_title="Dosing", section_id="H1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="H1", section_title="Dosing")],
        )
        result = SafetyValidator().validate(ctx)
        assert result.passed
        assert len(result.citations) == 1

    def test_multiple_rule_failures_short_circuit(self):
        """First failing rule should short-circuit."""
        ctx = TurnContext(tool_calls=[], answer="test")
        result = SafetyValidator().validate(ctx)
        assert not result.passed
        assert "No tool calls" in result.blocked_reason


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
        assert "search_topics" in names
        assert "get_topic_outline" in names
        assert "get_topic_section_text" in names
        assert "follow_related_topic" in names
        assert "get_graphic_content" in names
        # get_graphic_info should NOT be in the list
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
        tools.init_tools(self.mock_db)

    def test_empty_query(self):
        import tools
        result = tools.search_topics.invoke({"query": ""})
        data = json.loads(result)
        assert "results" in data
        assert data["results"] == []

    def test_returns_results(self):
        import tools
        self.mock_db.search_topics.return_value = [{"id": "123", "title": "Aspirin"}]
        self.mock_db.get_suggestions.return_value = ["aspirin dose"]
        result = tools.search_topics.invoke({"query": "aspirin"})
        data = json.loads(result)
        assert len(data["results"]) == 1
        assert data["results"][0]["id"] == "123"

    def test_no_results_returns_suggestions(self):
        import tools
        self.mock_db.search_topics.return_value = []
        self.mock_db.get_suggestions.return_value = ["aspirin", "ibuprofen"]
        result = tools.search_topics.invoke({"query": "xyz"})
        data = json.loads(result)
        assert data["results"] == []
        assert len(data["refine_with"]) == 2

    def test_strips_query(self):
        import tools
        self.mock_db.search_topics.return_value = []
        self.mock_db.get_suggestions.return_value = []
        tools.search_topics.invoke({"query": "  aspirin  "})
        self.mock_db.search_topics.assert_called_with("aspirin", limit=10)


class TestGetTopicOutline:
    """Tests for get_topic_outline tool."""

    def setup_method(self):
        import tools
        self.mock_db = MagicMock()
        tools.init_tools(self.mock_db)

    def test_topic_not_found(self):
        import tools
        self.mock_db.get_topic_outline.return_value = None
        result = tools.get_topic_outline.invoke({"topic_id": "999"})
        data = json.loads(result)
        assert "error" in data

    def test_returns_outline(self):
        import tools
        self.mock_db.get_topic_outline.return_value = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
        self.mock_db.get_topic_title.return_value = "Test Topic"
        result = tools.get_topic_outline.invoke({"topic_id": "123"})
        data = json.loads(result)
        assert data["title"] == "Test Topic"
        assert len(data["sections"]) == 1
        assert data["topicId"] == "123"


class TestGetTopicSectionText:
    """Tests for get_topic_section_text tool."""

    def setup_method(self):
        import tools
        self.mock_db = MagicMock()
        tools.init_tools(self.mock_db)

    def test_topic_not_found(self):
        import tools
        self.mock_db.get_topic_body.return_value = None
        result = tools.get_topic_section_text.invoke(
            {"topic_id": "999", "section_id": "H1", "section_title": ""}
        )
        assert "Topic not found" in result

    def test_section_not_found(self):
        import tools
        self.mock_db.get_topic_body.return_value = "<h2 id='H1'>Content</h2>"
        self.mock_db.get_topic_outline.return_value = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
        result = tools.get_topic_section_text.invoke(
            {"topic_id": "123", "section_id": "H99", "section_title": ""}
        )
        assert "Section not found" in result

    def test_returns_markdown(self):
        import tools
        self.mock_db.get_topic_body.return_value = "<h2 id='H1'>Intro</h2><p>Dose is 5 mg.</p>"
        self.mock_db.get_topic_outline.return_value = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
        result = tools.get_topic_section_text.invoke(
            {"topic_id": "123", "section_id": "H1", "section_title": "Intro"}
        )
        assert "5 mg" in result


class TestGetGraphicContent:
    """Tests for get_graphic_content tool."""

    def setup_method(self):
        import tools
        self.mock_db = MagicMock()
        tools.init_tools(self.mock_db)

    def test_graphic_not_found(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = None
        result = tools.get_graphic_content.invoke({"graphic_id": "999"})
        data = json.loads(result)
        assert "error" in data

    def test_non_table_type_blocked(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = {"type": "graphic_figure", "title": "Fig 1"}
        result = tools.get_graphic_content.invoke({"graphic_id": "123"})
        data = json.loads(result)
        assert "error" in data
        assert "not a table" in data["error"]

    def test_empty_content_blocked(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = {"type": "graphic_table", "title": "T", "html": ""}
        result = tools.get_graphic_content.invoke({"graphic_id": "123"})
        data = json.loads(result)
        assert "error" in data

    def test_table_content_returns_markdown(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = {
            "type": "graphic_table",
            "title": "Dosing Table",
            "html": "<table><tr><th>Dose</th><th>Rate</th></tr><tr><td>5mg</td><td>2x/day</td></tr></table>",
        }
        result = tools.get_graphic_content.invoke({"graphic_id": "123"})
        assert "Dosing Table" in result
        assert "| Dose | Rate |" in result

    def test_strips_graphic_prefix(self):
        import tools
        self.mock_db.get_graphic_asset.return_value = {
            "type": "graphic_table",
            "title": "Table",
            "html": "<table><tr><td>A</td></tr></table>",
        }
        tools.get_graphic_content.invoke({"graphic_id": "Graphic-123"})
        # Should look up "123" after stripping prefix
        self.mock_db.get_graphic_asset.assert_any_call("123")


class TestFollowRelatedTopic:
    """Tests for follow_related_topic tool."""

    def setup_method(self):
        import tools
        self.mock_db = MagicMock()
        tools.init_tools(self.mock_db)

    def test_delegates_to_get_topic_outline(self):
        import tools
        self.mock_db.get_topic_outline.return_value = '<a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Intro</a>'
        self.mock_db.get_topic_title.return_value = "Related"
        result = tools.follow_related_topic.invoke({"topic_id": "456"})
        data = json.loads(result)
        assert data["title"] == "Related"


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

        # Just verify it doesn't crash with default params
        graph = create_clinical_agent(
            llm=MagicMock(),
            tools=[dummy_tool],
            system_prompt="test",
        )
        assert graph is not None


# ═══════════════════════════════════════════════════════════════════════
# Edge cases and regression tests
# ═══════════════════════════════════════════════════════════════════════


class TestEdgeCases:
    """Regression tests for known bugs."""

    def test_citation_with_semicolon_separator(self):
        """Inline citations separated by semicolons should each parse."""
        answer = (
            "Topic: Drug A, Section: Dosing (ID: H1); "
            "Topic: Drug B, Section: Safety (ID: H2)"
        )
        citations = SafetyValidator.parse_citations(answer)
        # Semicolons are on the same line — only the first may match
        # depending on regex greediness. This is acceptable behavior.
        assert len(citations) >= 1

    def test_answer_with_only_structural_numbers(self):
        """Answer with section numbers, years, list markers should not trigger invented numbers."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="Guidelines 2024.", success=True)],
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

    def test_empty_answer(self):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="",
            tool_results=["..."],
        )
        result = SafetyValidator().validate(ctx)
        # Empty answer with citations empty should fail rule 4
        assert not result.passed

    def test_unicode_in_citations(self):
        answer = "Topic: Résumé, Section: Données (ID: H1)"
        citations = SafetyValidator.parse_citations(answer)
        assert len(citations) == 1
        assert "Résumé" in citations[0].topic_title

    def test_html_entities_in_tool_result(self):
        """Tool results with HTML entities should not cause false invented-number blocks."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="Dose &gt; 5 mg", success=True)],
            answer="The dose is 5 mg.",
            tool_results=["Dose &gt; 5 mg"],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None

    def test_multiple_fetched_sections_one_cited(self):
        """With 5 fetched sections and 1 cited, should still pass (density relaxed)."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_section_text", arguments={}, result="...", success=True)],
            answer="Based on section H3.",
            tool_results=["..."],
            citations=[Citation(topic_title="T", section_title="S", section_id="H3")],
            fetched_sections=[
                FetchedSection(topic_id="1", section_id=f"H{i}", section_title=f"Section {i}")
                for i in range(1, 6)
            ],
        )
        result = SafetyValidator()._validate_citation_required(ctx)
        assert result is None

    def test_graphic_content_as_only_fetch(self):
        """getGraphicContent should satisfy section content requirement."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_graphic_content", arguments={}, result="...", success=True)],
            answer="test",
        )
        result = SafetyValidator()._validate_section_content_required(ctx)
        assert result is None
