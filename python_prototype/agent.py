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


class AgentState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    system_prompt: str
    turn_context: dict  # Serialized TurnContext
    user_question: str  # Original user question for number validation
    retry_count: int
    validation_result: Optional[dict]


def _build_tool_map(tools: list[BaseTool]) -> dict[str, Callable]:
    """Build name -> invoke mapping for manual tool execution."""
    return {t.name: t for t in tools}


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
        max_tool_retries: Max tool loop iterations (default 24, matching Kotlin)
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
        straight to validation to prevent double-submission.
        """
        tc = TurnContext(**state["turn_context"])
        if tc.tool_calls and tc.tool_calls[-1].tool_name == "submit_clinical_answer":
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
                tc.tool_calls[-1].success = False
            else:
                try:
                    raw = tool.invoke(args)
                    result_content = raw if isinstance(raw, str) else json.dumps(raw)
                except Exception as e:
                    result_content = json.dumps({"error": str(e)})
                    tc.tool_calls[-1].success = False

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
        }

    def validate_safety(state: AgentState) -> dict:
        """Run safety validation on the final answer."""
        last_message = state["messages"][-1]
        raw = last_message.content if hasattr(last_message, "content") else str(last_message)

        # Extract clean answer from terminal tool JSON
        answer = raw
        try:
            data = json.loads(raw)
            if data.get("status") == "SUBMITTED" and "answer" in data:
                answer = data["answer"]
        except (json.JSONDecodeError, TypeError):
            pass

        tc = TurnContext(**state["turn_context"])
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
                content=(
                    "SYSTEM NOTICE: Your prior response was paused due to clinical verification rules.\n"
                    f"REASON: {validation.blocked_reason}\n\n"
                    "EVIDENCE RETRIEVED IN THIS TURN:\n"
                    f"{evidence_summary}\n\n"
                    "REMEDIAL INSTRUCTIONS:\n"
                    "1. Re-evaluate your answer using ONLY the retrieved evidence above.\n"
                    "2. Ensure all quoted dosages and figures are verified against the retrieved sections.\n"
                    "3. Include full citations in format: Topic: <Title>, Section: <Title> (ID: <SectionID>)\n"
                    "4. Do NOT invent clinical quantities not present in the evidence.\n"
                    "5. MUST call submit_clinical_answer as your final tool call."
                )
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
    """Auto-populate topicRefs and graphicRefs from fetched sections and graphic IDs."""
    # Build topic refs from fetched sections
    seen_topics = set()
    for sec in tc.fetched_sections:
        key = (sec.topic_id, sec.section_id)
        if key not in seen_topics:
            seen_topics.add(key)
            topic_title = (
                tc.topic_titles.get(sec.topic_id)
                or sec.topic_title
                or sec.topic_id
            )
            if not sec.section_title:
                continue
            tc.structured_topic_refs.append(
                TopicRef(
                    topic_id=sec.topic_id,
                    section_id=sec.section_id,
                    label=sec.section_title,
                    topic_title=topic_title,
                )
            )

    # Build graphic refs from fetched graphic IDs
    for gid in tc.graphic_ids:
        tc.structured_graphic_refs.append(
            GraphicRef(
                graphic_id=gid,
                label=tc.graphic_titles.get(gid, f"Graphic {gid}"),
            )
        )


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
    }
