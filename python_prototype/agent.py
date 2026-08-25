r"""LangGraph clinical retrieval agent — mirrors KoogAgentFactory.kt.

State graph:
    nodeStart -> [LLM Request] <-> [Execute Tools] (tool loop)
                          \-> [Safety Validate] -> [Self-Correction] or [Finish]
"""

import json
import re
from typing import Annotated, Callable, Optional, TypedDict

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import BaseTool
from langgraph.graph import END, StateGraph
from langgraph.graph.message import add_messages

from safety_validator import (
    FetchedSection,
    GraphicRef,
    SafetyValidator,
    ToolCallRecord,
    TopicRef,
    TurnContext,
    ValidationResult,
)
from system_prompt import build_correction_prompt


class AgentState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    system_prompt: str
    turn_context: dict  # Serialized TurnContext
    user_question: str  # Original user question for number validation
    retry_count: int
    validation_result: Optional[dict]
    tool_rounds: int  # Number of execute_tools passes this turn


def _build_tool_map(tools: list[BaseTool]) -> dict[str, Callable]:
    """Build name -> invoke mapping for manual tool execution."""
    return {t.name: t for t in tools}


def _is_logical_failure(result: str) -> bool:
    r"""Detect tool results that executed fine but retrieved nothing.

    Mirrors Kotlin TurnContextAccumulator.isLogicalFailure: plain-text
    sentinels plus JSON error envelopes ({"error": "..."}), and batch
    section responses where every requested ID was invalid.
    """
    trimmed = result.strip()
    if trimmed.lower().startswith("topic not found"):
        return True
    if trimmed.lower() == "section not found.":
        return True
    if trimmed.startswith("{"):
        try:
            data = json.loads(trimmed)
            if isinstance(data, dict):
                if data.get("error"):
                    return True
                markdown = data.get("markdown")
                invalid = data.get("invalidSections")
                if markdown == "" and invalid:
                    return True
        except json.JSONDecodeError:
            pass
    return False


def create_clinical_agent(
    llm,
    tools: list[BaseTool],
    system_prompt: str,
    callbacks: list = None,
    max_tool_retries: int = 24,
    max_safety_retries: int = 2,
):
    """Create the clinical retrieval agent graph.

    Args:
        llm: LangChain chat model with bound tools
        tools: List of tool functions
        system_prompt: System prompt text
        callbacks: List of callback handlers (e.g., TokenTracker)
        max_tool_retries: Max execute_tools rounds before routing to validation
            instead of looping further (default 24, mirrors Kotlin's cap)
        max_safety_retries: Max self-correction retries (default 2)
    """
    tool_map = _build_tool_map(tools)

    def should_continue(state: AgentState) -> str:
        """Route: tool calls -> execute_tools, text response -> validate_safety."""
        last_message = state["messages"][-1]
        if isinstance(last_message, AIMessage) and last_message.tool_calls:
            return "execute_tools"
        return "validate_safety"

    def after_tools(state: AgentState) -> str:
        """Route after tool execution: terminal tool -> validate, else compact & LLM.

        If submit_clinical_answer was called, skip the next LLM call and go
        straight to validation to prevent double-submission. Also stop the
        loop gracefully when the tool-round budget is exhausted (mirrors
        Kotlin maxAgentIterations) instead of relying on LangGraph's
        recursion limit.
        """
        tc = TurnContext(**state["turn_context"])
        if tc.tool_calls and tc.tool_calls[-1].tool_name == "submit_clinical_answer":
            return "validate_safety"

        if state.get("tool_rounds", 0) >= max_tool_retries:
            return "validate_safety"

        # Context Hygiene: Truncate older search_topics result messages in history if sections were fetched
        if len(tc.fetched_sections) > 0 and len(state["messages"]) > 4:
            new_msgs = list(state["messages"])
            for idx, msg in enumerate(new_msgs):
                if isinstance(msg, ToolMessage) and getattr(msg, "name", "") == "search_topics":
                    if len(msg.content) > 300:
                        msg.content = msg.content[:250] + "... [truncated search results]"
            state["messages"] = new_msgs

        return "llm"

    def call_llm(state: AgentState) -> dict:
        """Invoke LLM with conversation history."""
        config = {"callbacks": callbacks} if callbacks else {}
        response = llm.invoke(state["messages"], config=config)
        return {"messages": [response]}

    def execute_tools(state: AgentState) -> dict:
        """Execute tool calls manually (mirrors Kotlin ToolRegistry pattern).

        Deduplicates calls: if the same tool+args was already executed in this
        turn, returns the cached result instead of re-executing.
        """
        last_message = state["messages"][-1]
        tc = TurnContext(**state["turn_context"])
        new_messages: list[BaseMessage] = []

        if not isinstance(last_message, AIMessage) or not last_message.tool_calls:
            return {}

        for tool_call in last_message.tool_calls:
            name = tool_call["name"]
            args = tool_call["args"]
            tool_call_id = tool_call.get("id", "")

            # Deduplicate: check if same tool+args already called this turn
            args_key = json.dumps(args, sort_keys=True)
            cached = next(
                (tc2 for tc2 in tc.tool_calls
                 if tc2.tool_name == name and json.dumps(tc2.arguments, sort_keys=True) == args_key),
                None,
            )
            if cached is not None:
                new_messages.append(
                    ToolMessage(content=cached.result, tool_call_id=tool_call_id)
                )
                continue

            tc.tool_calls.append(
                ToolCallRecord(
                    tool_name=name,
                    arguments=args,
                    result="",
                    success=True,
                )
            )

            # Execute the tool
            tool = tool_map.get(name)
            if tool is None:
                result_content = json.dumps({"error": f"Unknown tool: {name}"})
                success = False
            else:
                success = True
                try:
                    raw = tool.invoke(args)
                    result_content = raw if isinstance(raw, str) else json.dumps(raw)
                except Exception as e:
                    result_content = json.dumps({"error": str(e)})
                    success = False

            # Logical failure: tool ran but retrieved nothing usable
            if success and _is_logical_failure(result_content):
                success = False

            tc.tool_calls[-1].success = success
            tc.tool_calls[-1].result = result_content
            tc.tool_results.append(result_content)

            # Track topic titles and section titles from outline
            if name == "get_topic_outline":
                try:
                    outline_data = json.loads(result_content)
                    topic_title = outline_data.get("title", "")
                    topic_id = outline_data.get("topicId", args.get("topic_id", ""))
                    if topic_id and topic_title:
                        tc.topic_titles[topic_id] = topic_title
                    # Store full section ID → title map
                    sections = outline_data.get("sections", [])
                    if topic_id and sections:
                        tc.outline_sections[topic_id] = {
                            s["id"]: s["title"] for s in sections if s.get("id")
                        }
                except (json.JSONDecodeError, AttributeError):
                    pass

            # Always try to get topic title from DB for any section-related tool
            if name == "get_topic_sections_text":
                topic_id = args.get("topic_id", "")
                if topic_id and topic_id not in tc.topic_titles:
                    try:
                        from tools import _db
                        if _db is not None:
                            db_title = _db.get_topic_title(topic_id)
                            if db_title:
                                tc.topic_titles[topic_id] = db_title
                    except Exception:
                        pass

            # Batch section tracking
            if name == "get_topic_sections_text":
                topic_id = args.get("topic_id", "")
                # Parse structured JSON result from batch tool
                try:
                    batch_data = json.loads(result_content)
                    topic_title = batch_data.get("topicTitle", "")
                    section_titles = batch_data.get("sectionTitles", {})
                    if topic_id and topic_title:
                        tc.topic_titles[topic_id] = topic_title
                    if topic_id and section_titles:
                        tc.outline_sections[topic_id] = section_titles
                except (json.JSONDecodeError, TypeError):
                    section_titles = {}
                # Use stored outline sections for titles (fallback to parsed data)
                section_map = tc.outline_sections.get(topic_id, section_titles)
                for sid in args.get("section_ids", []):
                    tc.fetched_sections.append(
                        FetchedSection(
                            topic_id=topic_id,
                            topic_title=tc.topic_titles.get(topic_id, ""),
                            section_id=sid,
                            section_title=section_map.get(sid, ""),
                            content_snippet=result_content[:2000],
                        )
                    )

            # Track graphic IDs and titles
            if name == "get_graphic_content":
                graphic_id = args.get("graphic_id", "")
                if graphic_id:
                    tc.graphic_ids.add(graphic_id)
                    # Extract title from result: "### Graphic Table: {title}\n\n{markdown}"
                    m = re.match(r"### Graphic Table:\s*(.+)", result_content)
                    if m:
                        tc.graphic_titles[graphic_id] = m.group(1).strip()

            # Auto-populate refs from TurnContext
            if name == "submit_clinical_answer":
                _auto_populate_refs(tc, result_content)

            new_messages.append(
                ToolMessage(content=result_content, tool_call_id=tool_call_id)
            )

        return {
            "messages": new_messages,
            "turn_context": tc.model_dump(),
            "user_question": state.get("user_question", ""),
            "tool_rounds": state.get("tool_rounds", 0) + 1,
        }

    def _extract_final_answer(state: AgentState, tc: TurnContext) -> str:
        """Resolve the turn's final answer from the submitted terminal tool
        record, falling back to the last plain-text AI message."""
        submit_rec = next(
            (r for r in reversed(tc.tool_calls) if r.tool_name == "submit_clinical_answer"),
            None,
        )
        if submit_rec is not None:
            try:
                data = json.loads(submit_rec.result)
                if data.get("status") == "SUBMITTED" and "answer" in data:
                    return data["answer"]
            except (json.JSONDecodeError, TypeError):
                pass

        for msg in reversed(state["messages"]):
            if isinstance(msg, AIMessage) and msg.content and not msg.tool_calls:
                return msg.content
        return ""

    def validate_safety(state: AgentState) -> dict:
        """Run safety validation on the final answer."""
        tc = TurnContext(**state["turn_context"])
        answer = _extract_final_answer(state, tc)

        tc.answer = answer
        tc.user_question = state.get("user_question", "")

        validator = SafetyValidator()
        validation = validator.validate(tc)

        if not validation.passed and state["retry_count"] < max_safety_retries:
            # Build evidence summary for correction turn
            evidence_lines = []
            for sec in tc.fetched_sections:
                if sec.content_snippet:
                    evidence_lines.append(
                        f"Section [{sec.section_id}] ({sec.section_title}):\n{sec.content_snippet}"
                    )
                else:
                    evidence_lines.append(
                        f"Section [{sec.section_id}] ({sec.section_title}) from topic {sec.topic_id}"
                    )
            evidence_summary = "\n---\n".join(evidence_lines) if evidence_lines else "No section text was successfully fetched in the prior turn."

            correction_msg = HumanMessage(
                content=build_correction_prompt(validation.blocked_reason or "", evidence_summary)
            )
            return {
                "messages": [correction_msg],
                "retry_count": state["retry_count"] + 1,
                "turn_context": tc.model_dump(),
                "validation_result": validation.model_dump(),
            }

        return {
            "validation_result": validation.model_dump(),
            "turn_context": tc.model_dump(),
        }

    def check_validation_next(state: AgentState) -> str:
        """Route after validation: retry or finish."""
        last = state["messages"][-1]
        if isinstance(last, HumanMessage) and "SYSTEM NOTICE" in last.content:
            return "llm"
        return END

    # ── Build the graph ────────────────────────────────────────────────

    workflow = StateGraph(AgentState)

    workflow.add_node("llm", call_llm)
    workflow.add_node("execute_tools", execute_tools)
    workflow.add_node("validate_safety", validate_safety)

    workflow.set_entry_point("llm")

    workflow.add_conditional_edges("llm", should_continue, {
        "execute_tools": "execute_tools",
        "validate_safety": "validate_safety",
    })
    workflow.add_conditional_edges("execute_tools", after_tools, {
        "llm": "llm",
        "validate_safety": "validate_safety",
    })
    workflow.add_conditional_edges("validate_safety", check_validation_next, {
        "llm": "llm",
        END: END,
    })

    return workflow.compile()


def _auto_populate_refs(tc: TurnContext, result: str) -> None:
    """Auto-populate topicRefs and graphicRefs from fetched sections and graphic IDs.

    Rebuilds both lists from scratch so repeated submissions stay idempotent
    (mirrors Kotlin's replace-on-parse semantics).
    """
    # Build topic refs from fetched sections
    topic_refs: list[TopicRef] = []
    seen_topics = set()
    for sec in tc.fetched_sections:
        key = (sec.topic_id, sec.section_id)
        if key in seen_topics:
            continue
        seen_topics.add(key)
        if not sec.section_title:
            continue
        topic_title = (
            tc.topic_titles.get(sec.topic_id)
            or sec.topic_title
            or sec.topic_id
        )
        topic_refs.append(
            TopicRef(
                topic_id=sec.topic_id,
                section_id=sec.section_id,
                label=sec.section_title,
                topic_title=topic_title,
            )
        )
    tc.structured_topic_refs = topic_refs

    # Build graphic refs from fetched graphic IDs
    tc.structured_graphic_refs = [
        GraphicRef(
            graphic_id=gid,
            label=tc.graphic_titles.get(gid, f"Graphic {gid}"),
        )
        for gid in tc.graphic_ids
    ]


def create_initial_state(
    user_message: str,
    system_prompt: str,
) -> dict:
    """Create initial agent state for a new turn."""
    return {
        "messages": [
            SystemMessage(content=system_prompt),
            HumanMessage(content=user_message),
        ],
        "system_prompt": system_prompt,
        "turn_context": TurnContext().model_dump(),
        "user_question": user_message,
        "retry_count": 0,
        "validation_result": None,
        "tool_rounds": 0,
    }
