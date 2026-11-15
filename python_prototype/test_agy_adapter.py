"""Unit & E2E tests for `AgyChatModel` and `run.py --provider agy`."""

from __future__ import annotations

import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage

sys.path.insert(0, str(Path(__file__).parent))

from agent import create_clinical_agent, create_initial_state
from agy_chat_model import AgyChatModel
from database import ClinRefDatabase
import run as run_module
from token_tracker import TokenTracker
from tools import init_tools


DB_DIR = Path(__file__).parent.parent


def _proc(stdout: str, returncode: int = 0, stderr: str = ""):
    return SimpleNamespace(stdout=stdout, stderr=stderr, returncode=returncode)


class TestAgyChatModel:
    def test_bind_tools_returns_separate_bound_copy(self):
        db = ClinRefDatabase(db_dir=DB_DIR)
        try:
            tools = init_tools(db)
            base = AgyChatModel(model_name="gemini-3.8-flash-medium")
            bound = base.bind_tools(tools)

            assert base.bound_tools == []
            assert len(bound.bound_tools) == 4
            assert {t["name"] for t in bound.bound_tools} == {
                "searchTopics",
                "getTopicOverview",
                "getTopicSectionsText",
                "getGraphicContent",
            }
        finally:
            db.close()

    def test_phase1_tool_call_cli_flags_and_token_tracking(self):
        seen_calls: list[dict] = []

        def fake_runner(cmd, *, cwd, timeout):
            seen_calls.append({"cmd": cmd, "cwd": cwd, "timeout": timeout})
            payload = {
                "status": "SUCCESS",
                "structured_output": {
                    "tool_calls": [
                        {
                            "name": "searchtopics",
                            "args_json": '```json\n{"query": "apixaban"}\n```',
                        }
                    ],
                    "content": "",
                },
                "response": "{}",
                "usage": {
                    "input_tokens": 1200,
                    "output_tokens": 40,
                    "thinking_tokens": 10,
                    "total_tokens": 1250,
                },
            }
            return _proc("noise warning line\n" + json.dumps(payload))

        db = ClinRefDatabase(db_dir=DB_DIR)
        try:
            tools = init_tools(db)
            tracker = TokenTracker()
            llm = AgyChatModel(model_name="gpt-oss-120b-medium", runner=fake_runner)
            bound = llm.bind_tools(tools)

            ai_msg = bound.invoke(
                [SystemMessage(content="sys"), HumanMessage(content="Apixaban AFib dose?")],
                config={"callbacks": [tracker]},
            )
            assert isinstance(ai_msg, AIMessage)
            assert len(ai_msg.tool_calls) == 1
            assert ai_msg.tool_calls[0]["name"] == "searchTopics"
            assert ai_msg.tool_calls[0]["args"] == {"query": "apixaban"}

            cmd = seen_calls[0]["cmd"]
            assert seen_calls[0]["cwd"] != str(DB_DIR)
            assert "--disable-slash-commands" in cmd
            assert "--dangerously-skip-permissions" in cmd
            assert "--output-format=json" in cmd
            assert "--model=gpt-oss-120b-medium" in cmd
            assert any(arg.startswith("--json-schema=") for arg in cmd)
            assert any(arg.startswith("--print=") for arg in cmd)

            summary = tracker.get_summary()
            assert summary["llm_calls"] == 1
            assert summary["input_tokens"] == 1200
            assert summary["output_tokens"] == 50
            assert summary["total_tokens"] == 1250
        finally:
            db.close()

    def test_phase2_tool_free_omits_json_schema(self):
        seen_cmds: list[list[str]] = []

        def fake_runner(cmd, *, cwd, timeout):
            seen_cmds.append(cmd)
            return _proc(
                json.dumps(
                    {
                        "status": "SUCCESS",
                        "response": "**We recommend apixaban 5 mg twice daily.**\n",
                        "usage": {"input_tokens": 900, "output_tokens": 30, "total_tokens": 930},
                    }
                )
            )

        llm = AgyChatModel(runner=fake_runner)
        res = llm.invoke([HumanMessage(content="Synthesize answer")])
        assert res.content == "**We recommend apixaban 5 mg twice daily.**"
        assert not getattr(res, "tool_calls", None)
        assert not any(arg.startswith("--json-schema=") for arg in seen_cmds[0])
        assert not any(arg.startswith("--model=") for arg in seen_cmds[0])

    def test_retry_on_503_and_error_when_exhausted(self):
        attempts = 0

        def flaky_runner(cmd, *, cwd, timeout):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                return _proc(
                    json.dumps(
                        {
                            "status": "ERROR",
                            "response": "",
                            "error": "UNAVAILABLE (code 503): No capacity",
                            "usage": {},
                        }
                    ),
                    returncode=3,
                )
            return _proc(
                json.dumps({"status": "SUCCESS", "response": "Recovered OK", "usage": {}})
            )

        llm = AgyChatModel(retries=1, runner=flaky_runner)
        msg = llm.invoke([HumanMessage(content="Hi")])
        assert attempts == 2
        assert msg.content == "Recovered OK"

        def always_fail(cmd, *, cwd, timeout):
            return _proc(
                json.dumps({"status": "ERROR", "response": "", "error": "Hard failure"}),
                returncode=1,
            )

        bad_llm = AgyChatModel(retries=0, runner=always_fail)
        with pytest.raises(RuntimeError, match="Hard failure"):
            bad_llm.invoke([HumanMessage(content="Hi")])

    def test_concatenated_json_envelopes_and_finish_tool_filtering(self):
        # Multi-turn CLI runs can concatenate JSON objects into `response` without `structured_output`,
        # and `agy` models sometimes emit an unbound `finish` call instead of `tool_calls: []`.
        db = ClinRefDatabase(db_dir=DB_DIR)
        try:
            tools = init_tools(db)

            def multi_json_runner(cmd, *, cwd, timeout):
                concat = (
                    '{"tool_calls": [], "content": ""}\n'
                    '{"tool_calls": [{"name": "searchTopics", "args_json": "{\\"query\\": \\"afib\\"}"}], "content": ""}\n'
                )
                return _proc(json.dumps({"status": "SUCCESS", "response": concat, "usage": {}}))

            llm = AgyChatModel(runner=multi_json_runner).bind_tools(tools)
            msg = llm.invoke([HumanMessage(content="AFib")])
            assert len(msg.tool_calls) == 1
            assert msg.tool_calls[0]["name"] == "searchTopics"
            assert msg.tool_calls[0]["args"] == {"query": "afib"}

            def finish_runner(cmd, *, cwd, timeout):
                finish_obj = {
                    "tool_calls": [
                        {
                            "name": "finish",
                            "args_json": json.dumps({"content": "Done gathering sections."}),
                        }
                    ],
                    "content": "",
                }
                return _proc(
                    json.dumps({"status": "SUCCESS", "structured_output": finish_obj, "usage": {}})
                )

            llm2 = AgyChatModel(runner=finish_runner).bind_tools(tools)
            done_msg = llm2.invoke([HumanMessage(content="Done")])
            assert done_msg.tool_calls == []
            assert "Done gathering sections." in done_msg.content
        finally:
            db.close()

    def test_prompt_compaction_keeps_user_question_and_newest_tool(self):
        llm = AgyChatModel(max_prompt_bytes=4000, runner=lambda *a, **kw: _proc("{}"))
        msgs = [
            SystemMessage(content="Clinical rules"),
            HumanMessage(content="ORIGINAL_QUESTION_MARKER"),
            AIMessage(content="", tool_calls=[{"name": "searchTopics", "args": {"query": "x"}, "id": "t1"}]),
            ToolMessage(content="OLD_BLOAT_" + ("Z" * 6000), tool_call_id="t1"),
            AIMessage(content="", tool_calls=[{"name": "getTopicOverview", "args": {"topic_id": "1"}, "id": "t2"}]),
            ToolMessage(content="NEWEST_EVIDENCE_MARKER", tool_call_id="t2"),
        ]
        prompt = llm._format_messages_prompt(msgs)
        assert len(prompt.encode("utf-8")) <= 4000
        assert "ORIGINAL_QUESTION_MARKER" in prompt
        assert "NEWEST_EVIDENCE_MARKER" in prompt
        assert "OLD_BLOAT" not in prompt

    def test_run_create_llm_and_full_langgraph_e2e_turn(self):
        created = run_module.create_llm("agy", "gemini-3.8-flash-medium", None)
        assert isinstance(created, AgyChatModel)
        assert created.model_name == "gemini-3.8-flash-medium"
        assert run_module.resolve_api_key("agy") is None

        db = ClinRefDatabase(db_dir=DB_DIR)
        try:
            tools = init_tools(db)
            overview_tool = next(t for t in tools if t.name == "getTopicOverview")
            hits = db.search_topics("apixaban")
            topic_id = str(hits[0]["id"])
            overview = overview_tool.invoke({"topic_id": topic_id}).model_dump()
            sec_id = str(overview["sections"][0]["id"])
            title = overview.get("title", "Apixaban")

            step = 0

            def scripted_runner(cmd, *, cwd, timeout):
                nonlocal step
                step += 1
                is_phase1 = any(arg.startswith("--json-schema=") for arg in cmd)
                if is_phase1:
                    if step == 1:
                        out = {
                            "tool_calls": [
                                {"name": "getTopicSectionsText", "args_json": json.dumps({"topic_id": topic_id, "section_ids": [sec_id]})}
                            ],
                            "content": "",
                        }
                    else:
                        out = {"tool_calls": [], "content": "Evidence retrieved."}
                    return _proc(
                        json.dumps(
                            {
                                "status": "SUCCESS",
                                "structured_output": out,
                                "response": json.dumps(out),
                                "usage": {"input_tokens": 500, "output_tokens": 25, "total_tokens": 525},
                            }
                        )
                    )
                return _proc(
                    json.dumps(
                        {
                            "status": "SUCCESS",
                            "response": f"**Consult retrieved {title} monograph sections before prescribing.**",
                            "usage": {"input_tokens": 700, "output_tokens": 20, "total_tokens": 720},
                        }
                    )
                )

            tracker = TokenTracker()
            llm = AgyChatModel(model_name="gemini-3.8-flash-medium", runner=scripted_runner)
            agent = create_clinical_agent(
                llm=llm,
                tools=tools,
                system_prompt="Clinical instructions",
                callbacks=[tracker],
                llm_with_tools=llm.bind_tools(tools),
                llm_tool_free=llm,
            )

            final = agent.invoke(
                create_initial_state("Apixaban guidance?", "Clinical instructions"),
                config={"callbacks": [tracker]},
            )
            assert final["validation_result"]["passed"] is True
            assert title in final["messages"][-1].content
            assert step == 3
            summary = tracker.get_summary()
            assert summary["llm_calls"] == 3
            assert summary["total_tokens"] == 525 + 525 + 720
        finally:
            db.close()
