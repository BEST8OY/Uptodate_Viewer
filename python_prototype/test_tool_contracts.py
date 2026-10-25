"""Empirical Challenger Test Suite for Milestone 1 Iteration 2.

Tests:
1. 6-tool preservation (tool count, naming, signatures).
2. camelCase & snake_case aliases across all 6 tools via both `tool.invoke()` and direct call `tool.func(...)`:
   - search_topics: query, search_query, query_text, queryText, searchQuery
   - get_topic_overview: topic_id/topicId
   - get_related_topics: topic_id, topicId
   - get_topic_sections_text: topic_id/topicId and section_ids/sectionIds (as list and single string)
   - search_topic_sections: topic_id/topicId and query/queryText/search_query/searchQuery
   - get_graphic_content: graphic_id, graphicId (with and without 'graphic-' prefix)
3. Smart section resolution cascades:
   - Exact ID match
   - Case-insensitive ID match
   - Exact title match
   - Stripped leading bullet/dash/en-dash/em-dash title match
   - Fallback behavior for non-existent sections
   - Deduplication and ordering
4. Adversarial stress & boundary inputs:
   - None values, empty strings, whitespace
   - Conflicting / co-occurring parameters
   - Extra unexpected arguments (**kwargs)
"""

from pathlib import Path
import pytest

from database import ClinRefDatabase
import tools

DB_DIR = Path(__file__).parent.parent


def to_model(cls, val):
    if isinstance(val, cls):
        return val
    if isinstance(val, str):
        return cls.model_validate_json(val)
    if isinstance(val, dict):
        return cls.model_validate(val)
    return cls.model_validate(val)


@pytest.fixture(scope="module")
def db():
    d = ClinRefDatabase(DB_DIR)
    yield d
    d.close()


@pytest.fixture(autouse=True)
def setup_tools(db):
    tools.init_tools(db)


class TestSixToolPreservation:
    """Empirical verification that all 6 tools exist, are properly registered, and mirror Kotlin."""

    def test_tool_count_and_identity(self, db):
        tool_list = tools.init_tools(db)
        assert len(tool_list) == 6, f"Expected exactly 6 tools, found {len(tool_list)}"

        expected_names = {
            "get_topic_overview",
            "search_topics",
            "search_topic_sections",
            "get_related_topics",
            "get_topic_sections_text",
            "get_graphic_content",
        }
        actual_names = {t.name for t in tool_list}
        assert "get_topic_outline" not in actual_names
        assert actual_names == expected_names, f"Tool name mismatch: {actual_names} vs {expected_names}"


class TestCamelCaseAndSnakeCaseAliases:
    """Verify that all 6 tools accept both snake_case and camelCase parameters via invoke() and direct call."""

    # 1. search_topics: search_query and queryText
    @pytest.mark.parametrize("payload", [
        {"query": "asthma"},
        {"search_query": "asthma"},
        {"q": "asthma"},
        {"keyword": "asthma"},
        {"query_text": "asthma"},
        {"queryText": "asthma"},
        {"searchQuery": "asthma"},
        {"search_query": "asthma", "queryText": "asthma"},
    ])
    def test_search_topics_parameter_aliases_invoke(self, payload):
        raw = tools.search_topics.invoke(payload)
        data = to_model(tools.SearchTopicsResponse, raw)
        assert len(data.results) > 0, f"Expected results for 'asthma' with payload {payload}"
        assert data.query == "asthma"

    def test_search_topics_direct_func_call(self):
        res1 = tools.search_topics.func(search_query="gout")
        res2 = tools.search_topics.func(queryText="gout")
        assert len(res1.results) > 0
        assert len(res2.results) > 0
        assert res1.results[0].id == res2.results[0].id

    # 2. get_topic_overview: topic_id and topicId (consolidated duplicates)
    @pytest.mark.parametrize("payload", [
        {"topic_id": "1"},
        {"topicId": "1"},
        {"topic_id": "1", "topicId": "1"},
    ])
    def test_get_topic_overview_parameter_aliases_invoke(self, payload):
        raw = tools.get_topic_overview.invoke(payload)
        data = to_model(tools.TopicOverviewResponse, raw)
        assert data.error is None, f"Unexpected error in get_topic_overview: {data}"
        assert data.topicId == "1"
        assert len(data.sections) > 0
        assert data.topicType == "article"

    # 3. get_related_topics: topic_id and topicId
    @pytest.mark.parametrize("payload", [
        {"topic_id": "1"},
        {"topicId": "1"},
        {"topic_id": "1", "topicId": "1"},
    ])
    def test_get_related_topics_parameter_aliases_invoke(self, payload):
        raw = tools.get_related_topics.invoke(payload)
        data = to_model(tools.RelatedTopicsResponse, raw)
        assert data.error is None, f"Unexpected error in get_related_topics: {data}"
        assert data.topicId == "1"
        assert hasattr(data, "relatedTopics")

    # 4. get_topic_sections_text: topic_id/topicId and section_ids/sectionIds
    @pytest.mark.parametrize("payload", [
        {"topic_id": "1", "section_ids": ["H1"]},
        {"topicId": "1", "sectionIds": ["H1"]},
        {"topic_id": "1", "sectionIds": ["H1"]},
        {"topicId": "1", "section_ids": ["H1"]},
        {"topicId": "1", "sectionIds": "H1"},  # single string support
        {"topic_id": "1", "section_ids": "H1"},
    ])
    def test_get_topic_sections_text_parameter_aliases_invoke(self, payload):
        raw = tools.get_topic_sections_text.invoke(payload)
        data = to_model(tools.TopicSectionsResponse, raw)
        assert data.error is None, f"Unexpected error in get_topic_sections_text: {data}"
        assert data.topicId == "1"
        assert "H1" in data.sectionTitles
        assert len(data.markdown) > 0

    # 5. search_topic_sections: topic_id/topicId and query/queryText/search_query/searchQuery (+ q/keyword)
    @pytest.mark.parametrize("payload", [
        {"topic_id": "1", "query": "dose"},
        {"topicId": "1", "queryText": "dose"},
        {"topic_id": "1", "search_query": "dose"},
        {"topicId": "1", "searchQuery": "dose"},
        {"topicId": "1", "query_text": "dose"},
        {"topic_id": "1", "q": "dose"},
        {"topic_id": "1", "keyword": "dose"},
    ])
    def test_search_topic_sections_parameter_aliases_invoke(self, payload):
        raw = tools.search_topic_sections.invoke(payload)
        data = to_model(tools.SearchTopicSectionsResponse, raw)
        assert data.error is None, f"Unexpected error in search_topic_sections: {data}"
        assert data.topicId == "1"
        assert len(data.matches) > 0

    # 6. get_graphic_content: graphic_id and graphicId
    @pytest.mark.parametrize("payload", [
        {"graphic_id": "116392"},
        {"graphicId": "116392"},
        {"graphic_id": "graphic-116392"},
        {"graphicId": "graphic-116392"},
        {"graphic_id": "Graphic-116392"},
        {"graphicId": "Graphic-116392"},
    ])
    def test_get_graphic_content_parameter_aliases_invoke(self, payload):
        raw = tools.get_graphic_content.invoke(payload)
        data = to_model(tools.GraphicContentResponse, raw)
        # 116392 is a table graphic in UpToDate
        assert "### Graphic Table:" in (data.markdown or "")
        assert data.error is None

    # Per-tool snake/camel alias equivalence through the raw LangChain function.
    # Replaces five near-identical per-tool `_direct_func_call` test bodies.
    @pytest.mark.parametrize(
        "tool_name, snake_args, camel_args, keys",
        [
            ("search_topics", {"search_query": "gout"}, {"queryText": "gout"}, ["results"]),
            ("get_topic_overview", {"topic_id": "1"}, {"topicId": "1"}, ["title", "sections"]),
            ("get_related_topics", {"topic_id": "1"}, {"topicId": "1"}, ["relatedTopics"]),
            ("get_topic_sections_text",
             {"topic_id": "1", "section_ids": ["H1"]},
             {"topicId": "1", "sectionIds": ["H1"]},
             ["sectionTitles", "markdown"]),
            ("search_topic_sections",
             {"topic_id": "1", "query": "dose"},
             {"topicId": "1", "queryText": "dose"},
             ["matches"]),
        ],
    )
    def test_tool_alias_equivalence_direct_func_call(self, tool_name, snake_args, camel_args, keys):
        tool = getattr(tools, tool_name)
        snake_res = tool.func(**snake_args)
        camel_res = tool.func(**camel_args)
        for key in keys:
            assert getattr(snake_res, key) == getattr(camel_res, key), \
                f"{tool_name}: snake_case {key} != camelCase {key}"

    def test_get_graphic_content_direct_func_call(self):
        res1 = tools.get_graphic_content.func(graphic_id="116392")
        res2 = tools.get_graphic_content.func(graphicId="116392")
        assert res1 == res2


class TestSmartSectionResolutionCascades:
    """Empirical verification of smart section ID resolution cascades."""

    @pytest.fixture
    def id_to_title_sample(self):
        return {
            "H_SR": "Summary and Recommendations",
            "H_DOSE": "Dosing & Administration",
            "H_ROMAN": "IV. Special Populations",
            "H_MGMT": "Management of Toxicity",
            "H_BULLET": "Clinical Presentation and Diagnosis",
        }

    def test_cascade_exact_id_match(self, id_to_title_sample):
        valid, invalid = tools.resolve_section_ids(["H_SR", "H_DOSE"], id_to_title_sample)
        assert valid == ["H_SR", "H_DOSE"]
        assert invalid == []

    def test_cascade_case_insensitive_id_match(self, id_to_title_sample):
        valid, invalid = tools.resolve_section_ids(["h_sr", "h_dose", "H_roman"], id_to_title_sample)
        assert valid == ["H_SR", "H_DOSE", "H_ROMAN"]
        assert invalid == []

    def test_cascade_exact_title_match(self, id_to_title_sample):
        valid, invalid = tools.resolve_section_ids(["Summary and Recommendations", "IV. Special Populations"], id_to_title_sample)
        assert valid == ["H_SR", "H_ROMAN"]
        assert invalid == []

    def test_cascade_stripped_bullet_and_dashes_title_match(self, id_to_title_sample):
        # Leading dashes, bullets, en-dash, em-dash
        requests = [
            "- Management of Toxicity",
            "– Management of Toxicity",
            "— Management of Toxicity",
            "• Clinical Presentation and Diagnosis",
            "● Clinical Presentation and Diagnosis",
        ]
        valid, invalid = tools.resolve_section_ids(requests, id_to_title_sample)
        assert valid == ["H_MGMT", "H_BULLET"]
        assert invalid == []

    def test_cascade_mixed_valid_and_invalid(self, id_to_title_sample):
        requests = ["H_SR", "UNKNOWN_SECTION_XYZ", "– Management of Toxicity", "FAKE_TITLE_123"]
        valid, invalid = tools.resolve_section_ids(requests, id_to_title_sample)
        assert valid == ["H_SR", "H_MGMT"]
        assert invalid == ["UNKNOWN_SECTION_XYZ", "FAKE_TITLE_123"]

    def test_cascade_in_live_tools(self):
        # Test in get_topic_sections_text against topic 1
        raw_sections = tools.get_topic_sections_text.invoke({
            "topicId": "1",
            "sectionIds": ["h1", "SUMMARY AND RECOMMENDATIONS", "- INTRODUCTION"]
        })
        data = to_model(tools.TopicSectionsResponse, raw_sections)
        assert data.error is None
        assert len(data.sectionTitles) > 0

        # Test in get_topic_overview against topic 1
        raw_overview = tools.get_topic_overview.invoke({
            "topicId": "1"
        })
        ov_data = to_model(tools.TopicOverviewResponse, raw_overview)
        assert ov_data.error is None
        assert len(ov_data.sections) > 0


class TestAdversarialEdgeCases:
    """Stress tests on unexpected kwargs, None values, and malformed structures."""

    def test_extra_unexpected_kwargs_tolerated(self):
        # LangChain or agents often inject extra keys like 'run_manager', 'callbacks', etc.
        raw = tools.search_topics.invoke({
            "queryText": "asthma",
            "extra_unexpected_key": "some_value",
            "another_param": 12345
        })
        data = to_model(tools.SearchTopicsResponse, raw)
        assert len(data.results) > 0

        raw_overview = tools.get_topic_overview.invoke({
            "topicId": "1",
            "unknown_metadata": {"foo": "bar"}
        })
        assert to_model(tools.TopicOverviewResponse, raw_overview).error is None

    def test_none_values_handling(self):
        # When model passes null/None
        raw = tools.get_topic_overview.invoke({
            "topic_id": "1",
            "topicId": None,
        })
        data = to_model(tools.TopicOverviewResponse, raw)
        assert data.error is None
        assert data.topicId == "1"

    def test_empty_string_topic_id(self):
        raw = tools.get_topic_overview.invoke({"topicId": ""})
        data = to_model(tools.TopicOverviewResponse, raw)
        assert data.error is not None
        assert "Topic not found" in data.error
