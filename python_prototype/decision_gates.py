"""ClinRef Phase 1 retrieval reranking via `gemini-decision-1`.

Uses calibrated scoring over titles and outline headings to accelerate Phase 1
tool discovery:
  1. Search result reranking (`rank_search_candidates`) inside `searchTopics`
  2. Topic outline / table / related-topic reranking (`rank_overview_items`)
     inside `getTopicOverview` (plus optional inline auto-prefetch)
"""

from __future__ import annotations

import json
from typing import Any, Optional, Union

from pydantic import BaseModel, Field

from decision_client import (
    DecisionClient,
    make_text_blocks,
    noul_question,
    sanitize_question_key,
    score_question,
)
from safety_validator import PatientProfile

# Ordered 4-level relevance rubric for search topics, outline sections, tables, and related topics.
_RELEVANCE_RUBRIC = [
    "Irrelevant: unrelated to the question or patient.",
    "Tangential: background only; does not answer inquiry.",
    "Helpful context: workup, monitoring, or secondary detail.",
    "Essential: direct answer, exact dose, threshold, or first-line rule.",
]

_BOILERPLATE_SECTION_TITLES = {
    "references",
    "society guideline links",
    "information for patients",
    "patient information",
    "topic outline",
    "contributors",
    "author and editor disclosures",
    "disclosures",
}


def is_boilerplate_section(title: str) -> bool:
    return (title or "").strip().lower() in _BOILERPLATE_SECTION_TITLES


def filter_subsumed_sections(
    candidate_section_ids: list[str],
    all_sections: list[dict[str, Any]],
) -> list[str]:
    """Eliminates redundant parent/child section pairs from candidate section IDs."""
    if len(candidate_section_ids) <= 1:
        return candidate_section_ids
    parent_map: dict[str, Optional[str]] = {
        str(s.get("id")): s.get("parentId") for s in all_sections if s.get("id")
    }
    descendants_map: dict[str, set[str]] = {}
    for s in all_sections:
        sid = str(s.get("id"))
        p = s.get("parentId")
        while p:
            descendants_map.setdefault(str(p), set()).add(sid)
            p = parent_map.get(str(p))

    result: list[str] = []
    result_set: set[str] = set()

    for sid in candidate_section_ids:
        # Check if an ancestor is already selected
        has_ancestor = False
        p = parent_map.get(sid)
        while p:
            if str(p) in result_set:
                has_ancestor = True
                break
            p = parent_map.get(str(p))
        if has_ancestor:
            continue

        # Check if any descendant is already selected
        descendants = descendants_map.get(sid)
        if descendants and any(d in result_set for d in descendants):
            continue

        result.append(sid)
        result_set.add(sid)
    return result


_MAX_OUTLINE_SECTIONS_TO_SCORE = 70
_MAX_TABLE_GRAPHICS_TO_SCORE = 15
_MAX_RELATED_TOPICS_TO_SCORE = 10


class DecisionConfig(BaseModel):
    """Configuration controlling Gemini Decisions API Phase 1 reranking."""

    enable_retrieval_rerank: bool = True
    auto_prefetch: bool = False

    section_score_threshold: float = 1.8
    table_score_threshold: float = 1.7
    topic_score_threshold: float = 1.5

    top_k_sections: int = 8
    top_k_tables: int = 4
    top_k_topics: int = 3

    @classmethod
    def rerank(cls, *, auto_prefetch: bool = True) -> "DecisionConfig":
        return cls(
            enable_retrieval_rerank=True,
            auto_prefetch=auto_prefetch,
        )

    @classmethod
    def from_mode(cls, mode: str, *, auto_prefetch: bool = True) -> Optional["DecisionConfig"]:
        normalized = (mode or "off").strip().lower()
        if normalized == "off":
            return None
        if normalized == "rerank":
            return cls.rerank(auto_prefetch=auto_prefetch)
        raise ValueError(f"Unknown decision mode: {mode!r} (expected 'off' or 'rerank')")


def _format_patient_profile(profile: Optional[Union[PatientProfile, dict, str]]) -> str:
    if not profile:
        return ""
    if isinstance(profile, str):
        return profile.strip()
    if isinstance(profile, PatientProfile):
        return profile.to_system_block()
    if isinstance(profile, dict):
        return PatientProfile(**profile).to_system_block()
    return str(profile).strip()


def _build_clinical_context_blocks(
    question: str,
    patient_profile: Optional[Union[PatientProfile, dict, str]] = None,
    extra_blocks: Optional[list[str]] = None,
) -> list[dict[str, str]]:
    blocks = [f"CLINICAL QUESTION:\n{question.strip()}"]
    profile_text = _format_patient_profile(patient_profile)
    if profile_text:
        blocks.append(profile_text)
    if extra_blocks:
        blocks.extend(b for b in extra_blocks if b)
    return make_text_blocks(blocks)


# ── 1. Phase 1 Retrieval Acceleration ────────────────────────────────────────


class RankedItem(BaseModel):
    id: str
    title: str
    score: float
    confidence: float
    kind: str  # "topic", "section", "table", "related_topic"


class SearchRerankResult(BaseModel):
    ok: bool = True
    error: Optional[str] = None
    ranked_topics: list[RankedItem] = Field(default_factory=list)
    top_topics: list[RankedItem] = Field(default_factory=list)
    requires_drug_monograph: bool = False
    monograph_confidence: float = 0.0
    requires_table_data: bool = False
    table_confidence: float = 0.0
    hint: str = ""
    input_tokens: int = 0


class OverviewRerankResult(BaseModel):
    ok: bool = True
    error: Optional[str] = None
    recommended_section_ids: list[str] = Field(default_factory=list)
    recommended_graphic_ids: list[str] = Field(default_factory=list)
    recommended_related_topic_ids: list[str] = Field(default_factory=list)
    section_scores: list[RankedItem] = Field(default_factory=list)
    graphic_scores: list[RankedItem] = Field(default_factory=list)
    related_topic_scores: list[RankedItem] = Field(default_factory=list)
    needs_drug_monograph: bool = False
    monograph_confidence: float = 0.0
    hint: str = ""
    input_tokens: int = 0


def rank_search_candidates(
    client: DecisionClient,
    question: str,
    candidates: list[dict[str, Any]],
    *,
    query: str = "",
    patient_profile: Optional[Union[PatientProfile, dict, str]] = None,
    config: Optional[DecisionConfig] = None,
) -> SearchRerankResult:
    """Score searchTopics candidate topics and detect drug-monograph / table intent in one pass."""
    cfg = config or DecisionConfig()
    if not candidates:
        return SearchRerankResult(ok=True)

    candidate_lines = [
        f"- [{c.get('id', '')}] {c.get('title', '')}"
        for c in candidates
        if c.get("id")
    ]
    if not candidate_lines:
        return SearchRerankResult(ok=True)

    extra_blocks = []
    if query.strip():
        extra_blocks.append(f"SEARCH QUERY:\n{query.strip()}")
    extra_blocks.append("SEARCH CANDIDATES:\n" + "\n".join(candidate_lines))

    input_blocks = _build_clinical_context_blocks(
        question,
        patient_profile=patient_profile,
        extra_blocks=extra_blocks,
    )

    questions: dict[str, dict[str, Any]] = {
        "requires_drug_monograph": noul_question(
            "Does answering the clinical question require exact medication dosing, titration, or renal/hepatic adjustment cutoffs from a dedicated drug monograph?",
            true_description="Clinician asks for exact mg doses, frequency, titration, renal/hepatic dose adjustments, or drug interactions.",
            false_description="Clinician asks about diagnosis, etiology, workup, prognosis, scoring criteria, or general non-dosing management.",
        ),
        "requires_table_data": noul_question(
            "Does answering this question require checking structured clinical tables (e.g., risk scores, grading criteria, comparative drug regimens, or diagnostic thresholds)?",
            true_description="Structured comparison tables, dosing tables, classification criteria, or diagnostic scorecards are important.",
            false_description="Narrative section prose alone is sufficient without table lookup.",
        ),
    }

    key_to_cand: dict[str, tuple[str, str]] = {}
    for idx, cand in enumerate(candidates):
        cid = str(cand.get("id", "")).strip()
        title = str(cand.get("title", "")).strip()
        if not cid:
            continue
        q_key = sanitize_question_key(f"topic_{idx}_{cid}", prefix="topic", index=idx)
        key_to_cand[q_key] = (cid, title)
        questions[q_key] = score_question(
            f"Rate how directly UpToDate topic '{title}' (ID {cid}) matches the clinical question.",
            _RELEVANCE_RUBRIC,
        )

    batch = client.decide(input_blocks, questions)
    if not batch.ok and not batch.answers:
        return SearchRerankResult(ok=False, error=batch.error, input_tokens=batch.usage.total_input_tokens)

    ranked: list[RankedItem] = []
    for q_key, (cid, title) in key_to_cand.items():
        ans = batch.get_score(q_key)
        if ans is not None:
            ranked.append(
                RankedItem(
                    id=cid,
                    title=title,
                    score=round(ans.score, 3),
                    confidence=round(ans.confidence, 3),
                    kind="topic",
                )
            )
    ranked.sort(key=lambda item: item.score, reverse=True)

    mono_ans = batch.get_noul("requires_drug_monograph")
    table_ans = batch.get_noul("requires_table_data")
    needs_mono = bool(mono_ans and mono_ans.noul >= 0.70)
    needs_table = bool(table_ans and table_ans.noul >= 0.70)

    top_items = [r for r in ranked if r.score >= cfg.topic_score_threshold][: cfg.top_k_topics]
    if not top_items:
        top_items = ranked[: min(2, len(ranked))]

    hint_parts = []
    if top_items:
        hint_parts.append(
            "Top topic candidates: "
            + ", ".join(f"[{r.id}] {r.title} (score={r.score:.2f})" for r in top_items)
        )
    if needs_mono:
        hint_parts.append(
            "Dosing/adjustment intent detected: ensure a `drug` monograph topic is also consulted if the disease topic omits exact doses."
        )
    if needs_table:
        hint_parts.append(
            "Tabular data likely relevant: check `graphics` (`isTable: true`) in getTopicOverview."
        )

    return SearchRerankResult(
        ok=batch.ok,
        error=batch.error,
        ranked_topics=ranked,
        top_topics=top_items,
        requires_drug_monograph=needs_mono,
        monograph_confidence=round(mono_ans.confidence, 3) if mono_ans else 0.0,
        requires_table_data=needs_table,
        table_confidence=round(table_ans.confidence, 3) if table_ans else 0.0,
        hint=" ".join(hint_parts),
        input_tokens=batch.usage.total_input_tokens,
    )


def _unique_key(existing: dict[str, Any], raw: str, prefix: str, index: int) -> str:
    candidate = sanitize_question_key(raw, prefix=prefix, index=index)
    if candidate not in existing:
        return candidate
    for disambig in range(1, 100):
        suffix = f"_{disambig}"
        base = candidate[: 64 - len(suffix)]
        cand2 = f"{base}{suffix}"
        if cand2 not in existing:
            return cand2
    return f"{prefix}_{index}"


def rank_overview_items(
    client: DecisionClient,
    question: str,
    *,
    topic_id: str,
    topic_title: str,
    topic_type: str,
    sections: list[dict[str, Any]],
    graphics: list[dict[str, Any]],
    related_topics: list[dict[str, Any]],
    patient_profile: Optional[Union[PatientProfile, dict, str]] = None,
    config: Optional[DecisionConfig] = None,
) -> OverviewRerankResult:
    """Rank outline sections, table graphics, and related topics in one parallel forward pass."""
    cfg = config or DecisionConfig()
    if topic_type == "calc" or (not sections and not graphics):
        return OverviewRerankResult(ok=True)

    trimmed_sections = [
        s for s in sections
        if s.get("id") and not is_boilerplate_section(str(s.get("title", "")))
    ][:_MAX_OUTLINE_SECTIONS_TO_SCORE]
    trimmed_graphics = [
        g for g in graphics
        if g.get("id") and g.get("isTable", True)
    ][:_MAX_TABLE_GRAPHICS_TO_SCORE]
    trimmed_related = [
        r for r in related_topics
        if r.get("id")
    ][:_MAX_RELATED_TOPICS_TO_SCORE]

    if not trimmed_sections and not trimmed_graphics:
        return OverviewRerankResult(ok=True)

    h1_headings = [s for s in sections if int(s.get("level", 1)) == 1][:15]
    h1_digest = (
        "\n\nMajor Heading Outline:\n" + "\n".join(f"- {s.get('title', '')}" for s in h1_headings)
        if h1_headings
        else ""
    )
    topic_block = f"OPEN TOPIC:\n[{topic_id}] {topic_title} (type={topic_type or 'topic'}){h1_digest}"
    input_blocks = _build_clinical_context_blocks(
        question,
        patient_profile=patient_profile,
        extra_blocks=[topic_block],
    )

    questions: dict[str, dict[str, Any]] = {}
    questions["is_topic_relevant"] = noul_question(
        f"Is topic '{topic_title}' relevant to answering the clinical question?",
        true_description="This topic contains clinical information pertinent to the inquiry.",
        false_description="This topic is unrelated or off-target for the clinical question.",
    )

    if topic_type != "drug":
        questions["needs_drug_monograph"] = noul_question(
            f"Given that [{topic_id}] '{topic_title}' is a '{topic_type}' topic (not a Lexidrug monograph), will the clinician also need a dedicated `drug` monograph to obtain exact dosing or renal/hepatic cutoffs?",
        )

    sec_title_map = {str(s.get("id")): str(s.get("title", "")) for s in sections if s.get("id")}
    sec_map: dict[str, tuple[str, str]] = {}
    for idx, s in enumerate(trimmed_sections):
        sid = str(s["id"]).strip()
        stitle = str(s.get("title", "")).strip()
        parent_id = str(s.get("parentId") or "")
        parent_title = sec_title_map.get(parent_id) if parent_id else None
        hierarchical_label = f"{parent_title} > {stitle}" if parent_title else stitle
        q_key = _unique_key(questions, f"sec_{idx}_{sid}", prefix="sec", index=idx)
        sec_map[q_key] = (sid, stitle)
        questions[q_key] = score_question(
            f"Rate relevance of section '{hierarchical_label}' (ID {sid}, H{s.get('level', 1)}) to the clinical question.",
            _RELEVANCE_RUBRIC,
        )

    gfx_map: dict[str, tuple[str, str]] = {}
    for idx, g in enumerate(trimmed_graphics):
        gid = str(g["id"]).strip()
        gtitle = str(g.get("title", "")).strip()
        q_key = _unique_key(questions, f"gfx_{idx}_{gid}", prefix="gfx", index=idx)
        gfx_map[q_key] = (gid, gtitle)
        questions[q_key] = score_question(
            f"Rate relevance of clinical table '{gtitle}' (ID {gid}) to the clinical question.",
            _RELEVANCE_RUBRIC,
        )

    rel_map: dict[str, tuple[str, str]] = {}
    for idx, r in enumerate(trimmed_related):
        rid = str(r["id"]).strip()
        rtitle = str(r.get("title", "")).strip()
        q_key = _unique_key(questions, f"rel_{idx}_{rid}", prefix="rel", index=idx)
        rel_map[q_key] = (rid, rtitle)
        questions[q_key] = score_question(
            f"Rate relevance of linked topic '{rtitle}' (ID {rid}) to the clinical question.",
            _RELEVANCE_RUBRIC,
        )

    batch = client.decide(input_blocks, questions)
    if not batch.ok and not batch.answers:
        return OverviewRerankResult(ok=False, error=batch.error, input_tokens=batch.usage.total_input_tokens)

    def _collect(mapping: dict[str, tuple[str, str]], kind: str) -> list[RankedItem]:
        items: list[RankedItem] = []
        for q_key, (item_id, item_title) in mapping.items():
            ans = batch.get_score(q_key)
            if ans is not None:
                items.append(
                    RankedItem(
                        id=item_id,
                        title=item_title,
                        score=round(ans.score, 3),
                        confidence=round(ans.confidence, 3),
                        kind=kind,
                    )
                )
        items.sort(key=lambda item: item.score, reverse=True)
        return items

    sec_scores = _collect(sec_map, "section")
    gfx_scores = _collect(gfx_map, "table")
    rel_scores = _collect(rel_map, "related_topic")

    topic_rel_ans = batch.get_noul("is_topic_relevant")
    is_topic_relevant = topic_rel_ans.noul if topic_rel_ans else 1.0

    sections_passing = [
        item.id for item in sec_scores if item.score >= cfg.section_score_threshold
    ]
    dedup_passing = filter_subsumed_sections(sections_passing, sections)[: cfg.top_k_sections]

    if dedup_passing:
        rec_sections = dedup_passing
    elif (
        is_topic_relevant >= 0.35
        and sec_scores
        and sec_scores[0].score >= 1.0
        and sec_scores[0].confidence >= 0.50
    ):
        fallback_candidates = [item.id for item in sec_scores[: min(3, len(sec_scores))]]
        rec_sections = filter_subsumed_sections(fallback_candidates, sections)
    else:
        rec_sections = []

    rec_graphics = [
        item.id for item in gfx_scores if item.score >= cfg.table_score_threshold
    ][: cfg.top_k_tables]

    rec_related = [
        item.id for item in rel_scores if item.score >= cfg.topic_score_threshold
    ][: cfg.top_k_topics]

    mono_ans = batch.get_noul("needs_drug_monograph")
    needs_mono = bool(mono_ans and mono_ans.noul >= 0.75)

    hint_lines = []
    if rec_sections:
        top_sec_str = ", ".join(
            f"{item.id} ({item.title}: {item.score:.2f})"
            for item in sec_scores
            if item.id in rec_sections
        )
        hint_lines.append(f"Priority sectionIds: {top_sec_str}")
    elif sec_scores:
        hint_lines.append(
            "All sections in this topic scored low relevance (<1.0); check relatedTopics or refine searchTopics."
        )
    if rec_graphics:
        top_gfx_str = ", ".join(
            f"{item.id} ({item.title}: {item.score:.2f})"
            for item in gfx_scores
            if item.id in rec_graphics
        )
        hint_lines.append(f"Priority table graphicIds: {top_gfx_str}")
    if rec_related:
        top_rel_str = ", ".join(
            f"[{item.id}] {item.title} ({item.score:.2f})"
            for item in rel_scores
            if item.id in rec_related
        )
        hint_lines.append(f"High-signal relatedTopics: {top_rel_str}")
    if needs_mono:
        hint_lines.append(
            "Consider fetching the dedicated `drug` monograph if exact doses/renal cutoffs are absent."
        )

    return OverviewRerankResult(
        ok=batch.ok,
        error=batch.error,
        recommended_section_ids=rec_sections,
        recommended_graphic_ids=rec_graphics,
        recommended_related_topic_ids=rec_related,
        section_scores=sec_scores,
        graphic_scores=gfx_scores,
        related_topic_scores=rel_scores,
        needs_drug_monograph=needs_mono,
        monograph_confidence=round(mono_ans.confidence, 3) if mono_ans else 0.0,
        hint=" | ".join(hint_lines),
        input_tokens=batch.usage.total_input_tokens,
    )


def inject_rerank_into_tool_json(raw_json: str, rerank_payload: dict[str, Any]) -> str:
    """Attach a `decisionRerank` dictionary to a JSON tool payload without breaking JSON validity."""
    try:
        data = json.loads(raw_json)
    except Exception:
        return raw_json
    if not isinstance(data, dict):
        return raw_json
    data["decisionRerank"] = rerank_payload
    return json.dumps(data, ensure_ascii=False)
