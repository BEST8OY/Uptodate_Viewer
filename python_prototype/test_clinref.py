"""Pytest suite for ClinRef Python prototype.

Tests database access, HTML parsing, safety validation, and tool behavior.
"""

import json
import sys
from pathlib import Path

import pytest

# Add parent to path for imports
sys.path.insert(0, str(Path(__file__).parent))

from database import ClinRefDatabase
from html_parser import (
    extract_graphics_from_outline,
    extract_outline_sections,
    extract_related_topics,
    extract_section_html,
    html_to_markdown,
    table_to_markdown,
)
from safety_validator import (
    Citation,
    FetchedSection,
    SafetyValidator,
    ToolCallRecord,
    TurnContext,
    ValidationResult,
)
from system_prompt import build_system_prompt


# ── Fixtures ─────────────────────────────────────────────────────────

@pytest.fixture(scope="module")
def db():
    db_dir = Path(__file__).parent.parent
    database = ClinRefDatabase(db_dir=db_dir)
    yield database
    database.close()


@pytest.fixture
def validator():
    return SafetyValidator()


# ── Database Tests ────────────────────────────────────────────────────

class TestDatabase:
    def test_search_topics(self, db):
        results = db.search_topics("atrial fibrillation")
        assert len(results) > 0
        assert all("id" in r and "title" in r for r in results)
        assert all(r["url"].startswith("Topic-") for r in results)

    def test_search_content(self, db):
        results = db.search_content("anticoagulation")
        assert len(results) > 0
        assert all("snippet" in r for r in results)

    def test_search_no_results(self, db):
        results = db.search_topics("xyzzy_nonexistent_medical_term_12345")
        assert len(results) == 0

    def test_topic_asset(self, db):
        results = db.search_topics("apixaban")
        if results:
            # Find a result that has a title (unidex may return topics without assets)
            for r in results:
                if r["title"]:
                    topic_id = r["id"]
                    asset = db.get_topic_asset(topic_id)
                    if asset:
                        assert "topicInfo" in asset
                        assert "bodyHtml" in asset
                        return
            # If no results have assets, that's OK for this test
            pytest.skip("No results with assets found")

    def test_topic_title(self, db):
        results = db.search_topics("apixaban")
        if results:
            # Find a result that has a title
            for r in results:
                if r["title"]:
                    title = db.get_topic_title(r["id"])
                    assert title is not None
                    assert len(title) > 0
                    return
            # If no results have titles, that's OK
            pytest.skip("No results with titles found")

    def test_topic_outline(self, db):
        results = db.search_topics("hypertension treatment")
        if results:
            topic_id = results[0]["id"]
            outline = db.get_topic_outline(topic_id)
            if outline:
                sections = extract_outline_sections(outline)
                # Some topics (calculators) may have empty outlines
                assert isinstance(sections, list)
            else:
                # No outline — acceptable for some topic types
                pass

    def test_toc_mapping(self, db):
        results = db.search_topics("hypertension")
        if results:
            topic_id = results[0]["id"]
            toc = db.get_toc_for_topic(topic_id)
            # TOC mapping may or may not exist for every topic
            assert isinstance(toc, list)


# ── HTML Parser Tests ─────────────────────────────────────────────────

class TestHTMLParser:
    def test_extract_outline_sections(self):
        html = '<a href="appAction(\'topic?section=SEC123\')">Section Title</a>'
        sections = extract_outline_sections(html)
        assert len(sections) == 1
        assert sections[0]["id"] == "SEC123"
        assert sections[0]["title"] == "Section Title"

    def test_extract_outline_sections_multiple(self):
        html = """
        <a href="appAction('topic?section=SEC1')">First</a>
        <a href="appAction('topic?section=SEC2')">Second</a>
        <a href="appAction('topic?section=SEC3')">Third</a>
        """
        sections = extract_outline_sections(html)
        assert len(sections) == 3
        assert [s["id"] for s in sections] == ["SEC1", "SEC2", "SEC3"]

    def test_extract_related_topics(self):
        html = """
        <a href="appAction('medical/Topic-12345')">Related Topic</a>
        <a href="appAction('topic?section=SEC1')">Not a topic</a>
        <a href="appAction('graphic/Graphic-999')">Not a topic</a>
        """
        topics = extract_related_topics(html)
        assert len(topics) == 1
        assert topics[0]["id"] == "12345"

    def test_extract_graphics(self):
        html = '<a href="appAction(\'graphic/Graphic-ABC123\')">Table Title</a>'
        graphics = extract_graphics_from_outline(html)
        assert len(graphics) == 1
        assert graphics[0]["id"] == "ABC123"

    def test_html_to_markdown_basic(self):
        html = "<h2>Title</h2><p>Some <strong>bold</strong> text.</p>"
        md = html_to_markdown(html)
        assert "Title" in md
        assert "bold" in md

    def test_html_to_markdown_table(self):
        html = """
        <table>
            <tr><th>Name</th><th>Dose</th></tr>
            <tr><td>Warfarin</td><td>5mg</td></tr>
        </table>
        """
        md = table_to_markdown(html)
        assert "| Name | Dose |" in md
        assert "| Warfarin | 5mg |" in md
        assert "---" in md

    def test_html_to_markdown_empty(self):
        assert html_to_markdown("") == ""
        assert html_to_markdown(None) == ""

    def test_extract_section_html(self):
        body = """
        <h1 id="SEC1">Section 1</h1><p>Content 1</p>
        <h1 id="SEC2">Section 2</h1><p>Content 2</p>
        <h1 id="SEC3">Section 3</h1><p>Content 3</p>
        """
        outline = """
        <a href="?section=SEC1">Section 1</a>
        <a href="?section=SEC2">Section 2</a>
        <a href="?section=SEC3">Section 3</a>
        """
        html = extract_section_html(body, outline, "SEC2")
        assert html is not None
        assert "Content 2" in html

    def test_extract_section_html_not_found(self):
        html = extract_section_html("<p>No sections</p>", "", "NONEXISTENT")
        assert html is None


# ── Safety Validator Tests ────────────────────────────────────────────

class TestSafetyValidator:
    def test_parse_citations(self, validator):
        answer = (
            "Warfarin is effective for AFib.\n\n"
            "Topic: Atrial fibrillation, Section: Anticoagulation (ID: 12345)\n"
            "Topic: Warfarin, Section: Dosing (ID: 67890)\n"
        )
        citations = validator.parse_citations(answer)
        assert len(citations) == 2
        assert citations[0].topic_title == "Atrial fibrillation"
        assert citations[0].section_id == "12345"
        assert citations[1].section_title == "Dosing"

    def test_parse_citations_no_id(self, validator):
        answer = "Topic: Test, Section: Section (ID: )\n"
        citations = validator.parse_citations(answer)
        # Empty ID should still parse
        assert len(citations) == 1

    def test_parse_citations_none(self, validator):
        citations = validator.parse_citations("No citations here.")
        assert len(citations) == 0

    def test_no_tool_calls_block(self, validator):
        ctx = TurnContext()
        result = validator.validate(ctx)
        assert not result.passed
        assert "No tool calls" in result.blocked_reason

    def test_no_section_content_block(self, validator):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="search_topics",
                arguments={"query": "test"},
                result="[]",
                success=True,
            )]
        )
        result = validator.validate(ctx)
        assert not result.passed
        assert "section content" in result.blocked_reason.lower()

    def test_no_citations_block(self, validator):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="Some content",
                success=True,
            )]
        )
        result = validator.validate(ctx)
        assert not result.passed
        assert "citations" in result.blocked_reason.lower()

    def test_citation_consistency_block(self, validator):
        # Title-match fallback: section_id mismatch but title matches -> warning, not block
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="Content",
                success=True,
            )],
            answer="Answer.\n\nTopic: Test, Section: Test (ID: FAKE_ID)\n",
            citations=[Citation(topic_title="Test", section_title="Test", section_id="FAKE_ID")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
        )
        result = validator.validate(ctx)
        # Title matches -> warning generated, but validation passes
        assert result.passed
        assert len(result.warnings) > 0

    def test_citation_consistency_hard_block(self, validator):
        # No title match AND no id match -> hard block
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Real Section"},
                result="Content",
                success=True,
            )],
            answer="Answer.\n\nTopic: Test, Section: Fake Section (ID: FAKE_ID)\n",
            citations=[Citation(topic_title="Test", section_title="Fake Section", section_id="FAKE_ID")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Real Section")],
        )
        result = validator.validate(ctx)
        assert not result.passed
        assert "unretrieved" in result.blocked_reason.lower()

    def test_invented_numbers_block(self, validator):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="The dose is 5mg twice daily.",
                success=True,
            )],
            answer="The recommended dose is 500mg daily.\n\nTopic: Test, Section: Test (ID: S1)\n",
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["The dose is 5mg twice daily."],
        )
        result = validator.validate(ctx)
        assert not result.passed
        assert "500mg" in result.blocked_reason

    def test_valid_answer_passes(self, validator):
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="The dose is 5mg twice daily.",
                success=True,
            )],
            answer="The dose is 5mg twice daily.\n\nTopic: Test, Section: Test (ID: S1)\n",
            citations=[Citation(topic_title="Test", section_title="Test", section_id="S1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["The dose is 5mg twice daily."],
        )
        result = validator.validate(ctx)
        assert result.passed

    def test_graphic_interpretation_warns_when_no_graphics(self, validator):
        """Visual language + no graphics fetched -> warn (advisory)."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="Content",
                success=True,
            )],
            answer="The image shows a fracture.\n\nTopic: Test, Section: Test (ID: ABC)\n",
            citations=[Citation(topic_title="Test", section_title="Test", section_id="ABC")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["Content"],
        )
        result = validator.validate(ctx)
        assert result.passed
        assert len(result.warnings) > 0

    def test_graphic_interpretation_blocks_when_graphic_tool_called(self, validator):
        """Visual language + graphic tool called -> block."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="getGraphicInfo",
                arguments={"graphic_id": "G12345"},
                result="ECG findings",
                success=True,
            ),
                ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="Content",
                success=True,
            )],
            answer="The ECG shows ST elevation.\n\nTopic: Test, Section: Test (ID: ABC)\n",
            citations=[Citation(topic_title="Test", section_title="Test", section_id="ABC")],
            graphic_ids={"G12345"},
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["ECG findings."],
        )
        result = validator.validate(ctx)
        assert not result.passed

    def test_graphic_interpretation_blocks_when_graphic_refs_in_answer(self, validator):
        """Graphic refs in answer + visual language -> block."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="Content",
                success=True,
            )],
            answer="The image shows a mass. See also Graphic-ABC.\n\nTopic: Test, Section: Test (ID: ABC)\n",
            citations=[Citation(topic_title="Test", section_title="Test", section_id="ABC")],
            graphic_ids={"G999"},
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["Content"],
        )
        result = validator.validate(ctx)
        assert not result.passed

    def test_markdown_list_numbers_not_invented(self, validator):
        """List markers like '1.', '2.' should not be treated as clinical data."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="The dose is 500mg twice daily for 7 days.",
                success=True,
            )],
            answer=(
                "1. The dose is **500mg** twice daily for **7 days**.\n"
                "2. Monitor kidney function.\n"
                "3. Continue treatment.\n\n"
                "Topic: Test, Section: Test (ID: S1)\n"
            ),
            citations=[Citation(topic_title="Test", section_title="Test", section_id="S1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["The dose is 500mg twice daily for 7 days."],
        )
        result = validator.validate(ctx)
        assert result.passed

    def test_bold_numbers_not_invented(self, validator):
        """Bold-wrapped numbers like **500mg** should match 500mg in tool results."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="The dose is 500mg twice daily.",
                success=True,
            )],
            answer="The dose is **500mg** twice daily.\n\nTopic: Test, Section: Test (ID: S1)\n",
            citations=[Citation(topic_title="Test", section_title="Test", section_id="S1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["The dose is 500mg twice daily."],
        )
        result = validator.validate(ctx)
        assert result.passed

    def test_boundary_aware_number_matching(self, validator):
        """'12' should NOT match as part of '123' in tool results."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="Reference range is 123 units.",
                success=True,
            )],
            answer="The value is 12 units.\n\nTopic: Test, Section: Test (ID: S1)\n",
            citations=[Citation(topic_title="Test", section_title="Test", section_id="S1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["Reference range is 123 units."],
        )
        result = validator.validate(ctx)
        assert not result.passed
        assert "12 units" in result.blocked_reason

    def test_trailing_whitespace_in_numbers(self, validator):
        """Numbers with trailing whitespace from markdown formatting should match."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="SpO2 should be above 92%. Give 3 doses of 2.5mg.",
                success=True,
            )],
            answer="Maintain SpO2 >92%. Give 3 doses of 2.5mg.\n\nTopic: Test, Section: Test (ID: S1)\n",
            citations=[Citation(topic_title="Test", section_title="Test", section_id="S1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["SpO2 should be above 92%. Give 3 doses of 2.5mg."],
        )
        result = validator.validate(ctx)
        assert result.passed

    def test_number_with_space_before_unit(self, validator):
        """Numbers with space before unit should match after normalization."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Test"},
                result="Give 5 mg twice daily. Max 20 mg.",
                success=True,
            )],
            answer="The dose is 5 mg twice daily. Maximum is 20 mg.\n\nTopic: Test, Section: Test (ID: S1)\n",
            citations=[Citation(topic_title="Test", section_title="Test", section_id="S1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Test")],
            tool_results=["Give 5 mg twice daily. Max 20 mg."],
        )
        result = validator.validate(ctx)
        assert result.passed

    def test_citation_density_blocks_uncited_sections(self, validator):
        """When 3 sections are fetched but only 1 is cited, should block."""
        ctx = TurnContext(
            tool_calls=[
                ToolCallRecord(tool_name="get_topic_section_text", arguments={"topic_id": "1", "section_id": "S1", "section_title": "Section A"}, result="Content A", success=True),
                ToolCallRecord(tool_name="get_topic_section_text", arguments={"topic_id": "1", "section_id": "S2", "section_title": "Section B"}, result="Content B", success=True),
                ToolCallRecord(tool_name="get_topic_section_text", arguments={"topic_id": "1", "section_id": "S3", "section_title": "Section C"}, result="Content C", success=True),
            ],
            answer="Summary from Section A.\n\nTopic: Test, Section: Section A (ID: S1)\n",
            citations=[Citation(topic_title="Test", section_title="Section A", section_id="S1")],
            fetched_sections=[
                FetchedSection(topic_id="1", section_id="S1", section_title="Section A"),
                FetchedSection(topic_id="1", section_id="S2", section_title="Section B"),
                FetchedSection(topic_id="1", section_id="S3", section_title="Section C"),
            ],
            tool_results=["Content A", "Content B", "Content C"],
        )
        result = validator.validate(ctx)
        assert not result.passed
        assert "not cited" in result.blocked_reason.lower()

    def test_citation_density_allows_one_uncited(self, validator):
        """1 uncited section out of 3 is allowed (supporting context)."""
        ctx = TurnContext(
            tool_calls=[
                ToolCallRecord(tool_name="get_topic_section_text", arguments={"topic_id": "1", "section_id": "S1", "section_title": "Section A"}, result="Content A", success=True),
                ToolCallRecord(tool_name="get_topic_section_text", arguments={"topic_id": "1", "section_id": "S2", "section_title": "Section B"}, result="Content B", success=True),
                ToolCallRecord(tool_name="get_topic_section_text", arguments={"topic_id": "1", "section_id": "S3", "section_title": "Section C"}, result="Content C", success=True),
            ],
            answer="Summary from Section A and B.\n\nTopic: Test, Section: Section A (ID: S1)\nTopic: Test, Section: Section B (ID: S2)\n",
            citations=[
                Citation(topic_title="Test", section_title="Section A", section_id="S1"),
                Citation(topic_title="Test", section_title="Section B", section_id="S2"),
            ],
            fetched_sections=[
                FetchedSection(topic_id="1", section_id="S1", section_title="Section A"),
                FetchedSection(topic_id="1", section_id="S2", section_title="Section B"),
                FetchedSection(topic_id="1", section_id="S3", section_title="Section C"),
            ],
            tool_results=["Content A", "Content B", "Content C"],
        )
        result = validator.validate(ctx)
        assert result.passed

    def test_patient_numbers_from_question_not_invented(self, validator):
        """Patient-specific values from the user's question should not be flagged."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Treatment"},
                result="Reduce dose for renal impairment.",
                success=True,
            )],
            answer="The patient is 62 years old with eGFR of 35 mL/min. Reduce dose.\n\nTopic: Gout, Section: Treatment (ID: S1)\n",
            citations=[Citation(topic_title="Gout", section_title="Treatment", section_id="S1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Treatment")],
            tool_results=["Reduce dose for renal impairment."],
            user_question="A 62-year-old man with eGFR of 35 mL/min presents with gout.",
        )
        result = validator.validate(ctx)
        assert result.passed

    def test_invented_numbers_still_blocked_with_question(self, validator):
        """Numbers NOT in question or tool results should still be blocked."""
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(
                tool_name="get_topic_section_text",
                arguments={"topic_id": "1", "section_id": "S1", "section_title": "Treatment"},
                result="Reduce dose for renal impairment.",
                success=True,
            )],
            answer="The dose is 500mg. Patient is 62 years old.\n\nTopic: Gout, Section: Treatment (ID: S1)\n",
            citations=[Citation(topic_title="Gout", section_title="Treatment", section_id="S1")],
            fetched_sections=[FetchedSection(topic_id="1", section_id="S1", section_title="Treatment")],
            tool_results=["Reduce dose for renal impairment."],
            user_question="A 62-year-old man with eGFR of 35 mL/min presents with gout.",
        )
        result = validator.validate(ctx)
        assert not result.passed
        assert "500mg" in result.blocked_reason


# ── System Prompt Tests ───────────────────────────────────────────────

class TestSystemPrompt:
    def test_build_default(self):
        prompt = build_system_prompt()
        assert "ClinRef AI" in prompt
        assert "CITATION FORMAT" in prompt
        assert "Topic:" in prompt

    def test_build_with_patient(self):
        prompt = build_system_prompt(patient_context="Age: 65, Male, AFib")
        assert "Age: 65" in prompt
        assert "ClinRef AI" in prompt

    def test_build_ollama(self):
        prompt = build_system_prompt(provider="ollama")
        assert "searchTopics" in prompt
        assert "getTopicOutline" in prompt

    def test_build_full_provider(self):
        prompt = build_system_prompt(provider="openai")
        assert "WORKFLOW" in prompt
        assert "searchTopics" in prompt
