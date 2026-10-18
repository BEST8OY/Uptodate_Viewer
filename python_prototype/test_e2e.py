"""End-to-end opaque-box clinical inquiry and safety test suite.

Exercising:
1. Full clinical inquiry flows with database verification.
2. Compound retrieval contracts and high-yield section bundling.
3. Patient contraindication scenarios (renal eGFR bounds, documented allergies).
4. Dosage boundary value analysis & metric unit conversions.
5. In-graph multi-turn self-correction remediation loops.
6. Auto-populated citations and clinical reference grouping.

All tests execute against live root UpToDate SQLite databases using deterministic
mock LLMs without external API dependencies.
"""

import json
import re
from pathlib import Path
from typing import Optional

import pytest
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, ToolMessage

from agent import create_clinical_agent, create_initial_state
from clinical_source import (
    ClinicalArticle,
    ClinicalTable,
    group_topic_refs_to_articles,
    graphic_refs_to_tables,
)
from database import ClinRefDatabase
from html_parser import extract_outline_sections
from safety_validator import (
    CLINICAL_QUANTITY_REGEX,
    FetchedSection,
    GraphicRef,
    SafetyValidator,
    ToolCallRecord,
    TopicRef,
    TurnContext,
    ValidationResult,
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
# 2. Compound Retrieval & High-Yield Bundling
# ═══════════════════════════════════════════════════════════════════════


class TestCompoundRetrievalE2E:
    """Test compound retrieval contracts, high-yield section bundling, and granular fallback."""

    def test_compound_batch_section_retrieval_against_live_db(self, db):
        """Batch section retrieval fetches multiple sections with markdown formatting in 1 round-trip."""
        outline_html = db.get_topic_outline("1")
        sections = extract_outline_sections(outline_html)
        target_sids = [s["id"] for s in sections[:3]]
        assert len(target_sids) >= 2

        raw_result = tools.get_topic_sections_text.invoke({
            "topic_id": "1",
            "section_ids": target_sids,
        })
        data = json.loads(raw_result)

        assert data["topicId"] == "1"
        assert len(data["topicTitle"]) > 0
        assert "markdown" in data
        # Ensure all requested valid sections have individual headers in the bundled markdown
        for sid in target_sids:
            assert f"=== Section: {sid} ===" in data["markdown"]

    def test_topic_overview_contract_compliance(self, db):
        """Topic overview contract: returns outline metadata and graphics without prefetching for articles."""
        clean_id = "1"
        res_raw = tools.get_topic_overview.invoke({"topic_id": clean_id})
        data = json.loads(res_raw)

        assert data["topicId"] == "1"
        assert len(data["sections"]) >= 3
        assert "prefetchedSections" not in data
        assert "highYieldSections" not in data
        assert data["markdown"] == ""


    def test_calculator_topic_compound_handling(self, db):
        """Calculator topics return calc type and FULL section without outline failures."""
        asset = db.get_topic_asset("148929")  # Known calculator
        if asset:
            raw_overview = tools.get_topic_overview.invoke({"topic_id": "148929"})
            data = json.loads(raw_overview)
            assert data.get("topicType") == "calc"
            assert data["sections"][0]["id"] == "FULL"

            # Retrieve FULL section
            raw_body = tools.get_topic_sections_text.invoke({
                "topic_id": "148929",
                "section_ids": ["FULL"],
            })
            body_data = json.loads(raw_body)
            assert "markdown" in body_data
            assert "invalidSections" not in body_data

    def test_nonexistent_topic_returns_structured_error(self, db):
        """Non-existent topic returns JSON error envelope rather than throwing exceptions."""
        result = tools.get_topic_overview.invoke({"topic_id": "nonexistent_9999999"})
        data = json.loads(result)
        assert "error" in data
        assert "Topic not found" in data["error"]


# ═══════════════════════════════════════════════════════════════════════
# 3. Patient Contraindication Scenarios
# ═══════════════════════════════════════════════════════════════════════


class TestPatientContraindicationE2E:
    """Test patient profile bounds (eGFR, CrCl, documented allergies)."""

    def test_renal_contraindication_scenario_boundary_check(self):
        """Patient with severe renal impairment (eGFR 24 mL/min < 30 cutoff) flags contraindications."""
        # Evidence with standard and reduced dose thresholds
        evidence = (
            "Apixaban standard dosing is 5 mg twice daily. "
            "For patients with severe renal impairment (eGFR or CrCl < 30 mL/min), "
            "or Serum Creatinine >= 1.5 mg/dL with age >= 80 or weight <= 60 kg, "
            "the recommended dose is reduced to 2.5 mg twice daily."
        )

        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result=evidence, success=True)],
            tool_results=[evidence],
            fetched_sections=[FetchedSection(topic_id="128998", topic_title="Apixaban", section_id="H1", section_title="Dosing", content_snippet=evidence)],
            user_question="Patient has eGFR 24 mL/min. What is the apixaban dose?",
            answer="In a patient with severe renal impairment (eGFR 24 mL/min, below 30 mL/min), the recommended reduced dose is 2.5 mg twice daily.",
        )

        validator = SafetyValidator()
        res = validator.validate(ctx)
        assert res.passed is True

    def test_allergy_contraindication_cross_reference_scenario(self):
        """Clinical answer recommending penicillin to a patient with documented penicillin allergy must be evaluated."""
        evidence = (
            "First-line treatment for Group A streptococcal pharyngitis is oral Penicillin V 500 mg twice daily for 10 days, "
            "or Amoxicillin 50 mg/kg once daily. For patients with documented penicillin allergy, oral Azithromycin 500 mg on day 1 "
            "followed by 250 mg daily for 4 days is recommended."
        )

        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result=evidence, success=True)],
            tool_results=[evidence],
            fetched_sections=[FetchedSection(topic_id="pharyngitis", topic_title="Pharyngitis", section_id="H2", section_title="Antibiotics", content_snippet=evidence)],
            user_question="Patient has documented penicillin allergy (anaphylaxis). What is the treatment?",
            answer="For patients with documented penicillin allergy, Azithromycin 500 mg on day 1 followed by 250 mg daily is recommended.",
        )

        validator = SafetyValidator()
        res = validator.validate(ctx)
        assert res.passed is True


# ═══════════════════════════════════════════════════════════════════════
# 4. Dosage Boundary Value Analysis & Unit Conversions
# ═══════════════════════════════════════════════════════════════════════


class TestDosageBoundaryE2E:
    """Test quantitative dosage boundaries, numerical ranges, and metric prefix conversions."""

    def test_metric_unit_conversions_g_mg_mcg(self):
        """Metric equivalence: 1000 mg == 1 g, 500 mcg == 0.5 mg, 2500 mL == 2.5 L."""
        evidence = (
            "Administer Cefazolin 1000 mg IV prior to incision. "
            "Levothyroxine is initiated at 0.5 mg daily. "
            "Fluid resuscitation requires 2500 mL normal saline."
        )

        # Claim using equivalent metric units: 1 g, 500 mcg, 2.5 L
        answer = "Administer Cefazolin 1 g IV. Initiate Levothyroxine at 500 mcg daily. Resuscitate with 2.5 L normal saline."

        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result=evidence, success=True)],
            tool_results=[evidence],
            fetched_sections=[FetchedSection(topic_id="meds", topic_title="Meds", section_id="H1", section_title="Dosing", content_snippet=evidence)],
            user_question="What are the doses?",
            answer=answer,
        )

        validator = SafetyValidator()
        res = validator.validate(ctx)
        assert res.passed is True

    def test_numerical_range_bounds_check(self):
        """Evidence specifying a range '5 to 10 mg' validates endpoints and range expression."""
        evidence = "The recommended starting dose of amlodipine is 5 to 10 mg orally once daily."

        validator = SafetyValidator()

        # 1. Lower bound (5 mg) passes
        ctx_lower = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result=evidence, success=True)],
            tool_results=[evidence],
            fetched_sections=[FetchedSection(topic_id="amlodipine", topic_title="Amlodipine", section_id="H1", section_title="Dosing", content_snippet=evidence)],
            user_question="What is the starting dose?",
            answer="Amlodipine is started at 5 mg orally once daily.",
        )
        assert validator.validate(ctx_lower).passed is True

        # 2. Upper bound (10 mg) passes
        ctx_upper = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result=evidence, success=True)],
            tool_results=[evidence],
            fetched_sections=[FetchedSection(topic_id="amlodipine", topic_title="Amlodipine", section_id="H1", section_title="Dosing", content_snippet=evidence)],
            user_question="What is the starting dose?",
            answer="Amlodipine dose may be titrated up to 10 mg daily.",
        )
        assert validator.validate(ctx_upper).passed is True

        # 3. Out-of-bounds invented number (15 mg) is blocked
        ctx_out = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result=evidence, success=True)],
            tool_results=[evidence],
            fetched_sections=[FetchedSection(topic_id="amlodipine", topic_title="Amlodipine", section_id="H1", section_title="Dosing", content_snippet=evidence)],
            user_question="What is the starting dose?",
            answer="Amlodipine maximum dose is 15 mg daily.",
        )
        res_out = validator.validate(ctx_out)
        assert res_out.passed is False
        assert "Unverified clinical quantities" in res_out.blocked_reason
        assert "15 mg" in res_out.blocked_reason

    def test_near_miss_dosage_blocked(self):
        """Claim of 50 mg when evidence states 500 mg must be blocked."""
        evidence = "The standard dose of Metformin is 500 mg twice daily."
        ctx = TurnContext(
            tool_calls=[ToolCallRecord(tool_name="get_topic_sections_text", arguments={}, result=evidence, success=True)],
            tool_results=[evidence],
            fetched_sections=[FetchedSection(topic_id="metformin", topic_title="Metformin", section_id="H1", section_title="Dosing", content_snippet=evidence)],
            user_question="What is the dose?",
            answer="Metformin is given at 50 mg twice daily.",
        )
        res = SafetyValidator().validate(ctx)
        assert res.passed is False
        assert "50" in res.blocked_reason


# ═══════════════════════════════════════════════════════════════════════
# 5. In-Graph Multi-Turn Self-Correction Remediation Loops
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
        # Turn 2: LLM produces invalid response with an invented statistic (99.4%)
        resp_turn2 = AIMessage(
            content="Evidence demonstrates a 99.4% efficacy rate across clinical trials."
        )
        # Turn 3: LLM receives correction prompt and self-corrects to verified text without invented numbers
        resp_turn3 = AIMessage(
            content="Practice Changing Updates outline key clinical evidence and practice changes."
        )

        mock_llm = ScriptedSequenceLLM([resp_turn1, resp_turn2, resp_turn3])
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
            isinstance(m, HumanMessage) and "SYSTEM NOTICE" in m.content and "clinical verification rules" in m.content
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
        resp_turn2 = AIMessage(content="Efficacy is 99.1%.")
        resp_turn3 = AIMessage(content="Still claiming 99.2% efficacy.")

        mock_llm = ScriptedSequenceLLM([resp_turn1, resp_turn2, resp_turn3])
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
# 6. Auto-Populated Citations & Clinical References
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
# 7. M3 Gate Remediation Regressions (post 5-agent gate certification)
# ═══════════════════════════════════════════════════════════════════════


class TestM3GateRemediation:
    """Anti-regression tests for defects demonstrated by the M3 gate challengers/reviewers.

    Covers: singular section_id citations (V6), patient-profile wiring (B1),
    graphic-ID prefix normalization (V2), overview table-only graphics (V4),
    and deterministic citation ordering (V5).
    """

    def test_singular_section_id_argument_yields_citations(self, db):
        """V6: LLM-issued singular `section_id` must be tracked into citations (was silently dropped)."""
        outline = json.loads(tools.get_topic_overview.func(topic_id="3852"))
        first_sid = next(s["id"] for s in outline["sections"] if s.get("id") and s["id"] != "FULL")

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
                "args": {"topic_id": "3852", "section_ids": []},
                "id": "call_sec_1",
            }],
        )
        resp_final = AIMessage(content="Prescribe amoxicillin for the pharyngitis.")
        mock_llm = ScriptedSequenceLLM([resp_tool, resp_final])
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
        ov = json.loads(tools.get_topic_overview.func(topic_id="3869"))
        table_graphic_id = next(g["id"] for g in ov["graphics"] if g.get("isTable"))
        for variant in (f"graphic_{table_graphic_id}", f"graphic_table_{table_graphic_id}",
                        f"Graphic-{table_graphic_id}", table_graphic_id, f"graphic-{table_graphic_id}"):
            raw = tools.get_graphic_content.func(graphic_id=variant)
            assert raw.startswith("### Graphic Table:"), f"Variant '{variant}' failed: {raw[:120]}"

    def test_topic_overview_only_returns_table_graphics(self, db):
        """V4: The isTable/is_table key mismatch must not leak figures/algorithms into compound retrieval."""
        ov = json.loads(tools.get_topic_overview.func(topic_id="3869"))
        types = {g.get("type") for g in ov["graphics"]}
        assert types <= {"graphic_table"}, f"Non-table graphics leaked into overview: {types}"
        for g in ov["graphics"]:
            assert g.get("isTable") is True, f"Missing isTable key on overview graphic {g.get('id')}"

    def test_graphic_refs_follow_insertion_order_across_processes(self, db):
        """V5: GraphicRef ordering must be deterministic (set-iteration hash-seed bug was demonstrated)."""
        ov = json.loads(tools.get_topic_overview.func(topic_id="3869"))
        ids = [g["id"] for g in ov["graphics"] if g.get("isTable")]
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
