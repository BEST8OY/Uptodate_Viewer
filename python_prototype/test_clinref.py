"""Comprehensive tests for ClinRef Python prototype.

Covers: evidence_models, html_parser, tools, agent.
Database-dependent tests are skipped if no DB is available.
"""

import json
import logging
import re
from unittest.mock import MagicMock, patch

import pytest

import evidence_models
from evidence_models import (
    FetchedGraphic,
    FetchedSection,
    GraphicRef,
    PatientProfile,
    ToolCallRecord,
    TopicRef,
    TurnContext,
    ValidationResult,
)
from bs4 import BeautifulSoup
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage

from system_prompt import (
    CANONICAL_ABSTENTION,
    RETRIEVAL_SYSTEM_PROMPT,
    SYNTHESIS_SYSTEM_PROMPT,
    build_evidence_gate_pause_prompt,
    build_system_prompt,
    evidence_summary_from,
    extract_patient_context,
    format_synthesis_prompt,
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
# evidence_models.py (1:1 mirror of EvidenceModels.kt)
# ═══════════════════════════════════════════════════════════════════════


class TestEvidenceModels:
    """Verifies TurnContext, FetchedSection, FetchedGraphic, and PatientProfile models."""

    def test_turn_context_has_evidence(self):
        ctx = TurnContext()
        assert ctx.has_evidence is False
        assert ctx.has_fetched_section("1", "H1") is False
        assert ctx.has_fetched_graphic("G1") is False

        ctx.fetched_sections.append(
            FetchedSection(
                topic_id="1",
                section_id="H1",
                topic_title="Topic 1",
                section_title="Sec 1",
                content_snippet="Evidence text",
            )
        )
        assert ctx.has_evidence is True
        assert ctx.has_fetched_section("1", "H1") is True
        assert ctx.has_fetched_graphic("G1") is False

    def test_turn_context_has_fetched_graphic(self):
        ctx = TurnContext()
        ctx.fetched_graphics.append(
            FetchedGraphic(
                graphic_id="G1",
                title="Table 1",
                markdown="| Col | Val |",
            )
        )
        assert ctx.has_evidence is True
        assert ctx.has_fetched_section("1", "H1") is False
        assert ctx.has_fetched_graphic("G1") is True

    def test_patient_profile_to_system_block(self):
        profile = PatientProfile(
            age="72",
            sex="male",
            conditions=["Heart Failure", "CKD"],
            medications=["Furosemide 40 mg"],
            allergies=["Sulfa"],
            notes="eGFR 35 mL/min",
        )
        block = profile.to_system_block()
        assert "- Age: 72" in block
        assert "- Sex: male" in block
        assert "- Conditions: Heart Failure, CKD" in block
        assert "- Medications: Furosemide 40 mg" in block
        assert "- Allergies: Sulfa" in block
        assert "- Notes: eGFR 35 mL/min" in block







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
        from evidence_models import TurnContext, FetchedSection, ToolCallRecord
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
        assert len(tool_list) == 4

    def test_init_tools_includes_expected_names(self):
        import tools
        mock_db = MagicMock()
        tool_list = tools.init_tools(mock_db)
        names = [t.name for t in tool_list]
        assert len(names) == 4
        assert set(names) == {
            "getTopicOverview",
            "searchTopics",
            "getTopicSectionsText",
            "getGraphicContent",
        }
        assert "searchTopicSections" not in names
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

    def test_sections_beyond_twenty_per_call_omitted(self):
        import tools
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "medical"},
            "bodyHtml": "".join(
                f"<h2 id='H{i}'>Sec {i}</h2><p>Content {i}.</p>" for i in range(1, 22)
            ),
            "outlineHtml": "".join(
                '<a href="appAction({&quot;section&quot;:&quot;H%d&quot;})">Sec %d</a>' % (i, i)
                for i in range(1, 22)
            ),
        }
        result = tools.get_topic_sections_text.func(
            topic_id="123", section_ids=[f"H{i}" for i in range(1, 22)]
        )
        # Count guard: at most 20 sections per call; the 21st is reported, never silently dropped
        assert set(result.sectionTitles.keys()) == {f"H{i}" for i in range(1, 21)}
        assert result.omittedSections == ["H21"]
        assert result.truncated is None
        assert "=== Section: H21 ===" not in result.markdown

    def test_get_topic_sections_text_does_not_count_subsumed_stubs_against_cap(self):
        import tools
        self.mock_db.get_topic_title.return_value = "Subsumption Cap Test"
        outline_html = (
            '<ul><li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">P1</a>'
            '<ul>' + "".join(f'<li><a href="appAction({{&quot;section&quot;:&quot;H1_{i}&quot;}})">C{i}</a></li>' for i in range(1, 6)) + '</ul></li>'
            + "".join(f'<li><a href="appAction({{&quot;section&quot;:&quot;H{i}&quot;}})">P{i}</a></li>' for i in range(2, 21)) + '</ul>'
        )
        body_html = (
            '<p id="H1">Parent 1'
            + "".join(f'<p id="H1_{i}">Child 1.{i}</p>' for i in range(1, 6))
            + '</p>'
            + "".join(f'<p id="H{i}">Parent {i}</p>' for i in range(2, 21))
        )
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "medical"},
            "bodyHtml": body_html,
            "outlineHtml": outline_html,
        }
        requested_ids = ["H1"] + [f"H1_{i}" for i in range(1, 6)] + [f"H{i}" for i in range(2, 21)]
        assert len(requested_ids) == 25
        result = tools.get_topic_sections_text.func(
            topic_id="301", section_ids=requested_ids
        )
        assert not result.omittedSections
        assert len(result.sectionTitles) == 25
        assert "=== Section: H1_1 ===\n(Content included in section [H1] above)" in result.markdown
        assert "=== Section: H20 ===" in result.markdown

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

    def test_get_topic_sections_text_avoids_duplicate_text_for_subsumed_sections(self):
        import tools
        self.mock_db.get_topic_title.return_value = "Topic 401"
        self.mock_db.get_topic_asset.return_value = {
            "topicInfo": {"type": "medical"},
            "outlineHtml": """
                <ul id="innerOutline">
                    <li><a href="appAction({&quot;section&quot;:&quot;H1&quot;})">Overview</a></li>
                    <li><a href="appAction({&quot;section&quot;:&quot;H2&quot;})">Treatment</a>
                        <ul>
                            <li><a href="appAction({&quot;section&quot;:&quot;H3&quot;})">Antibiotics</a></li>
                        </ul>
                    </li>
                    <li><a href="appAction({&quot;section&quot;:&quot;H4&quot;})">Prevention</a></li>
                </ul>
            """,
            "bodyHtml": """
                <p class="headingAnchor" id="H1"><span class="h1">Overview</span><span class="headingEndMark">—</span>General overview text.</p>
                <p class="headingAnchor" id="H2"><span class="h2">Treatment</span><span class="headingEndMark">—</span>Infected wounds must be drained.</p>
                <p class="headingAnchor" id="H3"><span class="h3">Antibiotics</span><span class="headingEndMark">—</span>Give Vancomycin 15 to 20 mg/kg IV.</p>
                <p class="headingAnchor" id="H4"><span class="h2">Prevention</span><span class="headingEndMark">—</span>Use sterile technique.</p>
            """,
        }
        # Request both H2 (parent) and H3 (child) in reverse order
        result = tools.get_topic_sections_text.func(
            topic_id="401", section_ids=["H3", "H2"]
        )

        assert "H2" in result.sectionTitles
        assert "H3" in result.sectionTitles
        assert "=== Section: H2 ===" in result.markdown
        assert "Infected wounds must be drained." in result.markdown
        assert "Vancomycin 15 to 20 mg/kg IV" in result.markdown
        assert "=== Section: H3 ===\n(Content included in section [H2] above)" in result.markdown
        assert result.markdown.count("Vancomycin 15 to 20 mg/kg IV") == 1


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
        assert "validate_safety" not in graph.nodes
        assert "safety_block" not in graph.nodes

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


# ═══════════════════════════════════════════════════════════════════════
# Quantity extraction & clinical normalizations (eval_quantities.py)
# ═══════════════════════════════════════════════════════════════════════


class TestQuantityExtraction:
    """Verifies clinical quantity extraction and normalization for CDS benchmark evaluation."""

    def test_structural_numbers_not_extracted_as_quantities(self):
        from eval_quantities import extract_quantities
        text = (
            "1. Introduction\n"
            "2. Methods\n"
            "See section 3.1.\n"
            "The year 2024 guidelines recommend treatment.\n"
            "Class IIa recommendation."
        )
        q = extract_quantities(text)
        assert len(q) == 0

    def test_slashed_and_compound_units(self):
        from eval_quantities import extract_quantities
        text = "Give 5 u/x IV, 10 U/L, and 0.5 mcg/kg/min."
        q = extract_quantities(text)
        assert "0.5 mcg/kg/min" in q

    def test_dose_ranges_and_units_normalization(self):
        from eval_quantities import extract_quantities
        text = "Apixaban 2.5 to 5.0 mg orally twice daily."
        q = extract_quantities(text)
        assert "2.5 mg" in q
        assert "5 mg" in q


class TestAutoPopulateRefs:
    """Tests for _auto_populate_refs — only valid sections with titles should produce refs."""

    def test_skips_sections_with_empty_title(self):
        """Sections whose section_title is empty should be excluded from topic_refs."""
        from agent import _auto_populate_refs
        from evidence_models import TurnContext, FetchedSection, ToolCallRecord

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
        from evidence_models import TurnContext, FetchedSection, ToolCallRecord

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
        from evidence_models import TurnContext, FetchedSection, ToolCallRecord

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
        from evidence_models import TurnContext

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
        from evidence_models import TurnContext

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

    def test_synthesis_emits_canonical_abstention_when_evidence_empty(self):
        from langchain_core.messages import AIMessage, HumanMessage
        from agent import create_clinical_agent
        from evidence_models import TurnContext
        from system_prompt import CANONICAL_ABSTENTION

        class DummyLLM:
            def bind_tools(self, tools):
                return self
            def invoke(self, messages, config=None):
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
        assert last_msg.content == CANONICAL_ABSTENTION

    def test_clinical_source_hierarchy_and_conversions(self):
        from clinical_source import (
            SectionRef,
            ClinicalArticle,
            ClinicalTable,
            group_topic_refs_to_articles,
            graphic_refs_to_tables,
        )
        from evidence_models import TopicRef, GraphicRef

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
        from evidence_models import TurnContext, FetchedSection, ToolCallRecord
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

    def test_strips_intermediate_thought_numbers_from_output(self):
        from reasoning_sanitizer import ReasoningTokenSanitizer

        raw_llm_answer = "<thought>Standard dose is 1000 mg, but patient eGFR is 20 mL/min. Adjust to 250 mg.</thought>Give 250 mg orally."
        clean_answer = ReasoningTokenSanitizer.strip_reasoning(raw_llm_answer)
        assert clean_answer == "Give 250 mg orally."
        assert "1000 mg" not in clean_answer
        assert "<thought>" not in clean_answer

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
# System Prompt & Two-Stage Pipeline Tests
# ═══════════════════════════════════════════════════════════════════════


class TestSystemPromptArchitecture:
    """Verifies two-stage prompt architecture, evidence synthesis formatting, and patient context extraction."""

    def test_synthesis_system_prompt_resolves_canonical_abstention_and_formats_correctly(self):
        prompt = SYNTHESIS_SYSTEM_PROMPT
        assert "You are ClinRef AI, a point-of-care clinical reference for physicians." in prompt
        assert CANONICAL_ABSTENTION in prompt
        assert "### 1. Clinical Rules" in prompt
        assert "### 2. Output Format" in prompt
        assert "Do not perform arithmetic dose calculations" in prompt
        assert "Line 1 MUST start immediately with `**`" in prompt
        assert "Keep a blank line before and after `---`." in prompt
        assert "Use ONLY `###` for subheadings" in prompt

    def test_format_synthesis_prompt_formats_user_prompt_with_evidence_and_inquiry(self):
        sections = [
            FetchedSection(
                topic_id="123",
                section_id="sec-1",
                topic_title="Atrial Fibrillation",
                section_title="Anticoagulation",
                content_snippet="Direct oral anticoagulants are preferred.",
            )
        ]
        tc = TurnContext(
            fetched_sections=sections,
            user_question="What anticoagulation is recommended?",
        )
        formatted = format_synthesis_prompt("What anticoagulation is recommended?", tc)
        assert formatted.startswith("<retrieved_evidence>")
        assert "Section: Anticoagulation [Topic: Atrial Fibrillation]:" in formatted
        assert "Direct oral anticoagulants are preferred." in formatted
        assert "</retrieved_evidence>" in formatted
        assert "<active_patient_profile>" not in formatted
        assert "<clinical_inquiry>\nWhat anticoagulation is recommended?\n</clinical_inquiry>" in formatted

    def test_format_synthesis_prompt_includes_active_patient_profile(self):
        sections = [
            FetchedSection(
                topic_id="123",
                section_id="sec-1",
                topic_title="Atrial Fibrillation",
                section_title="Anticoagulation",
                content_snippet="Apixaban dose is reduced to 2.5 mg twice daily if criteria met.",
            )
        ]
        profile = PatientProfile(
            age="82",
            conditions=["Atrial fibrillation", "CKD stage 3"],
            medications=["Metoprolol"],
        )
        tc = TurnContext(
            fetched_sections=sections,
            user_question="Dose of apixaban?",
            patient_profile=profile,
        )
        formatted = format_synthesis_prompt("Dose of apixaban?", tc)
        assert "<retrieved_evidence>" in formatted
        assert "<active_patient_profile>" in formatted
        assert "- Age: 82" in formatted
        assert "CKD stage 3" in formatted
        assert "</active_patient_profile>" in formatted
        assert "<clinical_inquiry>\nDose of apixaban?\n</clinical_inquiry>" in formatted

    def test_evidence_summary_suppresses_subsumed_pointer_stubs(self):
        sections = [
            FetchedSection(
                topic_id="7027",
                section_id="H8",
                topic_title="CAP",
                section_title="Initial Empiric Therapy",
                content_snippet="Empiric regimen includes ceftriaxone plus azithromycin.",
            ),
            FetchedSection(
                topic_id="7027",
                section_id="H1595678546",
                topic_title="CAP",
                section_title="Indications",
                content_snippet="(Content included in section [H8] above)",
            ),
        ]
        summary = evidence_summary_from(sections)
        assert "Section: Initial Empiric Therapy [Topic: CAP]:" in summary
        assert "Empiric regimen includes ceftriaxone plus azithromycin." in summary
        assert "Section: Indications" not in summary
        assert "(Content included in section [H8] above)" not in summary

    def test_evidence_summary_orders_sections_and_graphics_by_relevance_score(self):
        sec_low = FetchedSection(
            topic_id="1",
            section_id="S_LOW",
            topic_title="Cardiology",
            section_title="General Background",
            content_snippet="Background epidemiology information.",
            relevance_score=1.2,
        )
        sec_high = FetchedSection(
            topic_id="1",
            section_id="S_HIGH",
            topic_title="Cardiology",
            section_title="Dosing and Administration",
            content_snippet="Primary first-line dosing is 5 mg daily.",
            relevance_score=2.9,
        )
        gfx_low = FetchedGraphic(
            graphic_id="G_LOW",
            title="Secondary monitoring table",
            markdown="| Param | Value |\n| --- | --- |\n| Lab | Normal |",
            relevance_score=1.5,
        )
        gfx_high = FetchedGraphic(
            graphic_id="G_HIGH",
            title="Primary Dosing Table",
            markdown="| Drug | Dose |\n| --- | --- |\n| DrugA | 100 mg |",
            relevance_score=2.8,
        )
        summary = evidence_summary_from([sec_low, sec_high], [gfx_low, gfx_high])
        pos_high_sec = summary.index("Dosing and Administration")
        pos_low_sec = summary.index("General Background")
        assert pos_high_sec < pos_low_sec

        pos_high_gfx = summary.index("Primary Dosing Table")
        pos_low_gfx = summary.index("Secondary monitoring table")
        assert pos_high_gfx < pos_low_gfx

    def test_evidence_summary_strips_redundant_graphic_table_header(self):
        gfx = FetchedGraphic(
            graphic_id="118967",
            title="Empiric Antibiotic Regimens",
            markdown="### Graphic Table: Empiric Antibiotic Regimens\n\n| Antibiotic | Dose |\n|---|---|\n| Amoxicillin | 1 g TID |",
            topic_id="7027",
            topic_title="CAP",
            relevance_score=2.8,
        )
        summary = evidence_summary_from([], [gfx])
        assert "Table: Empiric Antibiotic Regimens [Topic: CAP]:" in summary
        assert "### Graphic Table:" not in summary
        assert "| Amoxicillin | 1 g TID |" in summary

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

    def test_evidence_gate_pause_prompt_exact_shape(self):
        prompt = build_evidence_gate_pause_prompt("")
        assert "SYSTEM NOTICE — EVIDENCE GATE PAUSE:" in prompt
        assert "Nothing has been retrieved yet." in prompt
        assert "MANDATORY NEXT STEP (tool calls only, no text):" in prompt
        assert "getTopicSectionsText(" in prompt
        assert "getGraphicContent(" in prompt
        assert "searchTopics(" in prompt
        assert "getTopicOverview(" in prompt


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

        labelled = "**Clinical conclusion:** start symptomatic therapy.\n- Analgesics."
        assert eval_prompt._is_bluf(labelled) is True

    def test_preamble_and_missing_bluf_are_flagged(self):
        import eval_prompt

        answer = "Based on the retrieved evidence, **start symptomatic therapy.**\n**Preferred**\n- Analgesics."
        row = eval_prompt.score_turn(self._case("cold-adult-management"), answer, True, 1)
        assert row["checks"]["bluf"] is False
        assert row["checks"]["no_preamble"] is False

    def test_header_extraction_is_informational_only(self):
        import eval_prompt

        answer = "**Clinical conclusion:** start here.\n### **Common Etiologies**\n- malignancy\n## **Cautions**\n- none"
        assert eval_prompt.extract_headers(answer) == ["Clinical conclusion", "Common Etiologies", "Cautions"]


class TestWhatsNewInjection:
    """Verify What's New and Practice Changing Update injection and section extraction."""

    def test_inject_whats_new_html(self):
        from database import inject_whats_new_html

        body_html = '<div id="topicWhatsNewContainer"></div><p id="H1">Intro</p>'
        wn_info = [
            {
                "contentId": "152105",
                "pcu": True,
                "title": "Aspirin for VTE",
                "html": '<p class="headingAnchor"><span class="h2">Aspirin for VTE</span></p><div class="collapseContent"><p>Aspirin alone (Grade 2B).</p></div>',
            },
            {
                "contentId": "152038",
                "pcu": False,
                "title": "STITCH trial",
                "html": '<p class="headingAnchor"><span class="h2">STITCH trial</span></p><div class="collapseContent"><p>Small bites closure.</p></div>',
            },
        ]
        injected = inject_whats_new_html(body_html, wn_info)
        assert '<div id="PCU0" class="utdCallout utdPCU">' in injected
        assert "PRACTICE CHANGING UPDATE" in injected
        assert "Aspirin alone (Grade 2B)." in injected
        assert '<div id="WhatsNew0" class="utdCallout utdWhatsNew">' in injected
        assert "WHAT'S NEW" in injected
        assert "Small bites closure." in injected

    def test_extract_section_html_pcu_and_whats_new(self):
        from database import inject_whats_new_html
        from html_parser import extract_section_html, html_to_markdown

        outline_html = (
            '<ul id="innerOutline">'
            '<li id="pcuOutlineItem"><a href=\'javascript:appAction({"meta":{"assetType":"topic"},"data":[{"id":"500","section":"PCU0"}]});\'><span>PRACTICE CHANGING UPDATE</span></a></li>'
            '<li id="whatsNewOutlineItem"><a href=\'javascript:appAction({"meta":{"assetType":"topic"},"data":[{"id":"500","section":"WhatsNew0"}]});\'><span>WHAT\'S NEW</span></a></li>'
            '<li><a href=\'javascript:appAction({"meta":{"assetType":"topic"},"data":[{"id":"500","section":"H1"}]});\'><span>Intro</span></a></li>'
            '</ul>'
        )
        body_html = '<div id="topicWhatsNewContainer"></div><div id="topicText"><h2 id="H1">Intro</h2><p>Intro text</p></div>'
        wn_info = [
            {
                "contentId": "152105",
                "pcu": True,
                "title": "Aspirin for VTE",
                "html": '<p class="headingAnchor"><span class="h2">Aspirin for VTE</span></p><div class="collapseContent"><p>Aspirin alone (Grade 2B).</p></div>',
            },
            {
                "contentId": "152038",
                "pcu": False,
                "title": "STITCH trial",
                "html": '<p class="headingAnchor"><span class="h2">STITCH trial</span></p><div class="collapseContent"><p>Small bites closure.</p></div>',
            },
        ]
        injected = inject_whats_new_html(body_html, wn_info)

        pcu_html = extract_section_html(injected, outline_html, "PCU0")
        assert pcu_html is not None
        pcu_md = html_to_markdown(pcu_html)
        assert "Aspirin alone (Grade 2B)." in pcu_md
        assert "Small bites closure." not in pcu_md

        wn_html = extract_section_html(injected, outline_html, "WhatsNew0")
        assert wn_html is not None
        wn_md = html_to_markdown(wn_html)
        assert "Small bites closure." in wn_md
        assert "Intro text" not in wn_md

