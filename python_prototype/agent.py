r"""Two-Stage clinical retrieval agent — mirrors Kotlin ClinicalOrchestrator architecture exactly.

Deterministic Two-Stage Pipeline:
  Stage 0: Small-talk regex router (zero LLM calls)
  Stage 1: Ephemeral evidence retrieval while-loop (tools bound, 3-tier candidate chaining,
           Option A related-topic gating, inline auto-prefetch, 1-pass pause retry)
  Stage 2: Closed-book clinical synthesis (ZERO database tools bound, conditioned strictly
           on <retrieved_evidence>, direct return without post-hoc regex gating)
"""

from __future__ import annotations

import json
import re
from typing import Annotated, Any, Callable, Optional, TypedDict, Union
from typing_extensions import NotRequired

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, SystemMessage, ToolMessage
from langchain_core.tools import BaseTool
from langgraph.graph import END, StateGraph
from langgraph.graph.message import add_messages

from decision_gates import (
    DecisionConfig,
    RankedItem,
    inject_overview_rerank_into_json,
    inject_rerank_into_tool_json,
    inject_search_rerank_into_json,
    rank_overview_items,
    rank_search_candidates,
)
from evidence_models import (
    FetchedGraphic,
    FetchedSection,
    GraphicRef,
    NO_SECTIONS_BLOCKED_REASON,
    NO_TOOLS_BLOCKED_REASON,
    PatientProfile,
    ToolCallRecord,
    TopicRef,
    TurnContext,
    ValidationResult,
)
from reasoning_sanitizer import strip_reasoning
from router import (
    CANNED_SMALL_TALK_REPLY,
    small_talk,
)
from system_prompt import (
    CANONICAL_ABSTENTION,
    NOTHING_RETRIEVED_PLACEHOLDER,
    RETRIEVAL_SYSTEM_PROMPT,
    SYNTHESIS_SYSTEM_PROMPT,
    build_evidence_gate_pause_prompt,
    build_premature_interception_prompt,
    evidence_summary_from,
    format_synthesis_prompt,
)

GRAPHIC_PREFIX_RE = re.compile(r"(?i)^(?:graphic[-_]?(?:table[-_]?)?|table[-_]?)")


def _clean_graphic_id(gid: Any) -> str:
    return GRAPHIC_PREFIX_RE.sub("", str(gid or "")).strip()


def _lookup_db_topic_title(topic_id: str) -> Optional[str]:
    """Fallback database lookup for topic title if title is missing or numeric."""
    if not topic_id:
        return None
    try:
        from tools import _db
        if _db is not None:
            db_title = _db.get_topic_title(topic_id)
            if db_title and not str(db_title).isdigit():
                return str(db_title)
    except Exception:
        pass
    return None


def _to_dict(obj: Any) -> dict[str, Any]:
    """Convert a Pydantic model, dict, or JSON string to a plain dict."""
    if obj is None:
        return {}
    if hasattr(obj, "model_dump"):
        return obj.model_dump()
    if isinstance(obj, dict):
        return obj
    if isinstance(obj, str):
        try:
            parsed = json.loads(obj)
            if isinstance(parsed, dict):
                return parsed
        except Exception:
            pass
    return {}


class AgentState(TypedDict):
    messages: Annotated[list[BaseMessage], add_messages]
    system_prompt: str
    turn_context: dict  # Serialized TurnContext
    user_question: str  # Original user question
    gate1_retries: int  # Stage 1 pause retries (max 1)
    gate2_revisions: int  # Retained for state schema compatibility
    gate2_escalations: int  # Retained for state schema compatibility
    validation_result: Optional[dict]
    tool_rounds: int  # Number of execute_tools passes this turn
    premature_retries: int  # Number of premature interception retries
    decision_telemetry: NotRequired[dict]  # Phase 1 retrieval rerank telemetry


def _build_tool_map(tools: list[BaseTool]) -> dict[str, Callable]:
    """Build name -> invoke mapping for manual tool execution."""
    return {t.name: t for t in tools}


def extract_section_text_from_markdown(full_markdown: str, section_id: str) -> str:
    """Extract section markdown under '=== Section: {section_id} ==='. Parity with Kotlin extractSectionText."""
    if section_id.upper() == "FULL":
        return full_markdown.strip()
    header_pattern = f"=== Section: {section_id} ==="
    start_index = full_markdown.find(header_pattern)
    if start_index == -1:
        return full_markdown.strip()
    text_start = start_index + len(header_pattern)
    next_header_index = full_markdown.find("=== Section: ", text_start)
    if next_header_index != -1:
        return full_markdown[text_start:next_header_index].strip()
    return full_markdown[text_start:].strip()


def _tool_key(raw_name: str) -> str:
    """Canonical tool key: ignores snake_case vs camelCase (mirrors Kotlin normalizeToolName)."""
    return re.sub(r"[^a-z0-9]", "", (raw_name or "").lower())


def create_clinical_agent(
    llm: Any = None,
    tools: Optional[list[BaseTool]] = None,
    system_prompt: str = "",
    callbacks: Optional[list] = None,
    max_tool_retries: int = 10,
    max_safety_retries: int = 2,
    max_premature_retries: int = 2,
    llm_with_tools: Any = None,
    llm_tool_free: Any = None,
    decision_client: Any = None,
    decision_config: Optional[DecisionConfig] = None,
):
    """Create the procedural two-stage clinical retrieval agent graph.

    Args:
        llm: Base LangChain chat model used to derive tool-bound and tool-free models
        tools: List of tool functions
        system_prompt: System prompt text
        callbacks: List of callback handlers
        max_tool_retries: Max execute_tools rounds before routing to validation (default 10)
        max_safety_retries: Max retries (retained for signature compatibility)
        max_premature_retries: Max premature interception retries
        llm_with_tools: LangChain chat model with tools bound (Stage 1: Evidence Gathering)
        llm_tool_free: LangChain chat model with zero tools bound (Stage 2: Clinical Synthesis)
        decision_client: Optional DecisionClient for `gemini-decision-1` retrieval reranking
        decision_config: Optional DecisionConfig controlling active Stage 1 reranking options
    """
    dec_cfg = decision_config or DecisionConfig()
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
    effective_premature_retries = max_premature_retries

    # ── Stage 0: Small-Talk Gating ─────────────────────────────────────

    def node_small_talk_reply(state: AgentState) -> dict:
        """Stage 0 small talk: returns the canned greeting with zero LLM calls."""
        return {"messages": [AIMessage(content=CANNED_SMALL_TALK_REPLY)]}

    def route_after_question(state: AgentState) -> str:
        """Conditional entry point: route on the question at entry."""
        if small_talk(str(state.get("user_question", ""))):
            return "small_talk_reply"
        return "node_evidence_gathering"

    # ── Stage 1: Evidence Gathering Loop ───────────────────────────────

    def node_evidence_gathering(state: AgentState) -> dict:
        """Stage 1: Evidence Gathering using llm_with_tools."""
        config = {"callbacks": callbacks} if callbacks else {}
        response = llm_with_tools.invoke(state["messages"], config=config)
        return {"messages": [response]}

    def route_after_evidence_gathering(state: AgentState) -> str:
        """Route from evidence gathering: tool_calls -> execute_tools, else validate evidence."""
        last_message = state["messages"][-1]
        if isinstance(last_message, AIMessage) and last_message.tool_calls:
            return "execute_tools"

        tc_dict = state.get("turn_context") or {}
        has_evidence = bool(tc_dict.get("fetched_sections")) or bool(tc_dict.get("graphic_ids")) or bool(tc_dict.get("fetched_graphics"))
        if not has_evidence and state.get("premature_retries", 0) < effective_premature_retries:
            return "intercept_premature"

        return "validate_evidence"

    def intercept_premature(state: AgentState) -> dict:
        """Intercept premature response before tools were executed on clinical inquiry."""
        current_retries = state.get("premature_retries", 0) + 1
        prompt = build_premature_interception_prompt()
        return {
            "messages": [HumanMessage(content=prompt)],
            "premature_retries": current_retries,
        }

    def _find_tool_by_key(target_key: str) -> Optional[tuple[str, Callable]]:
        for reg_name, reg_tool in tool_map.items():
            if _tool_key(reg_name) == target_key:
                return reg_name, reg_tool
        return None

    def _run_and_record_tool(
        name: str,
        args: dict[str, Any],
        tc: TurnContext,
    ) -> tuple[Any, str, bool, bool]:
        """Execute a tool call (with deduplication) and record evidence into TurnContext."""
        args_key = json.dumps(args, sort_keys=True)
        cached = next(
            (tc2 for tc2 in tc.tool_calls
             if tc2.tool_name == name and json.dumps(tc2.arguments, sort_keys=True) == args_key),
            None,
        )
        if cached is not None:
            return None, cached.result, cached.success, True

        tc.tool_calls.append(
            ToolCallRecord(
                tool_name=name,
                arguments=args,
                result="",
                success=True,
            )
        )

        tool = tool_map.get(name)
        if tool is None:
            tool_entry = _find_tool_by_key(_tool_key(name))
            if tool_entry is not None:
                tool = tool_entry[1]
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

        raw_dict = _to_dict(raw)
        if success and raw is not None:
            has_error = bool(raw_dict.get("error"))
            is_empty_sections = (
                _tool_key(name) == "gettopicsectionstext"
                and not raw_dict.get("markdown")
                and bool(raw_dict.get("invalidSections") or raw_dict.get("invalid_sections"))
            )
            if has_error or is_empty_sections:
                success = False

        tc.tool_calls[-1].success = success
        tc.tool_calls[-1].result = result_content
        tc.tool_results.append(result_content)

        # Track candidate topic titles from searchTopics
        if _tool_key(name) == "searchtopics":
            for item in (raw_dict.get("results") or []):
                item_dict = _to_dict(item)
                tid = str(item_dict.get("id") or "").strip()
                title = str(item_dict.get("title") or "").strip()
                if tid and title and not title.isdigit():
                    tc.topic_titles[tid] = title

        # Track topic overview bundling
        if _tool_key(name) == "gettopicoverview":
            topic_id = str(
                raw_dict.get("topicId") or raw_dict.get("topic_id") or args.get("topic_id", args.get("topicId", ""))
            ).strip()
            topic_title = str(raw_dict.get("title") or raw_dict.get("topic_title") or "").strip()
            if not topic_id:
                topic_id = str(args.get("topic_id", args.get("topicId", ""))).strip()
            if topic_id and topic_title and not topic_title.isdigit():
                tc.topic_titles[topic_id] = topic_title

            sections = raw_dict.get("sections") or raw_dict.get("outline") or []
            if topic_id and sections:
                tc.outline_sections[topic_id] = {
                    str(_to_dict(s).get("id") or ""): str(_to_dict(s).get("title") or "").lstrip("-–— ")
                    for s in sections
                    if _to_dict(s).get("id")
                }

            graphics = raw_dict.get("graphics") or []
            for g in graphics:
                g_dict = _to_dict(g)
                gid = str(g_dict.get("id") or "").strip()
                if gid:
                    tc.graphic_to_topic[gid] = topic_id
                    clean_gid = _clean_graphic_id(gid)
                    tc.graphic_to_topic[clean_gid] = topic_id
                    g_title = g_dict.get("title")
                    if g_title:
                        tc.graphic_titles[gid] = str(g_title)
                        tc.graphic_titles[clean_gid] = str(g_title)

            topic_type = str(raw_dict.get("topicType") or raw_dict.get("topic_type") or "")
            if topic_type == "calc" and success:
                full_md = str(raw_dict.get("markdown") or "")
                if not tc.has_fetched_section(topic_id, "FULL"):
                    tc.fetched_sections.append(
                        FetchedSection(
                            topic_id=topic_id,
                            topic_title=tc.topic_titles.get(topic_id, topic_title),
                            section_id="FULL",
                            section_title="Calculator Tool Content",
                            content_snippet=full_md.strip(),
                            relevance_score=3.0,
                        )
                    )

        # Batch section tracking
        if _tool_key(name) == "gettopicsectionstext":
            resp_tid = str(raw_dict.get("topicId") or raw_dict.get("topic_id") or "").strip()
            topic_id = resp_tid or str(args.get("topic_id", args.get("topicId", ""))).strip()
            topic_title = str(raw_dict.get("topicTitle") or raw_dict.get("topic_title") or "")
            raw_section_titles = raw_dict.get("sectionTitles") or raw_dict.get("section_titles") or {}
            section_titles = (
                {str(k): str(v).lstrip("-–— ") for k, v in raw_section_titles.items()}
                if isinstance(raw_section_titles, dict)
                else {}
            )
            if topic_id and topic_title and not topic_title.isdigit():
                tc.topic_titles[topic_id] = topic_title
            if topic_id and section_titles:
                tc.outline_sections.setdefault(topic_id, {}).update(section_titles)

            if topic_id and (topic_id not in tc.topic_titles or str(tc.topic_titles[topic_id]).isdigit()):
                db_title = _lookup_db_topic_title(topic_id)
                if db_title:
                    tc.topic_titles[topic_id] = db_title

            section_map = tc.outline_sections.get(topic_id, section_titles)
            section_ids = (
                args.get("section_ids") or args.get("sectionIds")
                or args.get("section_id") or args.get("sectionId") or []
            )
            if isinstance(section_ids, str):
                section_ids = [section_ids]
            raw_markdown = str(raw_dict.get("markdown") or "")
            if success:
                for sid in section_ids:
                    if tc.has_fetched_section(topic_id, sid):
                        continue
                    snippet = extract_section_text_from_markdown(raw_markdown, sid)
                    default_score = 3.0 if sid.upper() == "FULL" else 0.0
                    tc.fetched_sections.append(
                        FetchedSection(
                            topic_id=topic_id,
                            topic_title=tc.topic_titles.get(topic_id, ""),
                            section_id=sid,
                            section_title=section_map.get(sid, "").lstrip("-–— "),
                            content_snippet=snippet if snippet else result_content[:2000],
                            relevance_score=default_score,
                        )
                    )

        # Graphic tracking
        if _tool_key(name) == "getgraphiccontent" and success:
            graphic_id = str(args.get("graphic_id", args.get("graphicId", ""))).strip()
            if graphic_id:
                clean_gid = _clean_graphic_id(graphic_id)
                if clean_gid not in tc.graphic_ids:
                    tc.graphic_ids.append(clean_gid)
                title = raw_dict.get("title")
                if not title:
                    m = re.match(r"### Graphic Table:\s*(.+)", result_content)
                    if m:
                        title = m.group(1).strip()
                if title:
                    tc.graphic_titles[graphic_id] = str(title)
                    tc.graphic_titles[clean_gid] = str(title)
                parent_topic_id = tc.graphic_to_topic.get(graphic_id) or tc.graphic_to_topic.get(clean_gid, "")
                if not any(g.graphic_id == clean_gid for g in tc.fetched_graphics):
                    tc.fetched_graphics.append(
                        FetchedGraphic(
                            graphic_id=clean_gid,
                            title=str(title or ""),
                            markdown=result_content,
                            topic_id=parent_topic_id,
                            topic_title=tc.topic_titles.get(parent_topic_id, ""),
                            relevance_score=2.0,
                        )
                    )

        return raw, result_content, success, False

    def _apply_decision_rerank(
        name: str,
        args: dict[str, Any],
        raw: Any,
        result_content: str,
        tc: TurnContext,
        user_question: str,
        telemetry: dict[str, Any],
    ) -> str:
        """Use `gemini-decision-1` for 3-tier chaining, Option A related-topic gating, and prefetch."""
        if decision_client is None or not dec_cfg.enable_retrieval_rerank or raw is None:
            return result_content

        t_key = _tool_key(name)
        raw_dict = _to_dict(raw)

        if t_key == "searchtopics":
            cand_dicts = [
                {
                    "id": str(_to_dict(item).get("id") or "").strip(),
                    "title": str(_to_dict(item).get("title") or "").strip(),
                    "type": str(_to_dict(item).get("type") or "medical").strip(),
                }
                for item in (raw_dict.get("results") or [])
            ]
            if not cand_dicts:
                return result_content

            rerank = rank_search_candidates(
                decision_client,
                user_question,
                cand_dicts,
                query=str(args.get("query") or args.get("search_query") or "").strip(),
                patient_profile=tc.patient_profile,
                config=dec_cfg,
            )
            if not rerank.ok and not rerank.ranked_topics:
                return result_content

            top1 = rerank.top_topics[0] if rerank.top_topics else None
            top2 = rerank.top_topics[1] if len(rerank.top_topics) > 1 else None
            top3 = rerank.top_topics[2] if len(rerank.top_topics) > 2 else None

            score1 = top1.score if top1 else 0.0
            score2 = top2.score if top2 else 0.0
            score3 = top3.score if top3 else 0.0

            # Tier 1: Clear single winner
            is_tier1_single_winner = top1 is not None and score1 >= 2.5 and (
                top2 is None or score2 < 2.2 or (score1 - score2) >= 0.5
            )

            # Tier 2: 2 complementary co-winners
            is_tier2_co_winners = not is_tier1_single_winner and top1 is not None and top2 is not None and (
                score1 >= 2.5 and score2 >= 2.3 and (top3 is None or score3 < 2.2 or (score2 - score3) >= 0.3)
            )

            candidates_to_chain = (
                [top1] if is_tier1_single_winner
                else ([top1, top2] if is_tier2_co_winners else [])
            )

            if candidates_to_chain and dec_cfg.auto_prefetch:
                chained_ids = [c.id for c in candidates_to_chain]
                any_drug_satisfied = False
                any_needs_drug = False
                any_needs_related = False
                dominant_related_summary = None
                all_prefetched = True
                previews = []

                for cand in candidates_to_chain:
                    cand_id = cand.id
                    cand_type = getattr(cand, "kind", "medical")
                    is_cand_drug = cand_type == "drug"

                    # 1. Fetch overview
                    ov_tool_entry = _find_tool_by_key("gettopicoverview")
                    if ov_tool_entry is None:
                        continue
                    ov_name, _ = ov_tool_entry
                    ov_raw, ov_json, ov_ok, _ = _run_and_record_tool(ov_name, {"topic_id": cand_id}, tc)
                    if not ov_ok or ov_raw is None:
                        all_prefetched = False
                        continue

                    ov_dict = _to_dict(ov_raw)
                    actual_type = str(ov_dict.get("topicType") or ov_dict.get("topic_type") or cand_type).lower()
                    is_calc = actual_type == "calc"
                    is_drug = actual_type == "drug" or is_cand_drug

                    sections_raw = ov_dict.get("sections") or ov_dict.get("outline") or []
                    graphics_raw = ov_dict.get("graphics") or []
                    related_raw = ov_dict.get("relatedTopics") or ov_dict.get("related_topics") or []

                    sections = [
                        {
                            "id": str(_to_dict(s).get("id") or "").strip(),
                            "title": str(_to_dict(s).get("title") or "").strip(),
                            "level": int(_to_dict(s).get("level") or 1),
                            "parentId": _to_dict(s).get("parentId") or _to_dict(s).get("parent_id"),
                        }
                        for s in sections_raw
                    ]
                    graphics = [
                        {
                            "id": str(_to_dict(g).get("id") or "").strip(),
                            "title": str(_to_dict(g).get("title") or "").strip(),
                            "isTable": bool(_to_dict(g).get("isTable", True)),
                        }
                        for g in graphics_raw
                    ]
                    related = [
                        {
                            "id": str(_to_dict(r).get("id") or "").strip(),
                            "title": str(_to_dict(r).get("title") or "").strip(),
                        }
                        for r in related_raw
                    ]

                    # 2. Rerank overview
                    ov_rerank = rank_overview_items(
                        decision_client,
                        user_question,
                        topic_id=cand_id,
                        topic_title=str(ov_dict.get("title") or cand.title),
                        topic_type=actual_type,
                        sections=sections,
                        graphics=graphics,
                        related_topics=related,
                        patient_profile=tc.patient_profile,
                        config=dec_cfg,
                    )

                    # Option A related topic gating
                    uninspected_related = next(
                        (r for r in ov_rerank.related_topic_scores
                         if r.id != cand_id and r.id not in chained_ids and r.id not in tc.outline_sections),
                        None
                    )
                    best_sec_score = ov_rerank.section_scores[0].score if ov_rerank.section_scores else 0.0
                    cand_related_needed = (
                        uninspected_related is not None
                        and uninspected_related.score >= 2.6
                        and (uninspected_related.score > best_sec_score or uninspected_related.score >= 2.8)
                    )
                    if cand_related_needed:
                        any_needs_related = True
                        if dominant_related_summary is None and uninspected_related is not None:
                            dominant_related_summary = f"{uninspected_related.id} ({uninspected_related.title})"

                    # 3. Prefetch sections & tables
                    cand_sections_md = ""
                    cand_tables_md: list[str] = []

                    if ov_rerank.ok and not is_calc:
                        if ov_rerank.recommended_section_ids:
                            sec_tool_entry = _find_tool_by_key("gettopicsectionstext")
                            if sec_tool_entry is not None:
                                sec_name, _ = sec_tool_entry
                                sec_raw, _, sec_ok, _ = _run_and_record_tool(
                                    sec_name,
                                    {"topic_id": cand_id, "section_ids": ov_rerank.recommended_section_ids},
                                    tc,
                                )
                                if sec_ok and sec_raw is not None:
                                    cand_sections_md = str(
                                        getattr(sec_raw, "markdown", (sec_raw.get("markdown", "") if isinstance(sec_raw, dict) else ""))
                                    )

                        if ov_rerank.recommended_graphic_ids:
                            gfx_tool_entry = _find_tool_by_key("getgraphiccontent")
                            if gfx_tool_entry is not None:
                                gfx_name, _ = gfx_tool_entry
                                for gid in ov_rerank.recommended_graphic_ids:
                                    gfx_raw, _, gfx_ok, _ = _run_and_record_tool(
                                        gfx_name,
                                        {"graphic_id": gid},
                                        tc,
                                    )
                                    if gfx_ok and gfx_raw is not None:
                                        t_md = str(getattr(gfx_raw, "markdown", (gfx_raw.get("markdown", "") if isinstance(gfx_raw, dict) else "")))
                                        if t_md:
                                            cand_tables_md.append(t_md)

                    cand_prefetched = bool(cand_sections_md or cand_tables_md or is_calc)
                    if not cand_prefetched:
                        all_prefetched = False

                    if is_drug and cand_prefetched:
                        any_drug_satisfied = True
                    if not is_drug and ov_rerank.needs_drug_monograph:
                        any_needs_drug = True

                    preview_source = cand_sections_md or ("\n\n".join(cand_tables_md))
                    if preview_source.strip():
                        previews.append(preview_source.strip())

                    # Update overview call record with injected rerank
                    ov_updated = inject_overview_rerank_into_json(
                        ov_json,
                        ov_rerank,
                        auto_prefetched=cand_prefetched,
                        prefetched_sections_chars=len(cand_sections_md),
                        prefetched_tables_chars=len("\n\n".join(cand_tables_md)),
                        prune_low_scoring_sections=True,
                        needs_related_topic=cand_related_needed,
                    )
                    for call_rec in reversed(tc.tool_calls):
                        if _tool_key(call_rec.tool_name) == "gettopicoverview" and str(call_rec.arguments.get("topic_id", call_rec.arguments.get("topicId", ""))) == cand_id:
                            call_rec.result = ov_updated
                            break

                effective_drug_needed = False if any_drug_satisfied else any_needs_drug
                combined_preview = None
                if all_prefetched and effective_drug_needed and previews:
                    combined_preview = "\n\n---\n\n".join(previews)[:2500].strip() or None

                updated_search = inject_search_rerank_into_json(
                    result_content,
                    rerank,
                    auto_chained_topic_id=chained_ids[0] if chained_ids else None,
                    auto_chained_topic_ids=chained_ids,
                    auto_prefetched=all_prefetched,
                    needs_drug_monograph=effective_drug_needed,
                    needs_related_topic=any_needs_related,
                    promising_related_summary=dominant_related_summary,
                    prefetched_preview=combined_preview,
                )

                telemetry.setdefault("search_reranks", []).append({
                    "topTopics": [r.model_dump() for r in rerank.top_topics],
                    "autoChainedTopicIds": chained_ids,
                    "autoPrefetched": all_prefetched,
                    "requiresDrugMonograph": effective_drug_needed,
                    "needsRelatedTopic": any_needs_related,
                })
                tc.tool_calls[-1].result = updated_search
                tc.tool_results[-1] = updated_search
                return updated_search

            else:
                updated_search = inject_search_rerank_into_json(
                    result_content,
                    rerank,
                    auto_prefetched=False,
                    needs_drug_monograph=rerank.requires_drug_monograph,
                )
                telemetry.setdefault("search_reranks", []).append({
                    "topTopics": [r.model_dump() for r in rerank.top_topics],
                    "requiresDrugMonograph": rerank.requires_drug_monograph,
                    "requiresTableData": rerank.requires_table_data,
                })
                tc.tool_calls[-1].result = updated_search
                tc.tool_results[-1] = updated_search
                return updated_search

        elif t_key == "gettopicoverview":
            topic_id = str(
                raw_dict.get("topicId") or raw_dict.get("topic_id") or args.get("topic_id", args.get("topicId", ""))
            ).strip()
            topic_title = str(raw_dict.get("title") or raw_dict.get("topic_title") or "").strip()
            topic_type = str(raw_dict.get("topicType") or raw_dict.get("topic_type") or "").strip()
            sections_raw = raw_dict.get("sections") or raw_dict.get("outline") or []
            graphics_raw = raw_dict.get("graphics") or []
            related_raw = raw_dict.get("relatedTopics") or raw_dict.get("related_topics") or []

            sections = [
                {
                    "id": str(_to_dict(s).get("id") or "").strip(),
                    "title": str(_to_dict(s).get("title") or "").strip(),
                    "level": int(_to_dict(s).get("level") or 1),
                    "parentId": _to_dict(s).get("parentId") or _to_dict(s).get("parent_id"),
                }
                for s in sections_raw
            ]
            graphics = [
                {
                    "id": str(_to_dict(g).get("id") or "").strip(),
                    "title": str(_to_dict(g).get("title") or "").strip(),
                    "isTable": bool(_to_dict(g).get("isTable", True)),
                }
                for g in graphics_raw
            ]
            related = [
                {
                    "id": str(_to_dict(r).get("id") or "").strip(),
                    "title": str(_to_dict(r).get("title") or "").strip(),
                }
                for r in related_raw
            ]

            rerank = rank_overview_items(
                decision_client,
                user_question,
                topic_id=topic_id,
                topic_title=topic_title,
                topic_type=topic_type,
                sections=sections,
                graphics=graphics,
                related_topics=related,
                patient_profile=tc.patient_profile,
                config=dec_cfg,
            )

            is_calc = (topic_type or "").lower() == "calc"
            prefetched_sec_md = ""
            prefetched_tbl_md: list[str] = []

            if rerank.ok and dec_cfg.auto_prefetch and not is_calc:
                if rerank.recommended_section_ids:
                    sec_tool_entry = _find_tool_by_key("gettopicsectionstext")
                    if sec_tool_entry is not None:
                        sec_name, _ = sec_tool_entry
                        sec_raw, _, sec_ok, _ = _run_and_record_tool(
                            sec_name,
                            {"topic_id": topic_id, "section_ids": rerank.recommended_section_ids},
                            tc,
                        )
                        if sec_ok and sec_raw is not None:
                            prefetched_sec_md = str(
                                getattr(sec_raw, "markdown", (sec_raw.get("markdown", "") if isinstance(sec_raw, dict) else ""))
                            )

                if rerank.recommended_graphic_ids:
                    gfx_tool_entry = _find_tool_by_key("getgraphiccontent")
                    if gfx_tool_entry is not None:
                        gfx_name, _ = gfx_tool_entry
                        for gid in rerank.recommended_graphic_ids:
                            gfx_raw, _, gfx_ok, _ = _run_and_record_tool(
                                gfx_name,
                                {"graphic_id": gid},
                                tc,
                            )
                            if gfx_ok and gfx_raw is not None:
                                t_md = str(getattr(gfx_raw, "markdown", (gfx_raw.get("markdown", "") if isinstance(gfx_raw, dict) else "")))
                                if t_md:
                                    prefetched_tbl_md.append(t_md)

            did_prefetch = bool(prefetched_sec_md or prefetched_tbl_md or is_calc)
            preview_source = prefetched_sec_md or ("\n\n".join(prefetched_tbl_md))
            preview_excerpt = preview_source[:2500].strip() if (did_prefetch and rerank.needs_drug_monograph) else None

            updated_ov = inject_overview_rerank_into_json(
                result_content,
                rerank,
                auto_prefetched=did_prefetch,
                prefetched_sections_chars=len(prefetched_sec_md),
                prefetched_tables_chars=len("\n\n".join(prefetched_tbl_md)),
                prune_low_scoring_sections=True,
                prefetched_preview=preview_excerpt,
                needs_related_topic=rerank.needs_related_topic,
            )

            telemetry.setdefault("overview_reranks", []).append({
                "topicId": topic_id,
                "autoPrefetched": did_prefetch,
                "recommendedSectionIds": rerank.recommended_section_ids,
                "recommendedGraphicIds": rerank.recommended_graphic_ids,
                "needsDrugMonograph": rerank.needs_drug_monograph,
                "needsRelatedTopic": rerank.needs_related_topic,
            })
            for call_rec in reversed(tc.tool_calls):
                if _tool_key(call_rec.tool_name) == "gettopicoverview":
                    call_rec.result = updated_ov
                    break
            return updated_ov

        return result_content

    def execute_tools(state: AgentState) -> dict:
        """Execute requested tool calls with deduplication, strongly-typed serialization, and 3-tier chaining."""
        last_message = state["messages"][-1]
        tc = TurnContext(**state["turn_context"])
        new_messages: list[BaseMessage] = []
        telemetry = dict(state.get("decision_telemetry") or {})
        user_question = state.get("user_question", "")

        if not isinstance(last_message, AIMessage) or not last_message.tool_calls:
            return {}

        for tool_call in last_message.tool_calls:
            name = tool_call["name"]
            args = tool_call["args"]
            tool_call_id = tool_call.get("id", "")

            raw, result_content, success, from_cache = _run_and_record_tool(name, args, tc)
            if not from_cache and success:
                result_content = _apply_decision_rerank(
                    name, args, raw, result_content, tc, user_question, telemetry
                )

            new_messages.append(
                ToolMessage(content=result_content, tool_call_id=tool_call_id)
            )

        out: dict[str, Any] = {
            "messages": new_messages,
            "turn_context": tc.model_dump(),
            "user_question": user_question,
            "tool_rounds": state.get("tool_rounds", 0) + 1,
        }
        if telemetry:
            out["decision_telemetry"] = telemetry
        return out

    def route_after_execute_tools(state: AgentState) -> str:
        """Route after tool execution: check round budget, short-circuit, or loop."""
        tc = TurnContext(**state["turn_context"])
        can_short_circuit = decision_client is not None and dec_cfg.auto_prefetch

        telemetry = state.get("decision_telemetry") or {}
        last_search = (telemetry.get("search_reranks") or [{}])[-1]
        last_overview = (telemetry.get("overview_reranks") or [{}])[-1]

        all_prefetched = bool(last_search.get("autoPrefetched") or last_overview.get("autoPrefetched"))
        needs_mono = bool(last_search.get("requiresDrugMonograph") or last_overview.get("needsDrugMonograph"))
        needs_related = bool(last_search.get("needsRelatedTopic") or last_overview.get("needsRelatedTopic"))

        if (
            can_short_circuit
            and all_prefetched
            and not needs_mono
            and not needs_related
            and tc.has_evidence
        ):
            return "node_clinical_synthesis"

        if state.get("tool_rounds", 0) >= max_tool_retries:
            return "validate_evidence"

        return "node_evidence_gathering"

    def _evidence_summary_or_placeholder(tc: TurnContext) -> str:
        """Format discovered inventory summary when evidence is absent."""
        parts = []
        if tc.outline_sections:
            parts.append("Discovered Section Outlines (call getTopicSectionsText with these IDs):")
            for tid, s_map in tc.outline_sections.items():
                title = tc.topic_titles.get(tid, tid)
                items_str = ", ".join(f"{sid} ({stitle})" for sid, stitle in list(s_map.items())[:12])
                parts.append(f"- Topic [{tid}] '{title}': {items_str}")
        elif tc.topic_titles:
            parts.append("Discovered Candidate Topics (call getTopicOverview with a topicId):")
            for tid, title in list(tc.topic_titles.items())[:8]:
                parts.append(f"- [{tid}] {title}")
        if tc.graphic_titles:
            parts.append("Discovered Tables (call getGraphicContent with a graphicId):")
            for gid, title in list(tc.graphic_titles.items())[:6]:
                parts.append(f"- [{gid}] {title}")
        if parts:
            return "\n".join(parts)
        return NOTHING_RETRIEVED_PLACEHOLDER

    def validate_evidence(state: AgentState) -> dict:
        """Stage 1 evidence check: 1-pass pause retry if zero evidence, else proceed."""
        tc = TurnContext(**state["turn_context"])

        if tc.has_evidence:
            return {
                "validation_result": {"passed": True},
                "turn_context": tc.model_dump(),
            }

        # Zero evidence check with 1-pass retry budget
        if state.get("gate1_retries", 0) < 1:
            evidence_summary = _evidence_summary_or_placeholder(tc)
            correction_msg = HumanMessage(
                content=build_evidence_gate_pause_prompt(evidence_summary)
            )
            return {
                "messages": [correction_msg],
                "gate1_retries": state.get("gate1_retries", 0) + 1,
                "turn_context": tc.model_dump(),
                "validation_result": {"passed": False},
            }

        # Retry budget exhausted: proceed to synthesis which cleanly emits CANONICAL_ABSTENTION
        return {
            "validation_result": {"passed": True},
            "turn_context": tc.model_dump(),
        }

    def route_after_validate_evidence(state: AgentState) -> str:
        """Route from validate_evidence: loop back for 1-pass retry, or proceed to synthesis."""
        val = state.get("validation_result") or {}
        if not val.get("passed"):
            return "node_evidence_gathering"
        return "node_clinical_synthesis"

    # ── Stage 2: Closed-Book Clinical Synthesis ────────────────────────

    def node_clinical_synthesis(state: AgentState) -> dict:
        """Stage 2: Closed-Book Clinical Synthesis with zero tools bound in model schema."""
        tc = TurnContext(**state["turn_context"])
        user_question = str(state.get("user_question", "")).strip()

        # If zero evidence exists after retrieval rounds and retry, cleanly abstain
        if not tc.has_evidence:
            abstention = CANONICAL_ABSTENTION
            synth_msg = AIMessage(content=abstention)
            tc.answer = abstention
            _auto_populate_refs(tc, abstention)
            return {
                "messages": [synth_msg],
                "turn_context": tc.model_dump(),
                "validation_result": {
                    "passed": True,
                    "topic_refs": [r.model_dump() for r in tc.structured_topic_refs],
                    "graphic_refs": [r.model_dump() for r in tc.structured_graphic_refs],
                },
            }

        synthesis_prompt = format_synthesis_prompt(user_question, tc)
        synthesis_messages = [
            SystemMessage(content=SYNTHESIS_SYSTEM_PROMPT),
            HumanMessage(content=synthesis_prompt),
        ]

        config = {"callbacks": callbacks} if callbacks else {}
        response = llm_tool_free.invoke(synthesis_messages, config=config)
        raw_content = response.content if isinstance(response, AIMessage) else (response if isinstance(response, str) else "")
        clean_content = strip_reasoning(raw_content if isinstance(raw_content, str) else "")

        if not clean_content.strip():
            clean_content = CANONICAL_ABSTENTION

        tc.answer = clean_content
        _auto_populate_refs(tc, clean_content)
        synth_msg = AIMessage(content=clean_content)

        return {
            "messages": [synth_msg],
            "turn_context": tc.model_dump(),
            "validation_result": {
                "passed": True,
                "topic_refs": [r.model_dump() for r in tc.structured_topic_refs],
                "graphic_refs": [r.model_dump() for r in tc.structured_graphic_refs],
            },
        }

    # ── Build the Graph Topology ───────────────────────────────────────

    workflow = StateGraph(AgentState)

    workflow.add_node("small_talk_reply", node_small_talk_reply)
    workflow.add_node("node_evidence_gathering", node_evidence_gathering)
    workflow.add_node("execute_tools", execute_tools)
    workflow.add_node("intercept_premature", intercept_premature)
    workflow.add_node("validate_evidence", validate_evidence)
    workflow.add_node("node_clinical_synthesis", node_clinical_synthesis)

    # Stage 0 entry point
    workflow.set_conditional_entry_point(route_after_question, {
        "small_talk_reply": "small_talk_reply",
        "node_evidence_gathering": "node_evidence_gathering",
    })
    workflow.add_edge("small_talk_reply", END)

    # Stage 1 evidence gathering loop
    workflow.add_conditional_edges("node_evidence_gathering", route_after_evidence_gathering, {
        "execute_tools": "execute_tools",
        "intercept_premature": "intercept_premature",
        "validate_evidence": "validate_evidence",
    })
    workflow.add_edge("intercept_premature", "node_evidence_gathering")
    workflow.add_conditional_edges("execute_tools", route_after_execute_tools, {
        "node_evidence_gathering": "node_evidence_gathering",
        "validate_evidence": "validate_evidence",
        "node_clinical_synthesis": "node_clinical_synthesis",
    })
    workflow.add_conditional_edges("validate_evidence", route_after_validate_evidence, {
        "node_evidence_gathering": "node_evidence_gathering",
        "node_clinical_synthesis": "node_clinical_synthesis",
    })

    # Stage 2 synthesis terminates directly without post-hoc regex gating
    workflow.add_edge("node_clinical_synthesis", END)

    return workflow.compile()


def _clean_section_title(title: str) -> str:
    return str(title or "").lstrip("-—– \t").strip()


def _auto_populate_refs(tc: TurnContext, result: str = "") -> None:
    """Auto-populate topicRefs and graphicRefs from fetched sections and graphics.

    Mirrors Kotlin TurnContextAccumulator.buildTurnContext().
    """
    valid_sections = [
        s for s in tc.fetched_sections
        if _clean_section_title(s.section_title) or _clean_section_title(tc.outline_sections.get(s.topic_id, {}).get(s.section_id, ""))
    ]
    candidate_sections = valid_sections if valid_sections else tc.fetched_sections

    seen_pairs: set[tuple[str, str]] = set()
    auto_topic_refs: list[TopicRef] = []

    for sec in candidate_sections:
        pair = (sec.topic_id, sec.section_id)
        if pair in seen_pairs:
            continue
        seen_pairs.add(pair)
        clean_sec_id = "" if sec.section_id.upper() == "FULL" else sec.section_id
        resolved_sec_title = _clean_section_title(sec.section_title) or _clean_section_title(
            tc.outline_sections.get(sec.topic_id, {}).get(sec.section_id, "")
        )
        label = resolved_sec_title or sec.topic_title or sec.topic_id
        auto_topic_refs.append(
            TopicRef(
                topic_id=sec.topic_id,
                section_id=clean_sec_id,
                label=label,
                topic_title=sec.topic_title,
                is_graphic=False,
            )
        )

    # Ensure parent topics for graphic tables are attributed in topic refs
    final_topic_refs = list(auto_topic_refs)
    existing_topic_ids = {r.topic_id for r in final_topic_refs}

    for gid in tc.graphic_ids:
        clean_gid = _clean_graphic_id(gid)
        parent_topic_id = tc.graphic_to_topic.get(gid) or tc.graphic_to_topic.get(clean_gid)
        if parent_topic_id and parent_topic_id not in existing_topic_ids:
            resolved_title = tc.topic_titles.get(parent_topic_id, parent_topic_id)
            existing_topic_ids.add(parent_topic_id)
            final_topic_refs.append(
                TopicRef(
                    topic_id=parent_topic_id,
                    section_id="",
                    label=resolved_title,
                    topic_title=resolved_title,
                    is_graphic=False,
                )
            )

    auto_graphic_refs: list[GraphicRef] = []
    seen_gids: set[str] = set()
    for gid in tc.graphic_ids:
        clean_gid = _clean_graphic_id(gid)
        if clean_gid in seen_gids:
            continue
        seen_gids.add(clean_gid)
        title = tc.graphic_titles.get(clean_gid) or tc.graphic_titles.get(gid) or f"Table {clean_gid}"
        parent_topic_id = tc.graphic_to_topic.get(gid) or tc.graphic_to_topic.get(clean_gid)
        auto_graphic_refs.append(
            GraphicRef(
                graphic_id=clean_gid,
                label=title,
                topic_id=parent_topic_id,
            )
        )

    tc.structured_topic_refs = final_topic_refs
    tc.structured_graphic_refs = auto_graphic_refs


def create_initial_state(
    user_message: str,
    system_prompt: str,
    patient_profile: Optional[Union[PatientProfile, dict]] = None,
) -> dict:
    """Create initial agent state for a new turn."""
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
        "gate1_retries": 0,
        "gate2_revisions": 0,
        "gate2_escalations": 0,
        "validation_result": None,
        "tool_rounds": 0,
        "premature_retries": 0,
        "decision_telemetry": {},
    }
