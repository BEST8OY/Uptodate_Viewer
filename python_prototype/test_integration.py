"""Integration tests against real UpToDate database files.

Requires the 7 SQLite databases in the project root directory.
Run with: pytest test_integration.py -v
"""

import json
import re
from pathlib import Path

import pytest

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
    CLINICAL_QUANTITY_REGEX,
    FetchedSection,
    SafetyValidator,
    ToolCallRecord,
    TurnContext,
)

# Database path — parent directory of python_prototype/
DB_DIR = Path(__file__).parent.parent

# Skip all tests if databases not present
pytestmark = pytest.mark.skipif(
    not (DB_DIR / "utdasset.sqlite").exists(),
    reason="UpToDate databases not found in project root",
)


@pytest.fixture(scope="module")
def db():
    """Shared database connection for all integration tests."""
    d = ClinRefDatabase(DB_DIR)
    yield d
    d.close()


# ── Sample topic IDs with known good content ──────────────────────────
SAMPLE_TOPICS = ["1", "18", "20"]  # Practice Updates, Neurology, Oncology


class TestDatabaseAccess:
    """Verify database connections and basic queries."""

    def test_toc_accessible(self, db):
        rows = db.toc.execute("SELECT COUNT(*) AS cnt FROM TOC").fetchone()
        assert rows["cnt"] > 0

    def test_search_accessible(self, db):
        rows = db.search.execute("SELECT COUNT(*) AS cnt FROM Search").fetchone()
        assert rows["cnt"] > 0

    def test_asset_accessible(self, db):
        rows = db.asset.execute("SELECT COUNT(*) AS cnt FROM topic_asset").fetchone()
        assert rows["cnt"] > 0

    def test_get_topic_asset(self, db):
        asset = db.get_topic_asset("1")
        assert asset is not None
        assert "outlineHtml" in asset
        assert "bodyHtml" in asset
        assert len(asset["outlineHtml"]) > 0
        assert len(asset["bodyHtml"]) > 0

    def test_get_topic_title(self, db):
        title = db.get_topic_title("1")
        assert title is not None
        assert len(title) > 0

    def test_search_topics(self, db):
        results = db.search_topics("aspirin", limit=5)
        assert isinstance(results, list)

    def test_get_suggestions(self, db):
        suggestions = db.get_suggestions("aspirin", limit=10)
        assert isinstance(suggestions, list)


class TestOutlineParsing:
    """Test outline parsing against real HTML."""

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS)
    def test_outline_has_sections(self, db, topic_id):
        outline = db.get_topic_outline(topic_id)
        assert outline is not None
        sections = extract_outline_sections(outline)
        assert len(sections) >= 3, f"Topic {topic_id} should have >= 3 sections"

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS)
    def test_sections_have_ids_and_titles(self, db, topic_id):
        outline = db.get_topic_outline(topic_id)
        sections = extract_outline_sections(outline)
        for sec in sections:
            assert "id" in sec and sec["id"], f"Section missing id: {sec}"
            assert "title" in sec and sec["title"], f"Section missing title: {sec}"

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS)
    def test_section_ids_are_unique(self, db, topic_id):
        outline = db.get_topic_outline(topic_id)
        sections = extract_outline_sections(outline)
        ids = [s["id"] for s in sections]
        assert len(ids) == len(set(ids)), f"Duplicate section IDs in topic {topic_id}"


class TestGraphicsParsing:
    """Test graphic metadata extraction against real HTML."""

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS)
    def test_graphics_have_required_fields(self, db, topic_id):
        outline = db.get_topic_outline(topic_id)
        graphics = extract_graphics_from_outline(outline)
        for g in graphics:
            assert "id" in g and g["id"], f"Graphic missing id: {g}"
            assert "type" in g, f"Graphic missing type: {g}"
            assert "is_table" in g, f"Graphic missing is_table: {g}"
            assert "title" in g, f"Graphic missing title: {g}"

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS)
    def test_table_graphics_identified(self, db, topic_id):
        outline = db.get_topic_outline(topic_id)
        graphics = extract_graphics_from_outline(outline)
        tables = [g for g in graphics if g["is_table"]]
        figures = [g for g in graphics if not g["is_table"]]
        # At least verify the classification is consistent
        for t in tables:
            assert t["type"] == "graphic_table"
        for f in figures:
            assert f["type"] != "graphic_table"

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS)
    def test_graphics_deduplicated(self, db, topic_id):
        outline = db.get_topic_outline(topic_id)
        graphics = extract_graphics_from_outline(outline)
        ids = [g["id"] for g in graphics]
        assert len(ids) == len(set(ids)), f"Duplicate graphic IDs in topic {topic_id}"


class TestRelatedTopics:
    """Test related topic extraction."""

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS)
    def test_related_topics_are_valid(self, db, topic_id):
        outline = db.get_topic_outline(topic_id)
        related = extract_related_topics(outline)
        for r in related:
            assert "id" in r and r["id"], f"Related topic missing id: {r}"
            assert "title" in r and r["title"], f"Related topic missing title: {r}"
            # IDs should be numeric
            assert r["id"].isdigit(), f"Non-numeric related topic ID: {r['id']}"


class TestSectionExtraction:
    """Test section HTML extraction from real body HTML."""

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS[:2])  # Limit to avoid slow tests
    def test_each_section_extracts_without_error(self, db, topic_id):
        outline = db.get_topic_outline(topic_id)
        body = db.get_topic_body(topic_id)
        sections = extract_outline_sections(outline)
        errors = []
        for sec in sections:
            try:
                result = extract_section_html(body, outline, sec["id"])
                # Should be None or a non-empty string
                if result is not None and len(result) == 0:
                    errors.append(f"Section {sec['id']} returned empty string")
            except Exception as e:
                errors.append(f"Section {sec['id']} raised {type(e).__name__}: {e}")
        assert not errors, f"Errors extracting sections from topic {topic_id}:\n" + "\n".join(errors)

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS[:2])
    def test_section_html_contains_expected_content(self, db, topic_id):
        """Extracted section HTML should contain the section's own id attribute."""
        outline = db.get_topic_outline(topic_id)
        body = db.get_topic_body(topic_id)
        sections = extract_outline_sections(outline)
        for sec in sections[:5]:
            html = extract_section_html(body, outline, sec["id"])
            if html is not None:
                # The section's start element should have the id
                assert sec["id"] in html, (
                    f"Section {sec['id']} HTML doesn't contain its own id attribute"
                )


class TestMarkdownConversion:
    """Test HTML-to-markdown conversion against real content."""

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS[:2])
    def test_markdown_conversion_no_html_tags(self, db, topic_id):
        """Converted markdown should not contain raw HTML tags."""
        outline = db.get_topic_outline(topic_id)
        body = db.get_topic_body(topic_id)
        sections = extract_outline_sections(outline)
        for sec in sections[:5]:
            html = extract_section_html(body, outline, sec["id"])
            if html:
                md = html_to_markdown(html)
                # Should not contain opening HTML tags (except possibly in code blocks)
                assert "<h" not in md.lower() or md.count("<h") == 0, (
                    f"Markdown for section {sec['id']} still contains HTML heading tags"
                )

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS[:2])
    def test_markdown_has_content_for_nontrivial_sections(self, db, topic_id):
        """Sections with >500 chars of HTML should produce >50 chars of markdown."""
        outline = db.get_topic_outline(topic_id)
        body = db.get_topic_body(topic_id)
        sections = extract_outline_sections(outline)
        short_sections = 0
        for sec in sections:
            html = extract_section_html(body, outline, sec["id"])
            if html and len(html) > 500:
                md = html_to_markdown(html)
                if len(md) < 50:
                    short_sections += 1
        # Allow some short sections (sub-headings with minimal content)
        total_big = sum(
            1 for sec in sections
            if (h := extract_section_html(body, outline, sec["id"])) and len(h) > 500
        )
        if total_big > 0:
            ratio = short_sections / total_big
            assert ratio < 0.3, (
                f"Too many big-HTML sections producing short markdown: "
                f"{short_sections}/{total_big} ({ratio:.0%})"
            )


class TestGraphicTableMarkdown:
    """Test that real graphic table content converts to valid markdown tables."""

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS)
    def test_table_graphics_produce_markdown_tables(self, db, topic_id):
        outline = db.get_topic_outline(topic_id)
        graphics = extract_graphics_from_outline(outline)
        tables = [g for g in graphics if g["is_table"]]

        for g in tables[:2]:  # Test first 2 tables per topic
            asset = db.get_graphic_asset(g["id"])
            if not asset:
                continue
            html = asset.get("html", "") or asset.get("content", "")
            if not html:
                continue

            md = table_to_markdown(html)
            # Should contain pipe characters (markdown table syntax)
            assert "|" in md, f"Table graphic {g['id']} markdown has no pipes"
            # Should contain separator row
            assert "---" in md, f"Table graphic {g['id']} markdown has no separator"


class TestSafetyValidationWithRealData:
    """Test safety validation using realistic scenarios from real data."""

    def test_realistic_successful_turn(self, db):
        """Simulate a complete turn: search → outline → section → answer → validate."""
        # Use topic 1 (Practice Changing Updates)
        sections = extract_outline_sections(db.get_topic_outline("1"))
        first_sec = sections[0]

        # Simulate tool call record
        ctx = TurnContext(
            tool_calls=[
                ToolCallRecord(
                    tool_name="get_topic_sections_text",
                    arguments={"topic_id": "1", "section_ids": [first_sec["id"]]},
                    result="Mock section content for testing",
                    success=True,
                )
            ],
            answer=(
                "Based on the evidence, the recommended approach is discussed in the source."
            ),
            tool_results=["Mock section content for testing"],
            fetched_sections=[
                FetchedSection(
                    topic_id="1",
                    section_id=first_sec["id"],
                    section_title=first_sec["title"],
                )
            ],
        )
        result = SafetyValidator().validate(ctx)
        assert result.passed, f"Validation failed: {result.blocked_reason}"

    def test_invented_number_detection_with_real_content(self, db):
        """Clinical quantities not in tool results should be blocked."""
        ctx = TurnContext(
            tool_calls=[
                ToolCallRecord(
                    tool_name="get_topic_sections_text",
                    arguments={"section_id": "H1"},
                    result="The dose is 500 mg twice daily.",
                    success=True,
                )
            ],
            answer="The recommended dose is 750 mg three times daily.",
            tool_results=["The dose is 500 mg twice daily."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is not None
        assert not result.passed
        assert "750 mg" in result.blocked_reason

    def test_structural_numbers_not_flagged(self, db):
        """Section numbers, years, list markers should not be treated as clinical quantities."""
        ctx = TurnContext(
            tool_calls=[
                ToolCallRecord(
                    tool_name="get_topic_sections_text",
                    arguments={"section_id": "H1"},
                    result="Guidelines from 2024 recommend treatment.",
                    success=True,
                )
            ],
            answer=(
                "1. Introduction\n"
                "2. Methods\n"
                "See section 3.1.\n"
                "The year 2024 guidelines.\n"
                "Class IIa recommendation."
            ),
            tool_results=["Guidelines from 2024 recommend treatment."],
        )
        result = SafetyValidator()._validate_no_invented_numbers(ctx)
        assert result is None, f"Structural numbers incorrectly flagged: {result.blocked_reason}"


class TestFullPipeline:
    """End-to-end pipeline test: database → parse → markdown → validate."""

    @pytest.mark.parametrize("topic_id", SAMPLE_TOPICS[:1])
    def test_full_pipeline(self, db, topic_id):
        """Complete pipeline: load topic → extract sections → convert → validate."""
        asset = db.get_topic_asset(topic_id)
        assert asset is not None

        outline_html = asset["outlineHtml"]
        body_html = asset["bodyHtml"]

        sections = extract_outline_sections(outline_html)
        graphics = extract_graphics_from_outline(outline_html)
        related = extract_related_topics(outline_html)

        assert len(sections) > 0
        assert isinstance(graphics, list)
        assert isinstance(related, list)

        # Convert first 3 sections to markdown
        md_results = []
        for sec in sections[:3]:
            html = extract_section_html(body_html, outline_html, sec["id"])
            if html:
                md = html_to_markdown(html)
                md_results.append((sec["id"], md))

        # All converted markdown should be non-empty
        for sec_id, md in md_results:
            assert len(md) > 0, f"Empty markdown for section {sec_id}"

        # Build a realistic citation from first section
        if md_results:
            first_id, first_md = md_results[0]
            first_title = sections[0]["title"]
            topic_title = db.get_topic_title(topic_id) or topic_id

            answer = (
                f"Based on the clinical evidence:\n\n"
                f"{first_md[:200]}..."
            )

            ctx = TurnContext(
                tool_calls=[
                    ToolCallRecord(
                        tool_name="get_topic_sections_text",
                        arguments={"topic_id": topic_id, "section_ids": [first_id]},
                        result=first_md[:500],
                        success=True,
                    )
                ],
                answer=answer,
                tool_results=[first_md[:500]],
                fetched_sections=[
                    FetchedSection(
                        topic_id=topic_id,
                        section_id=first_id,
                        section_title=first_title,
                    )
                ],
            )

            validation = SafetyValidator().validate(ctx)
            assert validation.passed, (
                f"Full pipeline validation failed: {validation.blocked_reason}"
            )


class TestRealDatabasePureSearchPipeline:
    """Test tools.search_topics and tools.get_topic_outline against real database for 3-stage pipeline."""

    def test_search_topics_returns_pure_candidate_list(self, db):
        import tools
        tools.init_tools(db)

        res_json = tools.search_topics.invoke({"query": "atrial fibrillation"})
        data = json.loads(res_json)
        assert len(data["results"]) > 0, "Expected search results for 'atrial fibrillation'"
        top_match = data["results"][0]
        assert "id" in top_match and "title" in top_match
        assert "outline" not in top_match, "search_topics should return candidate topics without embedded outline"

    @pytest.mark.parametrize("query", ["asthma", "gout", "apixaban", "pneumonia"])
    def test_search_topics_and_get_outline_pipeline(self, db, query):
        import tools
        tools.init_tools(db)

        res_json = tools.search_topics.invoke({"query": query})
        data = json.loads(res_json)
        assert "results" in data
        if data["results"]:
            top_id = data["results"][0]["id"]
            outline_json = tools.get_topic_outline.invoke({"topic_id": top_id})
            outline_data = json.loads(outline_json)
            assert "sections" in outline_data
            assert "graphics" in outline_data


class TestDiverseHTMLHandling:
    """Test section extraction and clean markdown conversion across diverse real topics."""

    DIVERSE_TOPIC_IDS = ["1", "18", "20", "50", "100", "200", "300", "500", "1000", "1500"]

    def test_html_cleaned_across_diverse_topics(self, db):
        clean_failures = []
        for tid in self.DIVERSE_TOPIC_IDS:
            if not db.get_topic_asset(tid):
                continue
            outline = db.get_topic_outline(tid)
            body = db.get_topic_body(tid)
            if not outline or not body:
                continue
            sections = extract_outline_sections(outline)
            for sec in sections[:3]:  # Check first 3 sections per topic
                html = extract_section_html(body, outline, sec["id"])
                if not html:
                    continue
                md = html_to_markdown(html)
                # Check for remaining HTML tags (opening h1-h6, div, span, script, style)
                if re.search(r"<(?:div|span|script|style|h[1-6]|p)\b", md, re.IGNORECASE):
                    clean_failures.append(f"Topic {tid} Section {sec['id']} has raw HTML tags")
                # Check for uncleaned footnote references like [1], [1, 2]
                if re.search(r"\[\d+(?:\s*[-,]\s*\d+)*\]", md):
                    clean_failures.append(f"Topic {tid} Section {sec['id']} has uncleaned footnotes")
        assert not clean_failures, "HTML cleaning issues found:\n" + "\n".join(clean_failures)


class TestCalculatorSupport:
    """Test calculator topic support and full-text fallback."""

    def test_search_topics_returns_calculator(self, db):
        import json
        from tools import init_tools, search_topics
        init_tools(db)
        res_str = search_topics.invoke({"query": "ascvd risk calculator"})
        data = json.loads(res_str)
        assert len(data["results"]) > 0

    def test_get_topic_sections_text_calculator_fallback(self, db):
        import json
        from tools import init_tools, get_topic_sections_text
        init_tools(db)
        res_str = get_topic_sections_text.invoke({"topic_id": "148929", "section_ids": ["FULL"]})
        data = json.loads(res_str)
        assert "topicTitle" in data
        assert "=== Calculator:" in data["markdown"]
        assert "Age" in data["markdown"] or "Inputs" in data["markdown"]
