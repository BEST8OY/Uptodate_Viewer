r"""LangGraph clinical retrieval agent — mirrors KoogAgentFactory.kt & ClinicalAgentStrategy.kt.

Two-Phase Isolated Graph Architecture:
  Phase 1: node_evidence_gathering (with retrieval tools) <-> execute_tools
                                    |
                                    v
           validate_evidence (Dual Gate 1: Pre-Synthesis Evidence Gate)
                                    |
                                    v
     [Near 128k token headroom?] -> node_compress_history
                                    |
                                    v
  Phase 2: node_clinical_synthesis (ZERO database tools bound in model schema)
                                    |
                                    v
           validate_safety (Dual Gate 2: Post-Synthesis Safety Gate) -> Finish or Targeted Remediation
"""

import json
import re
from typing import Annotated, Any, Callable, Optional, TypedDict, Union

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, RemoveMessage, SystemMessage, ToolMessage
from langchain_core.tools import BaseTool
from langgraph.graph import END, StateGraph
from langgraph.graph.message import add_messages

from reasoning_sanitizer import strip_reasoning
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


def estimate_tokens(text: str) -> int:
    """Token estimation mirroring Koog SimpleRegexBasedTokenizer."""
    if not text:
        return 0
    return len(re.findall(r"\w+|[^\w\s]", text))


def estimate_messages_tokens(messages: list[BaseMessage]) -> int:
    """Estimate total tokens across a list of LangChain messages."""
    total = 0
    for msg in messages:
        content = msg.content if isinstance(msg.content, str) else str(msg.content)
        total += estimate_tokens(content)
        if hasattr(msg, "tool_calls") and msg.tool_calls:
            for tc in msg.tool_calls:
                total += estimate_tokens(tc.get("name", ""))
                total += estimate_tokens(json.dumps(tc.get("args", {})))
    return total


def is_near_token_limit(messages: list[BaseMessage], threshold: int = 128000) -> bool:
    """Evaluates whether message context exceeds 85% of active threshold."""
    return estimate_messages_tokens(messages) >= int(threshold * 0.85)


def compress_conversation_history(
    messages: list[BaseMessage],
    patient_profile: Optional[Union[PatientProfile, dict]] = None,
) -> list[BaseMessage]:
    """Semantic history compression preserving patient profile, allergies, and citations.

    Mirrors Koog's native semantic history compression (nodeLLMCompressHistory).
    Preserves:
    - Patient Profile demographics / constraints
    - Documented Allergies (never discarded or compressed away)
    - Primary Clinical Inquiries
    - Key Verified Topics & References
    """
    if len(messages) <= 2:
        return list(messages)

    sys_msgs = [m for m in messages if isinstance(m, SystemMessage)]
    non_sys = [m for m in messages if not isinstance(m, SystemMessage)]

    if len(non_sys) <= 2:
        return list(messages)

    older_msgs = non_sys[:-2]
    recent_msgs = non_sys[-2:]

    inquiries = []
    topics_mentioned = set()
    for m in older_msgs:
        if isinstance(m, HumanMessage) and m.content:
            text = str(m.content).strip()
            if not text.startswith("SYSTEM NOTICE:"):
                inquiries.append(text[:120])
        elif isinstance(m, ToolMessage) and m.content:
            try:
                data = json.loads(m.content)
                if isinstance(data, dict):
                    if "topicTitle" in data and data["topicTitle"]:
                        topics_mentioned.add(data["topicTitle"])
                    elif "title" in data and data["title"]:
                        topics_mentioned.add(data["title"])
            except Exception:
                pass

    summary_lines = ["=== CONVERSATION HISTORY SUMMARY (TLDR) ==="]

    profile_dict = patient_profile.model_dump() if hasattr(patient_profile, "model_dump") else (patient_profile or {})
    if profile_dict:
        demo_parts = []
        if profile_dict.get("age"):
            demo_parts.append(f"Age {profile_dict['age']}")
        if profile_dict.get("sex"):
            demo_parts.append(str(profile_dict['sex']))
        if profile_dict.get("conditions"):
            demo_parts.append(f"Conditions: {', '.join(profile_dict['conditions'])}")
        if demo_parts:
            summary_lines.append(f"Patient Demographics: {', '.join(demo_parts)}")

        allergies = profile_dict.get("allergies", [])
        if allergies:
            alg_str = ", ".join(allergies) if isinstance(allergies, list) else str(allergies)
            summary_lines.append(f"Documented Allergies: {alg_str} (STRICT CONTRAINDICATION)")

        medications = profile_dict.get("medications", [])
        if medications:
            med_str = ", ".join(medications) if isinstance(medications, list) else str(medications)
            summary_lines.append(f"Active Medications: {med_str}")

        notes = profile_dict.get("notes")
        if notes:
            summary_lines.append(f"Clinical Notes & Renal Markers: {notes}")

    if inquiries:
        summary_lines.append("Prior Inquiries: " + "; ".join(inquiries[-4:]))

    if topics_mentioned:
        summary_lines.append("Verified Topics & References: " + ", ".join(sorted(topics_mentioned)[:5]))

    summary_lines.append("===========================================")
    summary_text = "\n".join(summary_lines)

    compressed_human_msg = HumanMessage(content=summary_text)
    return sys_msgs + [compressed_human_msg] + recent_msgs


def _build_evidence_summary(sections: list[FetchedSection]) -> str:
    """Evidence digest shared by validation and synthesis prompt assembly."""
    evidence_lines = []
    for sec in sections:
        if sec.content_snippet:
            evidence_lines.append(
                f"Section [{sec.section_id}] ({sec.section_title}):\n{sec.content_snippet}"
            )
        else:
            evidence_lines.append(
                f"Section [{sec.section_id}] ({sec.section_title}) from topic {sec.topic_id}"
            )
    return "\n---\n".join(evidence_lines) if evidence_lines else "No section text was successfully fetched in the prior turn."


def format_synthesis_prompt(pending_input: str, tc: TurnContext) -> str:
    """Format synthesis directives injecting silent clinical triage, active patient profile
    contraindications, and verified evidence summary. Parity with Kotlin ClinicalAgentStrategy.formatSynthesisPrompt.
    """
    if SafetyValidator.is_non_clinical_response(tc) and not tc.fetched_sections:
        if pending_input.strip():
            return f"Please provide a helpful, courteous greeting or non-clinical response addressing: {pending_input}"
        else:
            return "Please provide a helpful, courteous greeting to the clinician and offer clinical assistance."

    if pending_input.startswith("SYSTEM NOTICE"):
        return pending_input

    patient_block = ""
    patient_profile = tc.patient_profile
    if patient_profile:
        from system_prompt import extract_patient_context
        patient_dict = patient_profile.model_dump() if hasattr(patient_profile, "model_dump") else patient_profile
        patient_block = extract_patient_context(patient_dict) or ""

    patient_profile_section = patient_block if patient_block.strip() else "No active patient profile or documented contraindications."
    evidence_summary = _build_evidence_summary(tc.fetched_sections)

    lines = [
        "CLINICAL SYNTHESIS DIRECTIVE (CLOSED-BOOK MODE — NO TOOLS ACTIVE):",
        "Synthesize a definitive, point-of-care clinical response for the inquiry below using ONLY the `<retrieved_evidence>` block and `<patient_profile>` constraints.\n",
        "<patient_profile>",
        patient_profile_section,
        "</patient_profile>\n",
        "<retrieved_evidence>",
        evidence_summary,
        "</retrieved_evidence>\n",
        "MANDATORY SYNTHESIS & VERIFICATION RULES:",
        "1. SILENT CLINICAL TRIAGE: Before writing, verify the candidate regimen against `<patient_profile>` allergies, renal markers (eGFR/CrCl), hepatic status, and active medications.",
        "2. ALLERGY EXCLUSION: If a first-line drug or drug class conflicts with a documented patient allergy in `<patient_profile>`, select a safe alternative from `<retrieved_evidence>`. Do NOT recommend or name the contraindicated drug as an option.",
        "3. VERBATIM QUANTITY MATCHING: Every clinical number, dosage, percentage, range, and lab threshold in your response must match an exact number and unit string inside `<retrieved_evidence>` (or echo the patient's own documented profile values). Do NOT convert units (e.g., g to mg) or calculate weight-multiplied doses not explicitly written in the text. If a number is absent from `<retrieved_evidence>`, write \"not specified in retrieved sections.\"",
        "4. POINT-OF-CARE STRUCTURE:",
        "   - Lead with a 1–2 sentence **bolded Bottom-Line Up Front (BLUF)** giving the exact patient-tailored regimen.",
        "   - Follow with concise bullet points under these three exact headers:",
        "     * **First-Line Regimen & Dosing**",
        "     * **Organ Function & Adjustments**",
        "     * **Contraindications & Critical Warnings**",
        "   - Write plain text only (no markdown links, no reference/source lists at the end).",
    ]

    if pending_input and not pending_input.startswith("CLINICAL SYNTHESIS DIRECTIVE"):
        lines.append("")
        lines.append("CLINICAL INQUIRY:")
        lines.append(pending_input)

    return "\n".join(lines)


def create_clinical_agent(
    llm: Any = None,
    tools: Optional[list[BaseTool]] = None,
    system_prompt: str = "",
    callbacks: Optional[list] = None,
    max_tool_retries: int = 24,
    max_safety_retries: int = 2,
    max_premature_retries: int = 2,
    llm_with_tools: Any = None,
    llm_tool_free: Any = None,
    history_compression_threshold: int = 128000,
):
    """Create the phase-isolated clinical retrieval agent graph.

    Args:
        llm: Base LangChain chat model used to derive tool-bound and tool-free models if not passed individually
        tools: List of tool functions
        system_prompt: System prompt text
        callbacks: List of callback handlers (e.g., TokenTracker)
        max_tool_retries: Max execute_tools rounds before routing to validation (default 24)
        max_safety_retries: Max self-correction retries (default 2)
        max_premature_retries: Max premature interception retries before routing to safety validation (default 2)
        llm_with_tools: LangChain chat model with tools bound (Phase 1: Evidence Gathering)
        llm_tool_free: LangChain chat model with zero tools bound (Phase 2: Clinical Synthesis)
        history_compression_threshold: Token threshold for semantic history compression (default 128000)
    """
    if tools is None:
        tools = []

    if llm_with_tools is None:
        if llm is not None:
            if tools and hasattr(llm, "bind_tools"):
                try:
                    llm_with_tools = llm.bind_tools(tools)
                except Exception:
                    llm_with_tools = llm
            else:
                llm_with_tools = llm
        else:
            raise ValueError("Either llm or llm_with_tools must be provided")

    if llm_tool_free is None:
        llm_tool_free = llm if llm is not None else llm_with_tools

    tool_map = _build_tool_map(tools)
    effective_premature_retries = min(max_premature_retries, max_safety_retries) if max_safety_retries == 0 else max_premature_retries

    def _extract_final_answer(state: AgentState, tc: TurnContext) -> str:
        """Resolve the turn's final answer from the last plain-text AI message."""
        for msg in reversed(state["messages"]):
            if isinstance(msg, AIMessage) and msg.content and not msg.tool_calls:
                return strip_reasoning(msg.content)
        return ""

    # ── Phase 1: Evidence Gathering ────────────────────────────────────

    def node_evidence_gathering(state: AgentState) -> dict:
        """Phase 1: Evidence Gathering using llm_with_tools."""
        config = {"callbacks": callbacks} if callbacks else {}
        response = llm_with_tools.invoke(state["messages"], config=config)
        return {"messages": [response]}

    def route_after_evidence_gathering(state: AgentState) -> str:
        """Route from evidence gathering: tool_calls -> execute_tools, premature -> intercept_premature, else -> validate_evidence."""
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

        return "validate_evidence"

    def intercept_premature(state: AgentState) -> dict:
        """Intercept premature response before tools were executed on clinical inquiry."""
        current_retries = state.get("premature_retries", 0) + 1
        prompt = build_premature_interception_prompt(str(current_retries))
        return {
            "messages": [HumanMessage(content=prompt)],
            "premature_retries": current_retries,
        }

    def execute_tools(state: AgentState) -> dict:
        """Execute requested tool calls with deduplication, strongly-typed Pydantic serialization,
        and direct attribute reading.
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
                raw = None
            else:
                success = True
                try:
                    raw = tool.invoke(args)
                    if hasattr(raw, "model_dump_json"):
                        result_content = raw.model_dump_json()
                    elif isinstance(raw, str):
                        result_content = raw
                    else:
                        try:
                            result_content = json.dumps(raw)
                        except TypeError:
                            result_content = str(raw)
                except Exception as e:
                    result_content = json.dumps({"error": str(e)})
                    success = False
                    raw = None

            # Tool success evaluation without obsolete _is_logical_failure helper
            if success and raw is not None:
                has_error = bool(getattr(raw, "error", None))
                if not has_error and isinstance(raw, dict):
                    has_error = bool(raw.get("error"))
                is_empty_sections = (
                    name == "get_topic_sections_text"
                    and not getattr(raw, "markdown", (raw.get("markdown") if isinstance(raw, dict) else ""))
                    and bool(getattr(raw, "invalid_sections", getattr(raw, "invalidSections", (raw.get("invalidSections") if isinstance(raw, dict) else []))))
                )
                if has_error or is_empty_sections:
                    success = False

            tc.tool_calls[-1].success = success
            tc.tool_calls[-1].result = result_content
            tc.tool_results.append(result_content)

            # Track candidate topic titles from search_topics
            if name == "search_topics":
                results = getattr(raw, "results", None)
                if results is None and isinstance(raw, dict):
                    results = raw.get("results", [])
                for item in (results or []):
                    tid = str(getattr(item, "id", item.get("id") if isinstance(item, dict) else "")).strip()
                    title = str(getattr(item, "title", item.get("title") if isinstance(item, dict) else "")).strip()
                    if tid and title and not title.isdigit():
                        tc.topic_titles[tid] = title

            # Track related topic titles
            if name == "get_related_topics":
                rel_topics = getattr(raw, "relatedTopics", getattr(raw, "related_topics", None))
                if rel_topics is None and isinstance(raw, dict):
                    rel_topics = raw.get("relatedTopics", raw.get("related_topics", []))
                for item in (rel_topics or []):
                    tid = str(getattr(item, "id", item.get("id", item.get("topicId")) if isinstance(item, dict) else "")).strip()
                    title = str(getattr(item, "title", item.get("title") if isinstance(item, dict) else "")).strip()
                    if tid and title and not title.isdigit():
                        tc.topic_titles[tid] = title

            # Track topic overview bundling
            if name in ("get_topic_overview", "getTopicOverview"):
                topic_id = str(
                    getattr(raw, "topicId", getattr(raw, "topic_id", (raw.get("topicId", raw.get("topic_id")) if isinstance(raw, dict) else args.get("topic_id", args.get("topicId", "")))))
                ).strip()
                topic_title = str(
                    getattr(raw, "title", getattr(raw, "topic_title", (raw.get("title", raw.get("topic_title")) if isinstance(raw, dict) else "")))
                ).strip()
                if not topic_id:
                    topic_id = str(args.get("topic_id", args.get("topicId", ""))).strip()
                if topic_id and topic_title and not topic_title.isdigit():
                    tc.topic_titles[topic_id] = topic_title

                # Outline sections
                sections = getattr(raw, "sections", (raw.get("sections", raw.get("outline", [])) if isinstance(raw, dict) else []))
                if topic_id and sections:
                    tc.outline_sections[topic_id] = {
                        str(getattr(s, "id", s.get("id") if isinstance(s, dict) else "")): str(getattr(s, "title", s.get("title") if isinstance(s, dict) else "")).lstrip("-–— ")
                        for s in sections
                        if (getattr(s, "id", None) or (isinstance(s, dict) and s.get("id")))
                    }

                # Graphics mapping
                graphics = getattr(raw, "graphics", (raw.get("graphics", []) if isinstance(raw, dict) else []))
                for g in (graphics or []):
                    gid = str(getattr(g, "id", g.get("id") if isinstance(g, dict) else "")).strip()
                    if gid:
                        tc.graphic_to_topic[gid] = topic_id
                        clean_gid = re.sub(r"(?i)^graphic-", "", gid)
                        tc.graphic_to_topic[clean_gid] = topic_id
                        g_title = getattr(g, "title", g.get("title") if isinstance(g, dict) else "")
                        if g_title:
                            tc.graphic_titles[gid] = str(g_title)
                            tc.graphic_titles[clean_gid] = str(g_title)

                # Calculator topic evidence
                topic_type = getattr(raw, "topicType", getattr(raw, "topic_type", (raw.get("topicType", raw.get("topic_type")) if isinstance(raw, dict) else "")))
                if topic_type == "calc":
                    full_md = getattr(raw, "markdown", (raw.get("markdown", "") if isinstance(raw, dict) else ""))
                    tc.fetched_sections.append(
                        FetchedSection(
                            topic_id=topic_id,
                            topic_title=tc.topic_titles.get(topic_id, topic_title),
                            section_id="FULL",
                            section_title="Calculator Tool Content",
                            content_snippet=full_md[:2000],
                        )
                    )

            # Batch section tracking
            if name == "get_topic_sections_text":
                resp_tid = str(getattr(raw, "topicId", getattr(raw, "topic_id", (raw.get("topicId", raw.get("topic_id")) if isinstance(raw, dict) else "")))).strip()
                topic_id = resp_tid or str(args.get("topic_id", args.get("topicId", ""))).strip()
                topic_title = getattr(raw, "topicTitle", getattr(raw, "topic_title", (raw.get("topicTitle", raw.get("topic_title")) if isinstance(raw, dict) else "")))
                raw_section_titles = getattr(raw, "sectionTitles", getattr(raw, "section_titles", (raw.get("sectionTitles", raw.get("section_titles")) if isinstance(raw, dict) else {})))
                section_titles = (
                    {str(k): str(v).lstrip("-–— ") for k, v in raw_section_titles.items()}
                    if isinstance(raw_section_titles, dict)
                    else {}
                )
                if topic_id and topic_title and not str(topic_title).isdigit():
                    tc.topic_titles[topic_id] = str(topic_title)
                if topic_id and section_titles:
                    tc.outline_sections[topic_id] = section_titles

                # DB title fallback if still missing or numeric
                if topic_id and (topic_id not in tc.topic_titles or str(tc.topic_titles[topic_id]).isdigit()):
                    try:
                        from tools import _db
                        if _db is not None:
                            db_title = _db.get_topic_title(topic_id)
                            if db_title and not str(db_title).isdigit():
                                tc.topic_titles[topic_id] = db_title
                    except Exception:
                        pass

                section_map = tc.outline_sections.get(topic_id, section_titles)
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

            # Graphic tracking
            if name == "get_graphic_content":
                graphic_id = str(args.get("graphic_id", args.get("graphicId", ""))).strip()
                if graphic_id:
                    clean_gid = re.sub(r"(?i)^graphic-", "", graphic_id)
                    if clean_gid not in tc.graphic_ids:
                        tc.graphic_ids.append(clean_gid)
                    title = getattr(raw, "title", (raw.get("title") if isinstance(raw, dict) else ""))
                    if not title:
                        m = re.match(r"### Graphic Table:\s*(.+)", result_content)
                        if m:
                            title = m.group(1).strip()
                    if title:
                        tc.graphic_titles[graphic_id] = str(title)
                        tc.graphic_titles[clean_gid] = str(title)

            new_messages.append(
                ToolMessage(content=result_content, tool_call_id=tool_call_id)
            )

        return {
            "messages": new_messages,
            "turn_context": tc.model_dump(),
            "user_question": state.get("user_question", ""),
            "tool_rounds": state.get("tool_rounds", 0) + 1,
        }

    def route_after_execute_tools(state: AgentState) -> str:
        """Route after tool execution: check round budget, proceed to evidence validation or loop."""
        if state.get("tool_rounds", 0) >= max_tool_retries:
            return "validate_evidence"
        return "node_evidence_gathering"

    # ── Dual Gate 1: Pre-Synthesis Evidence Gate ───────────────────────

    def validate_evidence(state: AgentState) -> dict:
        """Dual Gate 1: Pre-Synthesis Evidence Validation Gate.
        Checks Rule 0 (tool execution) & Rule 1 (fetched sections or table graphics).
        """
        tc = TurnContext(**state["turn_context"])
        answer = _extract_final_answer(state, tc)
        clean_text = strip_reasoning(answer)
        tc.answer = clean_text
        tc.user_question = state.get("user_question", "")

        is_greeting = SafetyValidator.is_non_clinical_response(tc)
        has_evidence = len(tc.fetched_sections) > 0 or len(tc.graphic_ids) > 0
        has_tools = len(tc.tool_calls) > 0

        if is_greeting and not has_tools and not has_evidence:
            return {
                "validation_result": ValidationResult(passed=True, warnings=[]).model_dump(),
                "turn_context": tc.model_dump(),
            }

        if not has_tools or not has_evidence:
            blocked_reason = (
                "Clinical recommendations require database verification. No database tools were executed."
                if not has_tools
                else "Answer requires section or table content retrieval. Only topic outlines or searches were performed."
            )
            if state["retry_count"] >= max_safety_retries:
                blocked_msg = AIMessage(
                    content=f"Clinical Response Verification Blocked: {blocked_reason}"
                )
                return {
                    "messages": [blocked_msg],
                    "validation_result": ValidationResult(passed=False, blocked_reason=blocked_reason, warnings=[]).model_dump(),
                    "turn_context": tc.model_dump(),
                }
            else:
                evidence_summary = _build_evidence_summary(tc.fetched_sections)
                tc.answer = ""
                tc.structured_topic_refs = []
                tc.structured_graphic_refs = []
                correction_msg = HumanMessage(
                    content=build_correction_prompt(blocked_reason, evidence_summary)
                )
                return {
                    "messages": [correction_msg],
                    "retry_count": state["retry_count"] + 1,
                    "turn_context": tc.model_dump(),
                    "validation_result": ValidationResult(passed=False, blocked_reason=blocked_reason, warnings=[]).model_dump(),
                }

        return {
            "validation_result": ValidationResult(passed=True, warnings=[]).model_dump(),
            "turn_context": tc.model_dump(),
        }

    def route_after_validate_evidence(state: AgentState) -> str:
        """Route from Dual Gate 1: loop back to evidence gathering, terminal block, or proceed to synthesis."""
        val = state.get("validation_result") or {}
        if not val.get("passed"):
            last_msg = state["messages"][-1]
            if isinstance(last_msg, AIMessage) and last_msg.content.startswith("Clinical Response Verification Blocked:"):
                return "safety_block"
            return "node_evidence_gathering"

        # Check token headroom before clinical synthesis
        if is_near_token_limit(state["messages"], threshold=history_compression_threshold):
            return "node_compress_history"
        return "node_clinical_synthesis"

    # ── History Compression ───────────────────────────────────────────

    def node_compress_history(state: AgentState) -> dict:
        """Perform semantic history compression preserving patient profile and citations."""
        tc = TurnContext(**state["turn_context"])
        non_sys = [m for m in state["messages"] if not isinstance(m, SystemMessage)]
        if len(non_sys) <= 2:
            return {}

        older_msgs = non_sys[:-2]
        compressed_messages = compress_conversation_history(
            state["messages"],
            patient_profile=tc.patient_profile,
        )
        summary_message = next(
            (m for m in compressed_messages if isinstance(m, HumanMessage) and "CONVERSATION HISTORY SUMMARY" in str(m.content)),
            None,
        )
        if not summary_message:
            return {}

        return {"messages": [RemoveMessage(id=m.id) for m in older_msgs if getattr(m, "id", None)] + [summary_message]}

    # ── Phase 2: Clinical Synthesis ────────────────────────────────────

    def node_clinical_synthesis(state: AgentState) -> dict:
        """Phase 2: Clinical Synthesis using llm_tool_free (zero tools bound in model schema)."""
        tc = TurnContext(**state["turn_context"])
        last_message = state["messages"][-1]
        is_remediation = isinstance(last_message, HumanMessage) and str(last_message.content).startswith("SYSTEM NOTICE:")

        pending_text = ""
        if is_remediation:
            pending_text = str(last_message.content)
        else:
            for msg in reversed(state["messages"]):
                if isinstance(msg, AIMessage) and msg.content and not msg.tool_calls:
                    pending_text = strip_reasoning(msg.content)
                    break

        is_greeting = (
            SafetyValidator.is_non_clinical_response(tc)
            and not tc.fetched_sections
            and not tc.tool_calls
        )

        # Non-clinical greeting already rendered
        if is_greeting and pending_text and not is_remediation:
            return {}

        synthesis_prompt = format_synthesis_prompt(pending_text, tc)
        config = {"callbacks": callbacks} if callbacks else {}
        if is_remediation:
            synthesis_messages = list(state["messages"])
        else:
            synthesis_messages = list(state["messages"]) + [HumanMessage(content=synthesis_prompt)]
        response = llm_tool_free.invoke(synthesis_messages, config=config)
        raw_content = response.content if isinstance(response, AIMessage) else (response if isinstance(response, str) else "")
        clean_content = strip_reasoning(raw_content if isinstance(raw_content, str) else "")
        synth_msg = AIMessage(content=clean_content)
        return {"messages": [synth_msg]}

    # ── Dual Gate 2: Post-Synthesis Safety Gate ────────────────────────

    def validate_safety(state: AgentState) -> dict:
        """Dual Gate 2: Post-Synthesis Safety Validation Gate."""
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

        has_evidence = len(tc.fetched_sections) > 0 or len(tc.graphic_ids) > 0
        if not answer.strip():
            # Blank-answer guard mirroring Kotlin ClinicalAgentStrategy.evaluateSafetyTurn
            validation = ValidationResult(
                passed=False,
                warnings=[],
                blocked_reason=(
                    "No final clinical answer was synthesized; render the gathered evidence"
                    if has_evidence
                    else "No clinical response was generated. Evidence retrieval is required."
                ),
            )
        else:
            validator = SafetyValidator()
            validation = validator.validate(tc)

        if not validation.passed and state["retry_count"] < max_safety_retries:
            evidence_summary = _build_evidence_summary(tc.fetched_sections)

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

    def route_after_validate_safety(state: AgentState) -> str:
        """Route from Dual Gate 2: finish, terminal safety block, or targeted remediation."""
        val = state.get("validation_result") or {}
        if val.get("passed"):
            return END

        last_msg = state["messages"][-1]
        if isinstance(last_msg, AIMessage) and str(last_msg.content).startswith("Clinical Response Verification Blocked:"):
            return "safety_block"

        # Targeted remediation: check if evidence is present
        tc = TurnContext(**state["turn_context"])
        has_evidence = len(tc.fetched_sections) > 0 or len(tc.graphic_ids) > 0
        if not has_evidence:
            return "node_evidence_gathering"  # Phase 1 Remediation
        else:
            return "node_clinical_synthesis"  # Phase 2 Remediation

    def safety_block(state: AgentState) -> dict:
        """Terminal safety block node ensuring blocked message is the final message."""
        last_msg = state["messages"][-1]
        if isinstance(last_msg, AIMessage) and str(last_msg.content).startswith("Clinical Response Verification Blocked:"):
            return {"messages": [last_msg]}
        blocked_reason = state.get("validation_result", {}).get("blocked_reason") or "Safety verification blocked."
        return {"messages": [AIMessage(content=f"Clinical Response Verification Blocked: {blocked_reason}")]}

    # ── Build the Graph Topology ───────────────────────────────────────

    workflow = StateGraph(AgentState)

    workflow.add_node("node_evidence_gathering", node_evidence_gathering)
    workflow.add_node("execute_tools", execute_tools)
    workflow.add_node("intercept_premature", intercept_premature)
    workflow.add_node("validate_evidence", validate_evidence)
    workflow.add_node("node_compress_history", node_compress_history)
    workflow.add_node("node_clinical_synthesis", node_clinical_synthesis)
    workflow.add_node("validate_safety", validate_safety)
    workflow.add_node("safety_block", safety_block)

    workflow.set_entry_point("node_evidence_gathering")

    workflow.add_conditional_edges("node_evidence_gathering", route_after_evidence_gathering, {
        "execute_tools": "execute_tools",
        "intercept_premature": "intercept_premature",
        "validate_evidence": "validate_evidence",
    })
    workflow.add_edge("intercept_premature", "node_evidence_gathering")
    workflow.add_conditional_edges("execute_tools", route_after_execute_tools, {
        "node_evidence_gathering": "node_evidence_gathering",
        "validate_evidence": "validate_evidence",
    })
    workflow.add_conditional_edges("validate_evidence", route_after_validate_evidence, {
        "node_evidence_gathering": "node_evidence_gathering",
        "safety_block": "safety_block",
        "node_compress_history": "node_compress_history",
        "node_clinical_synthesis": "node_clinical_synthesis",
    })
    workflow.add_edge("node_compress_history", "node_clinical_synthesis")
    workflow.add_edge("node_clinical_synthesis", "validate_safety")
    workflow.add_conditional_edges("validate_safety", route_after_validate_safety, {
        END: END,
        "safety_block": "safety_block",
        "node_evidence_gathering": "node_evidence_gathering",
        "node_clinical_synthesis": "node_clinical_synthesis",
    })
    workflow.add_edge("safety_block", END)

    return workflow.compile()


def _auto_populate_refs(tc: TurnContext, result: str = "") -> None:
    """Auto-populate topicRefs and graphicRefs from fetched sections and graphic IDs.

    Rebuilds both lists from scratch so repeated submissions stay idempotent
    (mirrors Kotlin's replace-on-parse semantics).
    """
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
