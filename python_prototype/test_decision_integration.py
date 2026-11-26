"""Comprehensive tests for Gemini Decisions API (`gemini-decision-1`) integration.

Uses deterministic mock `sdk_client` responses alongside live SQLite DB tools
and full LangGraph workflows — no live Google API key or network access needed.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from langchain_core.messages import AIMessage

sys.path.insert(0, str(Path(__file__).parent))

from agent import create_clinical_agent, create_initial_state
from database import ClinRefDatabase
from decision_client import (
    DecisionClient,
    choice_question,
    make_text_blocks,
    noul_question,
    sanitize_question_key,
    score_question,
)
from decision_gates import (
    DecisionConfig,
    inject_rerank_into_tool_json,
    rank_overview_items,
    rank_search_candidates,
)
import run as run_module
from router import CANNED_SMALL_TALK_REPLY
from system_prompt import build_system_prompt
from token_tracker import TokenTracker
from tools import init_tools


DB_DIR = Path(__file__).parent.parent


class FakeDecisionsEndpoint:
    """Scriptable mock matching `google.genai.Client.decisions.create(...)`."""

    def __init__(self, handler):
        self.handler = handler
        self.calls: list[dict[str, Any]] = []

    def create(self, *, model: str, input: Any, questions: dict[str, Any], **kwargs):
        call_record = {
            "model": model,
            "input": input,
            "questions": dict(questions),
        }
        self.calls.append(call_record)
        return self.handler(call_record)


class FakeSdkClient:
    def __init__(self, handler):
        self.decisions = FakeDecisionsEndpoint(handler)


def _noul(prob: float, conf: float = 0.92) -> dict[str, Any]:
    return {"type": "noul", "noul": prob, "confidence": conf}


def _score(score: float, conf: float = 0.9, max_level: int = 3) -> dict[str, Any]:
    top = str(min(max_level, max(0, round(score))))
    probs = {str(i): (0.7 if str(i) == top else 0.1) for i in range(max_level + 1)}
    legend = {str(i): f"Level {i}" for i in range(max_level + 1)}
    return {
        "type": "score",
        "score": score,
        "probabilities": probs,
        "legend": legend,
        "confidence": conf,
    }


def _choice(choice_key: str, probs: dict[str, float], conf: float = 0.91) -> dict[str, Any]:
    return {
        "type": "choice",
        "choice": choice_key,
        "probabilities": probs,
        "confidence": conf,
    }


def _resp(answers: dict[str, Any], input_tokens: int = 320):
    return SimpleNamespace(
        answers=answers,
        usage=SimpleNamespace(
            total_input_tokens=input_tokens,
            total_output_tokens=0,
            total_tokens=input_tokens,
        ),
    )


class ScriptedChatLLM:
    """Minimal scripted chat model supporting `.bind_tools()` and `.invoke()`."""

    def __init__(self, script: list[AIMessage]):
        self._script = list(script)
        self.invocations: list[list[Any]] = []
        self.bound_tools: list[Any] = []

    def bind_tools(self, tools):
        self.bound_tools = list(tools)
        return self

    def invoke(self, messages, config=None):
        self.invocations.append(list(messages))
        if not self._script:
            raise AssertionError("ScriptedChatLLM ran out of scripted responses")
        return self._script.pop(0)


# ── 1. DecisionClient Unit Tests ─────────────────────────────────────────────


class TestDecisionClient:
    def test_sanitize_question_key_rules(self):
        assert sanitize_question_key("sec_0_H 1/2:dose") == "sec_0_H_1_2_dose"
        assert sanitize_question_key("---abc") == "q_---abc"
        assert sanitize_question_key("", prefix="sec", index=4) == "sec_4"
        long_key = sanitize_question_key("x" * 120)
        assert len(long_key) == 64

    def test_make_text_blocks_delimiters(self):
        blocks = make_text_blocks(["Header", "", "Body line\n", "Tail"])
        assert blocks == [
            {"type": "text", "text": "Header\n\n"},
            {"type": "text", "text": "Body line\n"},
            {"type": "text", "text": "Tail"},
        ]

    def test_chunking_over_1000_questions_and_token_tracker(self):
        tracker = TokenTracker()

        def handler(call):
            qs = call["questions"]
            ans = {k: _noul(0.85, 0.9) for k in qs}
            return _resp(ans, input_tokens=100 + len(qs))

        sdk = FakeSdkClient(handler)
        client = DecisionClient(sdk_client=sdk, on_usage=tracker.on_decision_end)

        questions = {f"q_{i}": noul_question(f"Question {i}?") for i in range(1250)}
        batch = client.decide(make_text_blocks(["Evidence block"]), questions)

        assert batch.ok is True
        assert len(sdk.decisions.calls) == 2
        assert len(sdk.decisions.calls[0]["questions"]) == 1000
        assert len(sdk.decisions.calls[1]["questions"]) == 250
        assert len(batch.answers) == 1250
        assert batch.usage.total_output_tokens == 0
        assert batch.usage.total_input_tokens == (100 + 1000) + (100 + 250)

        summary = tracker.get_summary()
        assert summary["decision_calls"] == 2
        assert summary["decision_input_tokens"] == 1450

    def test_choice_and_score_helpers(self):
        def handler(call):
            return _resp(
                {
                    "decision": _choice("b", {"a": 0.1, "b": 0.8, "c": 0.1}),
                    "grade": _score(2.4, conf=0.88, max_level=3),
                    "ok_flag": _noul(0.95, 0.99),
                }
            )

        client = DecisionClient(sdk_client=FakeSdkClient(handler))
        batch = client.decide(
            "Evidence text",
            {
                "decision": choice_question("Pick one", {"a": "A", "b": "B", "c": "C"}),
                "grade": score_question("Rate quality", ["Bad", "Fair", "Good", "Great"]),
                "ok_flag": noul_question("Pass?"),
            },
        )
        assert batch.get_choice("decision").choice == "b"
        sc = batch.get_score("grade")
        assert sc is not None
        assert abs(sc.normalized_score() - (2.4 / 3.0)) < 1e-6
        assert batch.get_noul("ok_flag").is_true(0.9)

    def test_fail_open_and_fail_closed(self):
        def failing_handler(call):
            raise RuntimeError("Decisions endpoint unavailable")

        open_client = DecisionClient(sdk_client=FakeSdkClient(failing_handler), fail_open=True)
        res = open_client.decide("doc", {"q1": noul_question("Check?")})
        assert res.ok is False
        assert "unavailable" in (res.error or "")
        assert open_client.failed_calls == 1

        closed_client = DecisionClient(sdk_client=FakeSdkClient(failing_handler), fail_open=False)
        with pytest.raises(RuntimeError, match="unavailable"):
            closed_client.decide("doc", {"q1": noul_question("Check?")})


# ── 2. Phase 1 Retrieval Acceleration Tests ──────────────────────────────────


class TestRetrievalAcceleration:
    def test_rank_search_candidates_prioritizes_dosing_and_topics(self):
        def handler(call):
            answers = {
                "requires_drug_monograph": _noul(0.94, 0.96),
                "requires_table_data": _noul(0.81, 0.88),
            }
            for k, q in call["questions"].items():
                if k.startswith("topic_"):
                    answers[k] = _score(2.9 if "apixaban" in q["instructions"].lower() else 1.1)
            return _resp(answers)

        client = DecisionClient(sdk_client=FakeSdkClient(handler))
        rerank = rank_search_candidates(
            client,
            "What is the apixaban dose for atrial fibrillation with CrCl 30?",
            [
                {"id": "1001", "title": "Epidemiology of atrial fibrillation"},
                {"id": "15505", "title": "Apixaban: Drug information"},
            ],
        )
        assert rerank.ok is True
        assert rerank.requires_drug_monograph is True
        assert rerank.requires_table_data is True
        assert rerank.ranked_topics[0].id == "15505"
        assert "15505" in rerank.hint

    def test_rank_overview_items_calc_bypass_makes_zero_calls(self):
        sdk = FakeSdkClient(lambda c: _resp({}))
        client = DecisionClient(sdk_client=sdk)
        res = rank_overview_items(
            client,
            "Calculate Wells score",
            topic_id="calc1",
            topic_title="Wells calculator",
            topic_type="calc",
            sections=[{"id": "H1", "title": "Main"}],
            graphics=[],
            related_topics=[],
        )
        assert res.ok is True
        assert len(sdk.decisions.calls) == 0

    def test_rank_overview_items_against_live_sqlite_db(self):
        db = ClinRefDatabase(db_dir=DB_DIR)
        try:
            tools_list = init_tools(db)
            overview_tool = next(t for t in tools_list if t.name == "getTopicOverview")
            search_hits = db.search_topics("apixaban atrial fibrillation")
            assert search_hits
            first_id = str(search_hits[0]["id"])
            overview = overview_tool.invoke({"topic_id": first_id}).model_dump()
            assert "sections" in overview and overview["sections"]

            def handler(call):
                ans = {}
                for k, q in call["questions"].items():
                    if k == "needs_drug_monograph":
                        ans[k] = _noul(0.88)
                    elif k.startswith("sec_"):
                        instr = q["instructions"].lower()
                        ans[k] = _score(2.85 if ("dos" in instr or "anticoag" in instr or "therapy" in instr) else 0.7)
                    elif k.startswith("gfx_"):
                        ans[k] = _score(2.5)
                    elif k.startswith("rel_"):
                        ans[k] = _score(2.2)
                return _resp(ans)

            client = DecisionClient(sdk_client=FakeSdkClient(handler))
            rerank = rank_overview_items(
                client,
                "Apixaban dosing for nonvalvular atrial fibrillation",
                topic_id=first_id,
                topic_title=overview.get("title", ""),
                topic_type=overview.get("topicType", "medical"),
                sections=overview.get("sections", []),
                graphics=overview.get("graphics", []),
                related_topics=overview.get("relatedTopics", []),
            )
            assert rerank.ok is True
            assert len(rerank.recommended_section_ids) >= 1
            injected = inject_rerank_into_tool_json(
                json.dumps(overview),
                {"hint": rerank.hint, "recommendedSectionIds": rerank.recommended_section_ids},
            )
            parsed = json.loads(injected)
            assert parsed["decisionRerank"]["recommendedSectionIds"] == rerank.recommended_section_ids
        finally:
            db.close()


# ── 3. Full LangGraph E2E Integration with Retrieval Reranking ───────────────


class TestAgentEndToEndWithDecisions:
    def test_phase1_rerank_and_auto_prefetch_with_no_gate_decision_calls(self):
        db = ClinRefDatabase(db_dir=DB_DIR)
        try:
            tools = init_tools(db)
            overview_tool = next(t for t in tools if t.name == "getTopicOverview")
            hits = db.search_topics("apixaban")
            assert hits
            topic_id = str(hits[0]["id"])
            overview = overview_tool.invoke({"topic_id": topic_id}).model_dump()
            sections = overview.get("sections") or []
            assert len(sections) >= 1
            sec1 = str(sections[0]["id"])

            safe_draft = f"**Clinicians review dosing criteria in {overview.get('title', 'apixaban')}.**"

            decision_calls: list[str] = []

            def sdk_handler(call):
                qs = call["questions"]
                decision_calls.append(",".join(sorted(qs.keys())[:2]))

                # Phase 1: searchTopics rerank
                if "requires_drug_monograph" in qs:
                    ans = {
                        "requires_drug_monograph": _noul(0.92),
                        "requires_table_data": _noul(0.40),
                    }
                    for k, q in qs.items():
                        if k.startswith("topic_"):
                            ans[k] = _score(2.8 if f"ID {topic_id}" in q["instructions"] else 0.8)
                    return _resp(ans, input_tokens=200)

                # Phase 1: getTopicOverview rerank + auto-prefetch triggers on sec1
                if any(k.startswith("sec_") for k in qs):
                    ans = {}
                    for k, q in qs.items():
                        if k == "needs_drug_monograph":
                            ans[k] = _noul(0.2)
                        elif k.startswith("sec_"):
                            ans[k] = _score(2.9 if f"ID {sec1}" in q["instructions"] else 0.5)
                        elif k.startswith("gfx_"):
                            ans[k] = _score(0.5)
                        elif k.startswith("rel_"):
                            ans[k] = _score(0.5)
                    return _resp(ans, input_tokens=300)

                raise AssertionError(f"Unexpected Decision API call outside Phase 1 reranking: {list(qs.keys())}")

            tracker = TokenTracker()
            dec_client = DecisionClient(
                sdk_client=FakeSdkClient(sdk_handler),
                on_usage=tracker.on_decision_end,
            )

            phase1_llm = ScriptedChatLLM(
                [
                    AIMessage(
                        content="",
                        tool_calls=[{"name": "searchTopics", "args": {"query": "apixaban"}, "id": "c1"}],
                    ),
                    AIMessage(
                        content="",
                        tool_calls=[{"name": "getTopicOverview", "args": {"topic_id": topic_id}, "id": "c2"}],
                    ),
                    # Prefetched section sec1 already loaded into TurnContext; finish Phase 1 directly
                    AIMessage(content="Evidence complete."),
                ]
            )
            phase2_llm = ScriptedChatLLM([AIMessage(content=safe_draft)])

            system_prompt = build_system_prompt()
            cfg = DecisionConfig.rerank(auto_prefetch=True)
            cfg.top_k_sections = 1
            agent = create_clinical_agent(
                tools=tools,
                system_prompt=system_prompt,
                callbacks=[tracker],
                llm_with_tools=phase1_llm,
                llm_tool_free=phase2_llm,
                decision_client=dec_client,
                decision_config=cfg,
            )

            final = agent.invoke(
                create_initial_state("Apixaban guidance?", system_prompt),
                config={"callbacks": [tracker]},
            )

            assert final["validation_result"]["passed"] is True
            assert any(sec["section_id"] == sec1 for sec in final["turn_context"]["fetched_sections"])
            assert any("decisionRerank" in res for res in final["turn_context"]["tool_results"])
            overview_calls = [
                c for c in final["turn_context"]["tool_calls"]
                if c["tool_name"] == "getTopicOverview"
            ]
            assert overview_calls and "autoPrefetched" in overview_calls[0]["result"]
            assert "search_reranks" in final["decision_telemetry"]
            assert "overview_reranks" in final["decision_telemetry"]
            # Strictly 2 Decision calls: searchTopics rerank + getTopicOverview rerank (none in Gates 1/2)
            assert tracker.get_summary()["decision_calls"] == 2
            assert tracker.get_summary()["decision_input_tokens"] == 500
        finally:
            db.close()

    def test_small_talk_bypasses_decisions_completely(self):
        sdk = FakeSdkClient(lambda c: _resp({}))
        dec_client = DecisionClient(sdk_client=sdk)
        llm = ScriptedChatLLM([])
        agent = create_clinical_agent(
            llm=llm,
            tools=[],
            system_prompt="sys",
            decision_client=dec_client,
            decision_config=DecisionConfig.rerank(),
        )
        final = agent.invoke(create_initial_state("hello doctor", "sys"))
        assert final["messages"][-1].content == CANNED_SMALL_TALK_REPLY
        assert len(llm.invocations) == 0
        assert len(sdk.decisions.calls) == 0


# ── 4. run.py Decision Wiring & Log Formatting Tests ─────────────────────────


class TestRunAndEvalIntegration:
    def test_run_single_query_with_decision_mode(self, monkeypatch, capsys):
        def sdk_handler(call):
            return _resp({"requires_drug_monograph": _noul(0.9)}, input_tokens=100)

        tracker = TokenTracker()
        dec_client = DecisionClient(sdk_client=FakeSdkClient(sdk_handler), on_usage=tracker.on_decision_end)
        client, cfg = run_module._resolve_decision_runtime(
            "rerank", True, tracker, decision_client=dec_client
        )
        assert client is dec_client
        assert cfg is not None and cfg.enable_retrieval_rerank is True and cfg.auto_prefetch is True
        assert DecisionConfig.from_mode("off") is None
        with pytest.raises(ValueError, match="Unknown decision mode"):
            DecisionConfig.from_mode("full")

        # Smoke run.run_single_query on small talk with decision_mode='rerank'
        fake_llm = ScriptedChatLLM([])
        monkeypatch.setattr(run_module, "create_llm", lambda provider, model, api_key, **kw: fake_llm)
        monkeypatch.setattr(run_module, "resolve_api_key", lambda provider: "fake-key")
        run_module.run_single_query(
            "hi there",
            "openai",
            "gpt-4o",
            str(DB_DIR),
            decision_mode="rerank",
            decision_prefetch=True,
            decision_client=dec_client,
        )
        captured = capsys.readouterr().out
        assert CANNED_SMALL_TALK_REPLY in captured

    def test_runtime_log_formatters_and_token_summary(self, capsys):
        import json as _json

        search_payload = _json.dumps(
            {
                "query": "apixaban",
                "results": [
                    {"id": "85642", "title": "Apixaban: Drug information"},
                    {"id": "15035", "title": "Atrial fibrillation overview"},
                ],
                "decisionRerank": {
                    "requiresDrugMonograph": True,
                    "requiresTableData": False,
                    "hint": "Apixaban: Drug information (85642) matches dosing query.",
                },
            }
        )
        search_lines = run_module._format_tool_log_lines(search_payload, 0.42)
        assert "searchTopics('apixaban') -> 2 hits" in search_lines[0]
        assert "[Tool | 0.42s]" in search_lines[0]
        assert "[Decision Rerank]" in search_lines[1]
        assert "needsDrugMonograph=True" in search_lines[1]

        overview_payload = _json.dumps(
            {
                "topicId": "85642",
                "title": "Apixaban: Drug information",
                "topicType": "drug",
                "sections": [{"id": "F1", "title": "-Dosing: Adult"}],
                "graphics": [{"id": "G1", "isTable": True}],
                "decisionRerank": {
                    "recommendedSectionIds": ["F1"],
                    "recommendedGraphicIds": ["G1"],
                    "autoPrefetched": True,
                    "prefetchedSectionsMarkdown": "x" * 1200,
                    "prefetchedTablesMarkdown": "y" * 350,
                },
            }
        )
        ov_lines = run_module._format_tool_log_lines(overview_payload, 1.15)
        assert "getTopicOverview(85642 'Apixaban: Drug information', type=drug) -> 1 sections, 1 tables" in ov_lines[0]
        assert "picked 1 sections: F1 (Dosing: Adult)" in ov_lines[1]
        assert "1 tables: G1" in ov_lines[1]
        assert "[Auto-Prefetch] loaded 1 sections (1,200 chars) + 1 tables (350 chars)" in ov_lines[2]

        sec_payload = _json.dumps(
            {
                "topicId": "85642",
                "sectionTitles": {"F1": "Dosing: Adult"},
                "markdown": "5 mg twice daily",
                "invalidSections": ["BAD"],
            }
        )
        sec_lines = run_module._format_tool_log_lines(sec_payload, 0.08)
        assert "getTopicSectionsText(topic=85642) -> 1 sections (16 chars): F1 (Dosing: Adult)" in sec_lines[0]
        assert "invalidSections=['BAD']" in sec_lines[1]

        err_lines = run_module._format_tool_log_lines(_json.dumps({"error": "Not found"}), 0.03)
        assert "[Tool ERROR | 0.03s] Not found" in err_lines[0]

        g1_pass = run_module._format_gate1_lines(
            {
                "validation_result": {"passed": True},
                "turn_context": {"fetched_sections": [("85642", "F1")], "graphic_ids": []},
            },
            0.01,
        )
        assert "[Gate 1 | 0.01s] PASSED (1 sections, 0 tables)" in g1_pass[0]

        g1_block = run_module._format_gate1_lines(
            {
                "validation_result": {"passed": False, "blocked_reason": "Need drug dosing section"},
                "turn_context": {"fetched_sections": [], "graphic_ids": []},
            },
            0.02,
        )
        assert "[Gate 1 | 0.02s] BLOCKED: Need drug dosing section" in g1_block[0]

        g2_lines = run_module._format_gate2_lines(
            {
                "passed": False,
                "topic_refs": [("85642", "F1")],
                "graphic_refs": [],
                "blocked_reason": "Unverified dose detected",
                "warnings": ["Missing renal note"],
            },
            0.04,
        )
        assert "[Gate 2 | 0.04s] Validation: BLOCKED (refs: 1 sections, 0 tables)" in g2_lines[0]
        assert "Blocked: Unverified dose detected" in g2_lines[1]
        assert "Warning: Missing renal note" in g2_lines[2]

        tracker = TokenTracker()
        for tok in (1200, 2300):
            tracker.on_decision_end(tok)
        tracker.print_summary(elapsed_seconds=12.34)
        out = capsys.readouterr().out
        assert "Elapsed:        12.34s" in out
        assert "Decision calls: 2" in out
        assert "Decision in:    3,500 (0 out, ~$0.0004)" in out


def test_rank_overview_default_section_and_table_limits():
    cfg = DecisionConfig()
    assert cfg.top_k_sections == 8
    assert cfg.top_k_tables == 4

    def handler(call):
        qs = call["questions"]
        answers = {}
        for k in qs:
            if k == "needs_drug_monograph":
                answers[k] = _noul(0.2, 0.9)
            else:
                answers[k] = _score(2.6, 0.9)
        return _resp(answers, input_tokens=120)

    client = DecisionClient(sdk_client=FakeSdkClient(handler))
    sections = [{"id": f"S{i}", "title": f"Section {i}"} for i in range(1, 23)]
    graphics = [{"id": f"G{i}", "title": f"Table {i}", "isTable": True} for i in range(1, 11)]
    res = rank_overview_items(
        client,
        "Full treatment & tables?",
        topic_id="999",
        topic_title="Large clinical review",
        topic_type="medical",
        sections=sections,
        graphics=graphics,
        related_topics=[],
        config=cfg,
    )
    assert res.ok
    assert len(res.recommended_section_ids) == 8
    assert len(res.recommended_graphic_ids) == 4


def test_infer_topic_type():
    from tools import infer_topic_type
    assert infer_topic_type("Apixaban: Drug information") == "drug"
    assert infer_topic_type("Amoxicillin (pediatric): Drug information") == "drug"
    assert infer_topic_type("CHA2DS2-VASc score (calculator)") == "calc"
    assert infer_topic_type("GFR Cockcroft-Gault Calculator: Clinical calculation") == "calc"
    assert infer_topic_type("Lab interpretation: Ferritin") == "narrative"
    assert infer_topic_type("Treatment of community-acquired pneumonia in adults") == "medical"


def test_rank_overview_parent_penalty_only_when_child_meets_threshold():
    def handler(call):
        qs = call["questions"]
        answers = {}
        for k in qs:
            if "sec_parent" in k:
                answers[k] = _score(1.9, 0.9)
            elif "sec_child" in k:
                answers[k] = _score(1.6, 0.9)
            else:
                answers[k] = _score(0.5, 0.9)
        return _resp(answers, input_tokens=80)

    client = DecisionClient(sdk_client=FakeSdkClient(handler))
    sections = [
        {"id": "sec_parent", "title": "Initial Blood Pressure Management", "level": 1},
        {"id": "sec_child", "title": "Lifestyle Modifications", "level": 2, "parentId": "sec_parent"},
    ]
    res = rank_overview_items(
        client,
        "How to treat hypertension?",
        topic_id="Topic-HTN",
        topic_title="Hypertension Management",
        topic_type="medical",
        sections=sections,
        graphics=[],
        related_topics=[],
        config=DecisionConfig(section_score_threshold=1.8),
    )
    assert res.ok
    # Parent must be retained and recommended because child (1.6) failed threshold (1.8)
    assert res.recommended_section_ids == ["sec_parent"]


def test_inject_overview_rerank_prunes_low_scoring_sections_when_large():
    from decision_gates import OverviewRerankResult, RankedItem, inject_overview_rerank_into_json
    overview_data = {
        "topicId": "Topic-Large",
        "title": "Large Topic",
        "topicType": "medical",
        "sections": [
            {"id": f"S{i}", "title": f"Section {i}", "level": 1}
            for i in range(1, 25)
        ],
        "graphics": [],
    }
    overview_json = json.dumps(overview_data)
    ranked_sections = [
        RankedItem(id="S1", title="Section 1", score=2.8),
        RankedItem(id="S2", title="Section 2", score=2.5),
    ] + [
        RankedItem(id=f"S{i}", title=f"Section {i}", score=0.4)
        for i in range(3, 25)
    ]
    rerank = OverviewRerankResult(
        topic_id="Topic-Large",
        topic_title="Large Topic",
        section_scores=ranked_sections,
        recommended_section_ids=["S1", "S2"],
        ok=True,
    )
    enriched = inject_overview_rerank_into_json(overview_json, rerank, auto_prefetched=True)
    parsed = json.loads(enriched)
    retained_sections = parsed.get("sections", [])
    # Low-scoring sections (< 1.0) are pruned from the 24 sections when len > 15
    assert len(retained_sections) < 24
    assert any(s["id"] == "S1" for s in retained_sections)
    assert any(s["id"] == "S2" for s in retained_sections)


def test_option_a_related_topic_gating():
    def handler(call):
        qs = call["questions"]
        answers = {}
        for k in qs:
            if "rel_high" in k:
                answers[k] = _score(2.9, 0.9)
            elif "rel_borderline" in k:
                answers[k] = _score(2.5, 0.9)
            elif "sec_best" in k:
                answers[k] = _score(2.7, 0.9)
            else:
                answers[k] = _score(1.0, 0.9)
        return _resp(answers, input_tokens=100)

    client = DecisionClient(sdk_client=FakeSdkClient(handler))
    sections = [{"id": "sec_best", "title": "Main treatment", "level": 1}]
    related = [
        {"id": "rel_high", "title": "Critical related topic"},
        {"id": "rel_borderline", "title": "Borderline topic"},
    ]
    res = rank_overview_items(
        client,
        "Critical condition management?",
        topic_id="Topic-Crit",
        topic_title="Critical condition",
        topic_type="medical",
        sections=sections,
        graphics=[],
        related_topics=related,
    )
    assert res.ok
    # Both score >= 2.0 (topic_score_threshold), so both are recommended related topics
    assert res.recommended_related_topic_ids == ["rel_high", "rel_borderline"]
    # Option A: rel_high has score 2.9 (>= 2.6 and >= 2.8), so needs_related_topic is True
    assert res.needs_related_topic is True

