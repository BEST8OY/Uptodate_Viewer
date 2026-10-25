"""End-to-end opaque-box clinical inquiry and safety test suite.

Exercising (against live root UpToDate SQLite databases with deterministic mock
LLMs, no external API dependencies):
1. Full clinical inquiry flows with database verification.
2. In-graph multi-turn self-correction remediation loops.
3. Auto-populated citations and clinical reference grouping.
4. M3 gate remediation regressions (singular section_id, profile wiring,
   graphic prefix normalization, table-only graphics, citation ordering).
5. Premature synthesis interception through the agent graph (merged from the
   former Milestone-1 challenger suite).

Unit-level validator rules (dosage ranges, metric conversions, patient profile
bounds) live in test_clinref.py, and live-database tool contracts live in
test_integration.py.
"""

import json
from pathlib import Path
from typing import Optional

import pytest
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage

from agent import create_clinical_agent, create_initial_state
from clinical_source import (
    group_topic_refs_to_articles,
    graphic_refs_to_tables,
)
from database import ClinRefDatabase
from safety_validator import (
    FetchedSection,
    ToolCallRecord,
    TurnContext,
)
import tools

# Root database path (parent of python_prototype)
DB_DIR = Path(__file__).parent.parent

pytestmark = pytest.mark.skipif(
    not (DB_DIR / "utdasset.sqlite").exists(),
    reason="UpToDate databases not found in project root",
)

SAMPLE_TOPICS = ["1", "18", "20"]


@pytest.fixture(scope="module")
def db():
    """Shared database connection for E2E tests."""
    d = ClinRefDatabase(DB_DIR)
    tools.init_tools(d)
    yield d
    d.close()


class ScriptedSequenceLLM:
    """Deterministic LLM that returns a pre-scripted sequence of responses.

    Supports binding tools and records invocation history for opaque-box assertions.
    """

    def __init__(self, responses: list[BaseMessage]):
        self.responses = list(responses)
        self.call_count = 0
        self.invocations: list[list[BaseMessage]] = []

    def bind_tools(self, tool_list):
        return self

    def invoke(self, messages: list[BaseMessage], config: Optional[dict] = None) -> BaseMessage:
        self.invocations.append(messages)
        if self.call_count < len(self.responses):
            resp = self.responses[self.call_count]
            self.call_count += 1
            return resp
        # Default fallback if sequence exhausted
        return AIMessage(content="Verification complete.")


# ═══════════════════════════════════════════════════════════════════════
# 1. Full Clinical Inquiry Flows
# ═══════════════════════════════════════════════════════════════════════


class TestClinicalInquiryE2E:
    """Test full multi-turn clinical inquiry flows through the agent graph."""

    def test_full_clinical_inquiry_flow_with_database_verification(self, db):
        """Standard clinical inquiry: search -> outline -> batch sections -> verified answer."""
        tool_list = [
            tools.search_topics,
            tools.get_topic_overview,
            tools.get_topic_sections_text,
            tools.get_graphic_content,
        ]

        # Scripted responses simulating intelligent LLM exploration
        resp_turn1 = AIMessage(
            content="",
            tool_calls=[{
                "name": "search_topics",
                "args": {"query": "aspirin"},
                "id": "call_search_1",
            }]
        )
        resp_turn2 = AIMessage(
            content="",
            tool_calls=[{
                "name": "get_topic_overview",
                "args": {"topic_id": "1"},
                "id": "call_outline_1",
            }]
        )
        resp_turn3 = AIMessage(
            content="",
            tool_calls=[{
                "name": "get_topic_sections_text",
                "args": {"topic_id": "1", "section_ids": ["H1"]},
                "id": "call_sec_1",
            }]
        )
        # Synthesized answer using a general clinical claim without invented numbers
        resp_turn4 = AIMessage(
            content="Based on Practice Changing Updates, clinical evidence supports systematic evaluation of practice changes."
        )

        mock_llm = ScriptedSequenceLLM([resp_turn1, resp_turn2, resp_turn3, resp_turn4])
        agent = create_clinical_agent(
            llm=mock_llm,
            tools=tool_list,
            system_prompt="Clinical System Prompt",
            max_safety_retries=2,
        )

        initial_state = {
            "messages": [HumanMessage(content="What are recent practice changing updates?")],
            "system_prompt": "Clinical System Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What are recent practice changing updates?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
        }

        final_state = agent.invoke(initial_state)

        # Invariants:
        # 1. Final message is AIMessage
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert not last_msg.content.startswith("Clinical Response Verification Blocked")

        # 2. Validation passed
        val_result = final_state.get("validation_result")
        assert val_result is not None
        assert val_result["passed"] is True

        # 3. Turn context accumulated sections and topic refs
        tc_dict = final_state.get("turn_context", {})
        assert len(tc_dict.get("fetched_sections", [])) > 0
        assert len(tc_dict.get("structured_topic_refs", [])) > 0

        # 4. Citations auto-populated with topic title
        top_ref = tc_dict["structured_topic_refs"][0]
        assert top_ref["topic_id"] == "1"
        assert len(top_ref["topic_title"]) > 0

    def test_conversational_query_bypasses_tool_requirement(self, db):
        """Conversational greeting requires zero database tool calls."""
        greeting_resp = AIMessage(
            content="Hello! I am ClinRef AI, your clinical evidence assistant. How can I assist you with clinical questions today?"
        )
        mock_llm = ScriptedSequenceLLM([greeting_resp])
        agent = create_clinical_agent(
            llm=mock_llm,
            tools=[],
            system_prompt="Clinical Prompt",
            max_safety_retries=1,
        )

        initial_state = {
            "messages": [HumanMessage(content="Hello doctor!")],
            "system_prompt": "Clinical Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "Hello doctor!",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
        }

        final_state = agent.invoke(initial_state)
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert "Hello!" in last_msg.content

        val_result = final_state["validation_result"]
        assert val_result["passed"] is True
        assert val_result["blocked_reason"] is None

    def test_premature_clinical_synthesis_is_intercepted_and_blocked(self, db):
        """Premature clinical assertion with dosage without tool calls must be blocked (Rule 0)."""
        premature_resp = AIMessage(
            content="The recommended dose is 5 mg twice daily."
        )
        mock_llm = ScriptedSequenceLLM([premature_resp])
        agent = create_clinical_agent(
            llm=mock_llm,
            tools=[],
            system_prompt="Clinical Prompt",
            max_safety_retries=0,  # 0 retries to observe immediate terminal block
        )

        initial_state = {
            "messages": [HumanMessage(content="What is the apixaban dose?")],
            "system_prompt": "Clinical Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What is the apixaban dose?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
        }

        final_state = agent.invoke(initial_state)
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert last_msg.content.startswith("Clinical Response Verification Blocked:")
        assert "database verification" in last_msg.content


# ═══════════════════════════════════════════════════════════════════════
# 2. In-Graph Multi-Turn Self-Correction Remediation Loops
# ═══════════════════════════════════════════════════════════════════════


class TestRemediationLoopE2E:
    """Test in-graph self-correction remediation loops triggered by safety failures."""

    def test_in_graph_self_correction_remediation_loop(self, db):
        """Graph catches invented number on turn 1 -> injects correction prompt -> model rectifies on turn 2."""
        tool_list = [tools.get_topic_sections_text]

        # Turn 1: LLM fetches section
        resp_turn1 = AIMessage(
            content="",
            tool_calls=[{
                "name": "get_topic_sections_text",
                "args": {"topic_id": "1", "section_ids": ["H1"]},
                "id": "call_1",
            }]
        )
        resp_turn1b = AIMessage(content="Evidence retrieved.")
        # Turn 2: LLM produces invalid response with an invented statistic (99.4%)
        resp_turn2 = AIMessage(
            content="Evidence demonstrates a 99.4% efficacy rate across clinical trials."
        )
        # Turn 3: LLM receives correction prompt and self-corrects to verified text without invented numbers
        resp_turn3 = AIMessage(
            content="Practice Changing Updates outline key clinical evidence and practice changes."
        )

        mock_llm = ScriptedSequenceLLM([resp_turn1, resp_turn1b, resp_turn2, resp_turn3])
        agent = create_clinical_agent(
            llm=mock_llm,
            tools=tool_list,
            system_prompt="Clinical System Prompt",
            max_safety_retries=2,
        )

        initial_state = {
            "messages": [HumanMessage(content="What are the updates?")],
            "system_prompt": "Clinical System Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What are the updates?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
        }

        final_state = agent.invoke(initial_state)

        # 1. State machine conducted a remediation retry
        assert final_state["retry_count"] == 1

        # 2. Final message is clean, verified, and not blocked
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert not last_msg.content.startswith("Clinical Response Verification Blocked")

        # 3. Intermediate messages include the SYSTEM NOTICE correction prompt
        messages = final_state["messages"]
        has_correction_msg = any(
            isinstance(m, HumanMessage)
            and "SYSTEM NOTICE" in m.content
            and ("clinical verification rules" in m.content or "NUMERICAL VERIFICATION BLOCK" in m.content)
            for m in messages
        )
        assert has_correction_msg is True

        # 4. Final validation passed
        assert final_state["validation_result"]["passed"] is True

    def test_remediation_retries_exhaustion_emits_terminal_blocked_message(self, db):
        """Graph repeatedly failing safety validation halts at terminal block when retry budget is exhausted."""
        tool_list = [tools.get_topic_sections_text]

        # LLM continually emits invented numbers
        resp_turn1 = AIMessage(
            content="",
            tool_calls=[{
                "name": "get_topic_sections_text",
                "args": {"topic_id": "1", "section_ids": ["H1"]},
                "id": "call_1",
            }]
        )
        resp_turn1b = AIMessage(content="Evidence retrieved.")
        resp_turn2 = AIMessage(content="Efficacy is 99.1%.")
        resp_turn3 = AIMessage(content="Still claiming 99.2% efficacy.")

        mock_llm = ScriptedSequenceLLM([resp_turn1, resp_turn1b, resp_turn2, resp_turn3])
        agent = create_clinical_agent(
            llm=mock_llm,
            tools=tool_list,
            system_prompt="Clinical Prompt",
            max_safety_retries=1,  # Only 1 retry allowed
        )

        initial_state = {
            "messages": [HumanMessage(content="What is the efficacy?")],
            "system_prompt": "Clinical Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "What is the efficacy?",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
        }

        final_state = agent.invoke(initial_state)
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert last_msg.content.startswith("Clinical Response Verification Blocked:")
        assert final_state["validation_result"]["passed"] is False


# ═══════════════════════════════════════════════════════════════════════
# 3. Auto-Populated Citations & Clinical References
# ═══════════════════════════════════════════════════════════════════════


class TestCitationExtractionE2E:
    """Test auto-population of TopicRef, GraphicRef, and grouping into ClinicalArticle/Table."""

    def test_citation_auto_population_and_article_grouping(self):
        """Evidence fetched automatically forms structured TopicRef without manual LLM citation generation."""
        tc = TurnContext(
            fetched_sections=[
                FetchedSection(
                    topic_id="128998",
                    topic_title="Atrial Fibrillation Management",
                    section_id="H1",
                    section_title="Dosing and Administration",
                    content_snippet="Standard dose is 5 mg twice daily.",
                ),
                FetchedSection(
                    topic_id="128998",
                    topic_title="Atrial Fibrillation Management",
                    section_id="H2",
                    section_title="Renal Dose Adjustment",
                    content_snippet="Reduce to 2.5 mg twice daily for eGFR < 30.",
                ),
                FetchedSection(
                    topic_id="999",
                    topic_title="Anticoagulant Reversal",
                    section_id="FULL",
                    section_title="Calculator",
                    content_snippet="Calculator tool.",
                ),
            ],
            graphic_ids={"12345"},
            graphic_titles={"12345": "Table: Anticoagulant Dosing Summary"},
            graphic_to_topic={"12345": "128998"},
            topic_titles={"128998": "Atrial Fibrillation Management", "999": "Anticoagulant Reversal"},
        )

        from agent import _auto_populate_refs
        _auto_populate_refs(tc)

        # 1. Topic refs populated
        assert len(tc.structured_topic_refs) >= 2
        # Section FULL is stripped from citation section ID
        calc_ref = next(r for r in tc.structured_topic_refs if r.topic_id == "999")
        assert calc_ref.section_id == ""

        # 2. Graphic refs populated with parent topic attribution
        assert len(tc.structured_graphic_refs) == 1
        g_ref = tc.structured_graphic_refs[0]
        assert g_ref.graphic_id == "12345"
        assert g_ref.topic_id == "128998"
        assert g_ref.label == "Table: Anticoagulant Dosing Summary"

        # 3. Grouping into ClinicalArticle and ClinicalTable
        articles = group_topic_refs_to_articles(tc.structured_topic_refs)
        assert len(articles) == 2
        af_article = next(a for a in articles if a.topic_id == "128998")
        assert af_article.display_title == "Atrial Fibrillation Management"
        assert len(af_article.sections) == 2

        tables = graphic_refs_to_tables(tc.structured_graphic_refs, tc.topic_titles)
        assert len(tables) == 1
        assert tables[0].parent_topic_title == "Atrial Fibrillation Management"


# ═══════════════════════════════════════════════════════════════════════
# 4. M3 Gate Remediation Regressions (post 5-agent gate certification)
# ═══════════════════════════════════════════════════════════════════════


class TestM3GateRemediation:
    """Anti-regression tests for defects demonstrated by the M3 gate challengers/reviewers.

    Covers: singular section_id citations (V6), patient-profile wiring (B1),
    graphic-ID prefix normalization (V2), overview table-only graphics (V4),
    and deterministic citation ordering (V5).
    """

    def test_singular_section_id_argument_yields_citations(self, db):
        """V6: LLM-issued singular `section_id` must be tracked into citations (was silently dropped)."""
        outline = tools.get_topic_overview.func(topic_id="3852")
        first_sid = next(s.id for s in outline.sections if s.id and s.id != "FULL")

        resp_tool = AIMessage(
            content="",
            tool_calls=[{"name": "get_topic_overview", "args": {"topic_id": "3852"}, "id": "call_outline_1"}],
        )
        resp_tool2 = AIMessage(
            content="",
            tool_calls=[{
                "name": "get_topic_sections_text",
                "args": {"topic_id": "3852", "section_id": first_sid},
                "id": "call_sec_single",
            }],
        )
        resp_final = AIMessage(content="Follow the retrieved dosing section for management.")

        mock_llm = ScriptedSequenceLLM([resp_tool, resp_tool2, resp_final])
        agent = create_clinical_agent(
            llm=mock_llm,
            tools=[tools.get_topic_overview, tools.get_topic_sections_text],
            system_prompt="Clinical System Prompt",
            max_safety_retries=2,
        )
        initial_state = {
            "messages": [HumanMessage(content="Compare dosing alternatives.")],
            "system_prompt": "Clinical System Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "Compare dosing alternatives.",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }
        final_state = agent.invoke(initial_state)
        tc_dict = final_state["turn_context"]
        tracked_ids = [s["section_id"] for s in tc_dict["fetched_sections"]]
        assert first_sid in tracked_ids, (
            f"Singular section_id '{first_sid}' must be tracked, got fetched_sections={tracked_ids}"
        )
        assert len(tc_dict["structured_topic_refs"]) > 0, "Citations must be auto-populated for singular section_id"

    def test_patient_profile_wired_into_initial_state_enforces_rule4(self, db):
        """B1(sym): Penicillin-allergic profile passed into agent state must deterministically block amoxicillin."""
        resp_tool = AIMessage(
            content="",
            tool_calls=[{
                "name": "get_topic_sections_text",
                "args": {"topic_id": "3852", "section_ids": ["H1"]},
                "id": "call_sec_1",
            }],
        )
        resp_final = AIMessage(content="Prescribe amoxicillin for the pharyngitis.")
        mock_llm = ScriptedSequenceLLM([resp_tool, AIMessage(content="Evidence retrieved."), resp_final])
        agent = create_clinical_agent(
            llm=mock_llm,
            tools=[tools.get_topic_sections_text],
            system_prompt="Clinical System Prompt",
            max_safety_retries=0,
        )
        initial_state = create_initial_state(
            "Treat pharyngitis.",
            "Clinical System Prompt",
            patient_profile={
                "age": "24",
                "sex": "male",
                "conditions": ["pharyngitis"],
                "allergies": ["penicillin (anaphylaxis)"],
            },
        )
        final_state = agent.invoke(initial_state)
        val = final_state["validation_result"]
        assert val is not None and val["passed"] is False
        assert "allergy contraindication" in (val["blocked_reason"] or "").lower()
        assert "amoxicillin" in (val["blocked_reason"] or "").lower()

    def test_graphic_id_prefix_variants_resolve(self, db):
        """V2: graphic_ / graphic_table_ prefixes (and Graphic- style) must normalize like Kotlin."""
        ov = tools.get_topic_overview.func(topic_id="3869")
        table_graphic_id = next(g.id for g in ov.graphics if g.isTable)
        for variant in (f"graphic_{table_graphic_id}", f"graphic_table_{table_graphic_id}",
                        f"Graphic-{table_graphic_id}", table_graphic_id, f"graphic-{table_graphic_id}"):
            res = tools.get_graphic_content.func(graphic_id=variant)
            raw = res.markdown if hasattr(res, "markdown") else str(res)
            assert raw.startswith("### Graphic Table:"), f"Variant '{variant}' failed: {raw[:120]}"

    def test_topic_overview_only_returns_table_graphics(self, db):
        """V4: The isTable/is_table key mismatch must not leak figures/algorithms into compound retrieval."""
        ov = tools.get_topic_overview.func(topic_id="3869")
        assert len(ov.graphics) > 0
        for g in ov.graphics:
            assert g.isTable is True, f"Missing isTable key on overview graphic {g.id}"

    def test_graphic_refs_follow_insertion_order_across_processes(self, db):
        """V5: GraphicRef ordering must be deterministic (set-iteration hash-seed bug was demonstrated)."""
        ov = tools.get_topic_overview.func(topic_id="3869")
        ids = [g.id for g in ov.graphics if g.isTable]
        assert len(ids) >= 2, "Fixture requires at least 2 table graphics"
        selected = ids[:3]

        responses = []
        for i, gid in enumerate(selected):
            responses.append(AIMessage(
                content="",
                tool_calls=[{"name": "get_graphic_content", "args": {"graphic_id": gid}, "id": f"call_g_{i}"}],
            ))
        responses.append(AIMessage(content="Follow the retrieved dosing tables for management."))
        mock_llm = ScriptedSequenceLLM(responses)
        agent = create_clinical_agent(
            llm=mock_llm,
            tools=[tools.get_graphic_content],
            system_prompt="Clinical System Prompt",
            max_safety_retries=2,
        )
        initial_state = {
            "messages": [HumanMessage(content="Show the dosing tables.")],
            "system_prompt": "Clinical System Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": "Show the dosing tables.",
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }
        final_state = agent.invoke(initial_state)
        ref_ids = [g["graphic_id"] for g in final_state["turn_context"]["structured_graphic_refs"]]
        assert ref_ids == selected, f"GraphicRef order must match tool-call order: {ref_ids} != {selected}"


# ═══════════════════════════════════════════════════════════════════════
# 5. Premature Synthesis Interception Through the Agent Graph
# ═══════════════════════════════════════════════════════════════════════


class TestPrematureInterceptionGraphE2E:
    """Merged from the former Milestone-1 challenger suite.

    In-graph premature interception behavior: conversational queries must NOT be
    intercepted, clinical claims without tool history must be, the retry budget
    must terminate cleanly, and evidence-carrying responses must route to
    validation. Thought-tag formats themselves are unit-tested against
    reasoning_sanitizer in test_clinref.py (TestReasoningTokenSanitizer).
    """

    def _initial_state(self, user_question: str) -> dict:
        """Graph state with all retry counters explicit (mirrors the production contract)."""
        return {
            "messages": [HumanMessage(content=user_question)],
            "system_prompt": "Test Prompt",
            "turn_context": TurnContext().model_dump(),
            "user_question": user_question,
            "retry_count": 0,
            "validation_result": None,
            "tool_rounds": 0,
            "premature_retries": 0,
        }

    @pytest.mark.parametrize(
        "query,greeting_text",
        [
            ("hello", "Hello! I am ClinRef AI, your clinical evidence assistant."),
            ("hi there", "Hi! How can I help you with medical literature today?"),
            ("who are you", "I am ClinRef AI, an offline clinical evidence decision support assistant."),
            ("help", "I can assist you in searching UpToDate clinical guidelines and drug monographs."),
            ("what can you do", "I can search clinical topics, drug dosages, and evidence tables."),
            ("thanks", "You're welcome! Let me know if you need anything else."),
            ("thank you very much", "Glad to help!"),
            ("good morning", "Good morning! How may I assist you today?"),
        ],
    )
    def test_conversational_queries_bypass_premature_interception(self, query, greeting_text):
        llm = ScriptedSequenceLLM([AIMessage(content=greeting_text)])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        final_state = agent.invoke(self._initial_state(query))

        # 1. premature_retries remains 0 and no interception notice was injected
        assert final_state["premature_retries"] == 0
        interceptions = [
            m for m in final_state["messages"]
            if isinstance(m, HumanMessage) and "PREMATURE RESPONSE INTERCEPTED" in m.content
        ]
        assert len(interceptions) == 0
        # 2. Final message is the greeting and validation passed
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert greeting_text in last_msg.content
        assert final_state["validation_result"]["passed"] is True

    def test_conversational_query_with_clinical_numbers_in_thought_tags(self):
        """Greeting whose thought block contains dosages/percentages must strip thoughts and pass."""
        raw_output = (
            "<thought>\n"
            "The user said 'hello'. I recall trial 10294 evaluated 500 mg vs 250 mg with 85.5% success.\n"
            "However, this is just a friendly greeting, so no clinical claims should be made.\n"
            "</thought>\n"
            "Hello doctor! I am ready to help you navigate clinical guidelines."
        )
        llm = ScriptedSequenceLLM([AIMessage(content=raw_output)])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        final_state = agent.invoke(self._initial_state("Hello doctor!"))

        assert final_state["premature_retries"] == 0
        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert "<thought>" not in last_msg.content
        assert "500 mg" not in last_msg.content
        assert "Hello doctor!" in last_msg.content
        assert final_state["validation_result"]["passed"] is True

    @pytest.mark.parametrize(
        "premature_answer",
        [
            "The recommended dosage of amoxicillin is 500 mg orally every 8 hours for 10 days.",
            "Start apixaban at 5 mg orally twice daily.",
            "Administer ceftriaxone 1 g IV daily.",
            "Expected response rate is approximately 95% with standard therapy.",
        ],
    )
    def test_premature_assertion_routes_to_interception_node(self, premature_answer):
        # Round 1: premature clinical answer -> intercepted; Round 2: safe response
        llm = ScriptedSequenceLLM([
            AIMessage(content=premature_answer),
            AIMessage(content="I need to consult the clinical databases to provide verified dosing guidance."),
        ])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        final_state = agent.invoke(self._initial_state("What is the recommended dose?"))

        # 1. premature_retries incremented to 1
        assert final_state["premature_retries"] == 1
        # 2. Interception notice injected exactly once and carries retrieval directives
        interceptions = [
            m for m in final_state["messages"]
            if isinstance(m, HumanMessage) and "SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED" in m.content
        ]
        assert len(interceptions) == 1
        notice = interceptions[0].content
        assert "MANDATORY CLINICAL DIRECTIVE" in notice
        assert "get_topic_overview" in notice or "getTopicOverview" in notice
        assert "search_topics" in notice or "searchTopics" in notice
        # 3. LLM was invoked twice (initial + after interception)
        assert llm.call_count == 2

    def test_full_two_retry_premature_budget_exhaustion(self):
        """With max_safety_retries=1 and max_premature_retries=2, exactly 2 interceptions then a clean terminal block."""
        llm = ScriptedSequenceLLM([AIMessage(content=f"Turn {i}: Give 500 mg amoxicillin orally twice daily.") for i in range(1, 5)])
        agent = create_clinical_agent(
            llm=llm,
            tools=[],
            system_prompt="Test Prompt",
            max_premature_retries=2,
            max_safety_retries=1,
        )

        final_state = agent.invoke(self._initial_state("What is the amoxicillin dose?"))

        interceptions = [
            m for m in final_state["messages"]
            if isinstance(m, HumanMessage) and "SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED" in m.content
        ]
        assert len(interceptions) == 2
        assert final_state["premature_retries"] == 2

        last_msg = final_state["messages"][-1]
        assert isinstance(last_msg, AIMessage)
        assert last_msg.content.startswith("Clinical Response Verification Blocked:")

    def test_response_with_fetched_evidence_routes_to_validation_not_interception(self):
        """When fetched_sections has evidence, a direct text response routes to validation, never to interception."""
        llm = ScriptedSequenceLLM([
            AIMessage(content="Retrieved evidence ready."),
            AIMessage(content="Based on retrieved guidelines, the dose is 5 mg twice daily."),
        ])
        agent = create_clinical_agent(llm=llm, tools=[], system_prompt="Test Prompt", max_premature_retries=2)

        tc = TurnContext()
        tc.tool_calls.append(
            ToolCallRecord(
                tool_name="get_topic_sections_text",
                arguments={"topic_id": "1"},
                result="...",
                success=True,
            )
        )
        tc.fetched_sections.append(
            FetchedSection(
                topic_id="1",
                topic_title="Apixaban",
                section_id="H1",
                section_title="Dosing",
                content_snippet="Standard dose is 5 mg twice daily.",
            )
        )
        tc.topic_titles["1"] = "Apixaban"
        tc.outline_sections["1"] = {"H1": "Dosing"}

        state = self._initial_state("What is the apixaban dose?")
        state["turn_context"] = tc.model_dump()
        state["tool_rounds"] = 1

        final_state = agent.invoke(state)

        assert final_state["premature_retries"] == 0
        assert final_state["validation_result"]["passed"] is True
        assert "5 mg twice daily" in final_state["messages"][-1].content
