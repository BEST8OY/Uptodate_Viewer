r"""LangGraph clinical retrieval agent — mirrors KoogAgentFactory.kt.

State graph:
    nodeStart -> [LLM Request] <-> [Execute Tools] (tool loop)
                          \-> [Safety Validate] -> [Self-Correction] or [Finish]
"""

import json
import re
from typing import Annotated, Callable, Optional, TypedDict, Union

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import BaseTool
from langgraph.graph import END, StateGraph
from langgraph.graph.message import add_messages

from safety_validator import (
    FetchedSection,
    GraphicRef,
    PatientProfile,
    SafetyValidator,
    ToolCallRecord,
    TopicRef,
    TurnContext,
    ValidationResult,
)
from reasoning_sanitizer import strip_reasoning
from system_prompt import build_correction_prompt, build_premature_interception_prompt


class AgentState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    system_prompt: str
    turn_context: dict  # Serialized TurnContext
    user_question: str  # Original user question for number validation
    retry_count: int
    validation_result: Optional[dict]
    tool_rounds: int  # Number of execute_tools passes this turn
    premature_retries: int  # Number of premature interception retries


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
    max_premature_retries: int = 2,
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
        max_premature_retries: Max premature interception retries before routing to safety validation (default 2)
    """
    tool_map = _build_tool_map(tools)
    effective_premature_retries = min(max_premature_retries, max_safety_retries) if max_safety_retries == 0 else max_premature_retries

    def should_continue(state: AgentState) -> str:
        """Route: tool calls -> execute_tools, premature text -> intercept_premature, text -> validate_safety."""
        last_message = state["messages"][-1]
        if isinstance(last_message, AIMessage) and last_message.tool_calls:
            return "execute_tools"

        tc = TurnContext(**state["turn_context"])
        raw_text = last_message.content if isinstance(last_message, AIMessage) and last_message.content else ""
        clean_text = strip_reasoning(raw_text)
        tc.answer = clean_text
        tc.user_question = state.get("user_question", "")

        is_conversational = SafetyValidator.is_non_clinical_response(tc)
        has_evidence = len(tc.fetched_sections) > 0 or len(tc.graphic_ids) > 0
        has_tools = len(tc.tool_calls) > 0

        # Phase 1 premature interception: clinical response generated before any tools or evidence
        if not is_conversational and not has_tools and not has_evidence:
            if state.get("premature_retries", 0) < effective_premature_retries:
                return "intercept_premature"

        return "validate_safety"

    def after_tools(state: AgentState) -> str:
        """Route after tool execution: check retry budget, compact history, return to LLM."""
        if state.get("tool_rounds", 0) >= max_tool_retries:
            return "validate_safety"

        tc = TurnContext(**state["turn_context"])

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

            # Track candidate topic titles from search_topics
            if name == "search_topics":
                try:
                    search_data = json.loads(result_content)
                    for item in search_data.get("results", []):
                        tid = str(item.get("id", "")).strip()
                        title = str(item.get("title", "")).strip()
                        if tid and title and not title.isdigit():
                            tc.topic_titles[tid] = title
                except (json.JSONDecodeError, TypeError):
                    pass

            # Track related topic titles
            if name == "get_related_topics":
                try:
                    rel_data = json.loads(result_content)
                    for item in rel_data.get("relatedTopics", rel_data.get("related_topics", [])):
                        tid = str(item.get("id", item.get("topicId", ""))).strip()
                        title = str(item.get("title", "")).strip()
                        if tid and title and not title.isdigit():
                            tc.topic_titles[tid] = title
                except (json.JSONDecodeError, TypeError):
                    pass

            # Track topic overview bundling
            if name in ("get_topic_overview", "getTopicOverview"):
                try:
                    overview_data = json.loads(result_content)
                    topic_id = str(
                        overview_data.get("topicId", overview_data.get("topic_id", args.get("topic_id", args.get("topicId", ""))))
                    ).strip()
                    topic_title = overview_data.get("title", overview_data.get("topicTitle", overview_data.get("topic_title", "")))
                    if topic_id and topic_title and not str(topic_title).isdigit():
                        tc.topic_titles[topic_id] = topic_title

                    # Outline sections
                    sections = overview_data.get("sections", overview_data.get("outline", []))
                    if topic_id and sections:
                        tc.outline_sections[topic_id] = {
                            s["id"]: s["title"].lstrip("-–— ") for s in sections if s.get("id")
                        }

                    # Graphics mapping
                    graphics = overview_data.get("graphics", [])
                    for g in graphics:
                        gid = str(g.get("id", "")).strip()
                        if gid:
                            tc.graphic_to_topic[gid] = topic_id
                            clean_gid = re.sub(r"(?i)^graphic-", "", gid)
                            tc.graphic_to_topic[clean_gid] = topic_id
                            g_title = g.get("title", "")
                            if g_title:
                                tc.graphic_titles[gid] = g_title
                                tc.graphic_titles[clean_gid] = g_title

                    # Calculator topic evidence
                    if overview_data.get("topicType") == "calc" or overview_data.get("topic_type") == "calc":
                        full_md = overview_data.get("markdown", "")
                        tc.fetched_sections.append(
                            FetchedSection(
                                topic_id=topic_id,
                                topic_title=tc.topic_titles.get(topic_id, topic_title),
                                section_id="FULL",
                                section_title="Calculator Tool Content",
                                content_snippet=full_md[:2000],
                            )
                        )
                except (json.JSONDecodeError, AttributeError, TypeError):
                    pass

            # Track topic titles and section titles from outline
            if name == "get_topic_outline":
                try:
                    outline_data = json.loads(result_content)
                    topic_title = outline_data.get("title", "")
                    topic_id = outline_data.get("topicId", args.get("topic_id", args.get("topicId", "")))
                    if topic_id and topic_title and not str(topic_title).isdigit():
                        tc.topic_titles[topic_id] = topic_title
                    # Store full section ID → title map (strip leading outline dashes)
                    sections = outline_data.get("sections", [])
                    if topic_id and sections:
                        tc.outline_sections[topic_id] = {
                            s["id"]: s["title"].lstrip("-–— ") for s in sections if s.get("id")
                        }
                    # Store graphics mapping to this topic
                    graphics = outline_data.get("graphics", [])
                    for g in graphics:
                        gid = str(g.get("id", "")).strip()
                        if gid:
                            tc.graphic_to_topic[gid] = topic_id
                            clean_gid = re.sub(r"(?i)^graphic-", "", gid)
                            tc.graphic_to_topic[clean_gid] = topic_id
                            g_title = g.get("title", "")
                            if g_title:
                                tc.graphic_titles[gid] = g_title
                                tc.graphic_titles[clean_gid] = g_title
                except (json.JSONDecodeError, AttributeError):
                    pass

            # Batch section tracking
            if name == "get_topic_sections_text":
                topic_id = str(args.get("topic_id", args.get("topicId", ""))).strip()
                # Parse structured JSON result from batch tool
                try:
                    batch_data = json.loads(result_content)
                    resp_tid = str(batch_data.get("topicId", "")).strip()
                    if resp_tid:
                        topic_id = resp_tid
                    topic_title = batch_data.get("topicTitle", "")
                    raw_section_titles = batch_data.get("sectionTitles", {})
                    section_titles = (
                        {k: v.lstrip("-–— ") for k, v in raw_section_titles.items()}
                        if isinstance(raw_section_titles, dict)
                        else {}
                    )
                    if topic_id and topic_title and not str(topic_title).isdigit():
                        tc.topic_titles[topic_id] = topic_title
                    if topic_id and section_titles:
                        tc.outline_sections[topic_id] = section_titles
                except (json.JSONDecodeError, TypeError):
                    section_titles = {}

                # If topic title is still missing or all digits, resolve from DB
                if topic_id and (topic_id not in tc.topic_titles or str(tc.topic_titles[topic_id]).isdigit()):
                    try:
                        from tools import _db
                        if _db is not None:
                            db_title = _db.get_topic_title(topic_id)
                            if db_title and not str(db_title).isdigit():
                                tc.topic_titles[topic_id] = db_title
                    except Exception:
                        pass
                # Use stored outline sections for titles (fallback to parsed data)
                section_map = tc.outline_sections.get(topic_id, section_titles)
                # Accept singular alias forms too (parity with Kotlin accumulator: sectionId/section_id)
                section_ids = (
                    args.get("section_ids") or args.get("sectionIds")
                    or args.get("section_id") or args.get("sectionId") or []
                )
                if isinstance(section_ids, str):
                    section_ids = [section_ids]
                for sid in section_ids:
                    tc.fetched_sections.append(
                        FetchedSection(
                            topic_id=topic_id,
                            topic_title=tc.topic_titles.get(topic_id, ""),
                            section_id=sid,
                            section_title=section_map.get(sid, "").lstrip("-–— "),
                            content_snippet=result_content[:2000],
                        )
                    )

            # Track graphic IDs and titles
            if name == "get_graphic_content":
                graphic_id = str(args.get("graphic_id", args.get("graphicId", ""))).strip()
                if graphic_id:
                    clean_gid = re.sub(r"(?i)^graphic-", "", graphic_id)
                    if clean_gid not in tc.graphic_ids:  # insertion-ordered, dedup across remediation turns
                        tc.graphic_ids.append(clean_gid)
                    # Extract title from result: "### Graphic Table: {title}\n\n{markdown}"
                    m = re.match(r"### Graphic Table:\s*(.+)", result_content)
                    if m:
                        title = m.group(1).strip()
                        tc.graphic_titles[graphic_id] = title
                        tc.graphic_titles[clean_gid] = title

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
        """Resolve the turn's final answer from the last plain-text AI message."""
        for msg in reversed(state["messages"]):
            if isinstance(msg, AIMessage) and msg.content and not msg.tool_calls:
                return strip_reasoning(msg.content)
        return ""

    def validate_safety(state: AgentState) -> dict:
        """Run safety validation on the final answer."""
        tc = TurnContext(**state["turn_context"])
        answer = _extract_final_answer(state, tc)

        # Ensure last AIMessage has reasoning stripped
        for msg in reversed(state["messages"]):
            if isinstance(msg, AIMessage) and msg.content and not msg.tool_calls:
                msg.content = answer
                break

        tc.answer = answer
        tc.user_question = state.get("user_question", "")

        # Auto-populate refs from retrieved evidence
        _auto_populate_refs(tc)

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

            # Reset answer state for correction turn (mirrors Kotlin prepareForCorrection)
            tc.answer = ""
            tc.structured_topic_refs = []
            tc.structured_graphic_refs = []

            correction_msg = HumanMessage(
                content=build_correction_prompt(validation.blocked_reason or "", evidence_summary)
            )
            return {
                "messages": [correction_msg],
                "retry_count": state["retry_count"] + 1,
                "turn_context": tc.model_dump(),
                "validation_result": validation.model_dump(),
            }

        # Terminal state: if validation blocked after max retries, emit blocked message (mirrors Kotlin nodeSafetyBlock)
        if not validation.passed:
            blocked_msg = AIMessage(
                content=f"Clinical Response Verification Blocked: {validation.blocked_reason}"
            )
            return {
                "messages": [blocked_msg],
                "validation_result": validation.model_dump(),
                "turn_context": tc.model_dump(),
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

    def intercept_premature(state: AgentState) -> dict:
        """Intercept premature response before tools were executed on clinical inquiry.

        Injects interception prompt directive and returns to LLM (mirrors Kotlin nodeInterceptPremature).
        """
        current_retries = state.get("premature_retries", 0) + 1
        prompt = build_premature_interception_prompt(str(current_retries))
        return {
            "messages": [HumanMessage(content=prompt)],
            "premature_retries": current_retries,
        }

    # ── Build the graph ────────────────────────────────────────────────

    workflow = StateGraph(AgentState)

    workflow.add_node("llm", call_llm)
    workflow.add_node("execute_tools", execute_tools)
    workflow.add_node("intercept_premature", intercept_premature)
    workflow.add_node("validate_safety", validate_safety)

    workflow.set_entry_point("llm")

    workflow.add_conditional_edges("llm", should_continue, {
        "execute_tools": "execute_tools",
        "intercept_premature": "intercept_premature",
        "validate_safety": "validate_safety",
    })
    workflow.add_edge("intercept_premature", "llm")
    workflow.add_conditional_edges("execute_tools", after_tools, {
        "llm": "llm",
        "validate_safety": "validate_safety",
    })
    workflow.add_conditional_edges("validate_safety", check_validation_next, {
        "llm": "llm",
        END: END,
    })

    return workflow.compile()


def _auto_populate_refs(tc: TurnContext, result: str = "") -> None:
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
        sec_title = sec.section_title or tc.outline_sections.get(sec.topic_id, {}).get(sec.section_id, "")
        if not sec_title:
            continue
        raw_title = tc.topic_titles.get(sec.topic_id) or sec.topic_title or ""
        if not raw_title or str(raw_title).isdigit():
            try:
                from tools import _db
                if _db is not None:
                    db_title = _db.get_topic_title(sec.topic_id)
                    if db_title and not str(db_title).isdigit():
                        raw_title = db_title
            except Exception:
                pass
        topic_title = raw_title if raw_title and not str(raw_title).isdigit() else sec.topic_id
        label = sec_title.lstrip("-–— ")
        clean_sec_id = "" if sec.section_id.upper() == "FULL" else sec.section_id
        topic_refs.append(
            TopicRef(
                topic_id=sec.topic_id,
                section_id=clean_sec_id,
                label=label,
                topic_title=topic_title,
            )
        )

    # Ensure parent topics for graphic tables are attributed
    for gid in tc.graphic_ids:
        clean_gid = re.sub(r"(?i)^graphic-", "", gid)
        parent_topic_id = tc.graphic_to_topic.get(gid) or tc.graphic_to_topic.get(clean_gid)
        if parent_topic_id and not any(r.topic_id == parent_topic_id for r in topic_refs):
            raw_title = tc.topic_titles.get(parent_topic_id) or ""
            if not raw_title or str(raw_title).isdigit():
                try:
                    from tools import _db
                    if _db is not None:
                        db_title = _db.get_topic_title(parent_topic_id)
                        if db_title and not str(db_title).isdigit():
                            raw_title = db_title
                except Exception:
                    pass
            topic_title = raw_title if raw_title and not str(raw_title).isdigit() else parent_topic_id
            topic_refs.append(
                TopicRef(
                    topic_id=parent_topic_id,
                    section_id="",
                    label=topic_title,
                    topic_title=topic_title,
                )
            )

    tc.structured_topic_refs = topic_refs

    # Build graphic refs from fetched graphic IDs
    tc.structured_graphic_refs = [
        GraphicRef(
            graphic_id=gid,
            label=tc.graphic_titles.get(gid, f"Graphic {gid}"),
            topic_id=tc.graphic_to_topic.get(gid) or tc.graphic_to_topic.get(re.sub(r"(?i)^graphic-", "", gid)),
        )
        for gid in tc.graphic_ids
    ]


def create_initial_state(
    user_message: str,
    system_prompt: str,
    patient_profile: Optional[Union[PatientProfile, dict]] = None,
) -> dict:
    """Create initial agent state for a new turn.

    patient_profile (optional dict with age/sex/conditions/medications/allergies/notes)
    is wired into TurnContext so the deterministic Tier-1 patient bounds (renal & allergy)
    can fire in-graph — parity with KoogAgentFactory.accumulator.setPatientProfile().
    """
    if isinstance(patient_profile, dict):
        patient_profile = PatientProfile(**patient_profile) if patient_profile else None
    return {
        "messages": [
            SystemMessage(content=system_prompt),
            HumanMessage(content=user_message),
        ],
        "system_prompt": system_prompt,
        "turn_context": TurnContext(patient_profile=patient_profile).model_dump(),
        "user_question": user_message,
        "retry_count": 0,
        "validation_result": None,
        "tool_rounds": 0,
        "premature_retries": 0,
    }
