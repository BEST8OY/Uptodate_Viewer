"""ClinRef Phase 1 retrieval reranking via `gemini-decision-1`.

Uses calibrated scoring over titles and outline headings to accelerate Phase 1
tool discovery:
  1. Search result reranking (`rank_search_candidates`) inside `searchTopics`
  2. Topic outline / table / related-topic reranking (`rank_overview_items`)
     inside `getTopicOverview` (plus optional inline auto-prefetch)

Mirrors Kotlin DecisionGates.kt exactly.
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
from evidence_models import PatientProfile

# Ordered 4-level relevance rubric for search topics, outline sections, and tables (mirrors Kotlin DecisionGates.RELEVANCE_RUBRIC).
_RELEVANCE_RUBRIC = [
    "Irrelevant.",
    "Tangential: background only or wrong patient population.",
    "Supporting: secondary detail or indirect context.",
    "Essential: directly answers the inquiry (rule, dose, workup, monitoring, or criteria) for this patient.",
]

# Dedicated 4-level rubric for related topics (mirrors Kotlin DecisionGates.RELATED_TOPIC_RUBRIC).
_RELATED_TOPIC_RUBRIC = [
    "Irrelevant.",
    "Redundant: already covered by the open topic, or background only.",
    "Supplemental: useful extra context beyond the open topic.",
    "Essential missing source: specifically answers the question or patient population better than the open topic.",
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


_MAX_OUTLINE_SECTIONS_TO_SCORE = 250
_MAX_TABLE_GRAPHICS_TO_SCORE = 25
_MAX_RELATED_TOPICS_TO_SCORE = 25


class DecisionConfig(BaseModel):
    """Configuration controlling Gemini Decisions API Phase 1 reranking."""

    enable_retrieval_rerank: bool = True
    auto_prefetch: bool = False

    section_score_threshold: float = 1.8
    table_score_threshold: float = 1.7
    topic_score_threshold: float = 1.5

    top_k_sections: int = 8
    top_k_tables: int = 4
    top_k_topics: int = 5

    search_drug_monograph_threshold: float = 0.70
    overview_drug_monograph_threshold: float = 0.75
    table_data_threshold: float = 0.70

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
    blocks = [f"QUESTION:\n{question.strip()}"]
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
    confidence: float = 0.9
    kind: str = "topic"  # "topic", "section", "table", "related_topic"


class SearchRerankResult(BaseModel):
    ok: bool = True
    error: Optional[str] = None
    ranked_topics: list[RankedItem] = Field(default_factory=list)
    top_topics: list[RankedItem] = Field(default_factory=list)
    requires_drug_monograph: bool = False
    drug_monograph_prob: float = 0.0
    monograph_confidence: float = 0.0
    requires_table_data: bool = False
    table_data_prob: float = 0.0
    table_confidence: float = 0.0
    hint: str = ""
    input_tokens: int = 0


class OverviewRerankResult(BaseModel):
    ok: bool = True
    error: Optional[str] = None
    topic_id: str = ""
    topic_title: str = ""
    recommended_section_ids: list[str] = Field(default_factory=list)
    recommended_graphic_ids: list[str] = Field(default_factory=list)
    recommended_related_topic_ids: list[str] = Field(default_factory=list)
    section_scores: list[RankedItem] = Field(default_factory=list)
    graphic_scores: list[RankedItem] = Field(default_factory=list)
    related_topic_scores: list[RankedItem] = Field(default_factory=list)
    needs_drug_monograph: bool = False
    needs_related_topic: bool = False
    drug_monograph_prob: float = 0.0
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
        f"- [{c.get('id', '')}] {c.get('title', '')} ({c.get('type', 'medical')})"
        for c in candidates
        if c.get("id")
    ]
    if not candidate_lines:
        return SearchRerankResult(ok=True)

    profile_text = _format_patient_profile(patient_profile)
    task_instruction = (
        "TASK: Score each candidate's relevance to answering QUESTION"
        + (" for PATIENT PROFILE." if profile_text else ".")
    )
    extra_blocks = []
    if query.strip():
        extra_blocks.append(f"SEARCH QUERY:\n{query.strip()}")
    extra_blocks.append("SEARCH CANDIDATES:\n" + "\n".join(candidate_lines))
    extra_blocks.append(task_instruction)

    input_blocks = _build_clinical_context_blocks(
        question,
        patient_profile=patient_profile,
        extra_blocks=extra_blocks,
    )

    questions: dict[str, dict[str, Any]] = {
        "requires_drug_monograph": noul_question(
            "Does answering this clinical question accurately require consulting a dedicated drug dosing / Lexidrug monograph topic?",
            true_description="Directly asks about a specific drug, or requires monograph-level details (renal/hepatic dose adjustment, pediatric weight dosing, formulation, boxed warnings, contraindications, or drug-drug interactions).",
            false_description="Disease diagnosis, workup, or standard first-line management/regimens normally covered inside a clinical disease topic.",
        ),
        "requires_table_data": noul_question(
            "Does answering this question require checking structured clinical tables (e.g., risk scores, grading criteria, comparative drug regimens, or diagnostic thresholds)?",
            true_description="Structured comparison tables, dosing tables, classification criteria, or diagnostic scorecards are important.",
            false_description="Narrative section prose alone is sufficient without table lookup.",
        ),
    }

    key_to_cand: dict[str, tuple[str, str, str]] = {}
    for idx, cand in enumerate(candidates):
        cid = str(cand.get("id", "")).strip()
        title = str(cand.get("title", "")).strip()
        cand_type = str(cand.get("type", "medical")).strip()
        if not cid:
            continue
        q_key = sanitize_question_key(f"topic_{idx}_{cid}", prefix="topic", index=idx)
        key_to_cand[q_key] = (cid, title, cand_type)
        questions[q_key] = score_question(
            f"Relevance of topic '{title}' (id={cid}, type={cand_type}) to answering QUESTION.",
            _RELEVANCE_RUBRIC,
        )

    batch = client.decide(input_blocks, questions)
    if not batch.ok and not batch.answers:
        return SearchRerankResult(ok=False, error=batch.error, input_tokens=batch.usage.total_input_tokens)

    ranked: list[RankedItem] = []
    for q_key, (cid, title, cand_type) in key_to_cand.items():
        ans = batch.get_score(q_key)
        if ans is not None:
            ranked.append(
                RankedItem(
                    id=cid,
                    title=title,
                    score=round(ans.score, 3),
                    confidence=round(ans.confidence, 3),
                    kind=cand_type,
                )
            )
    ranked.sort(key=lambda item: (item.score, item.confidence), reverse=True)

    mono_ans = batch.get_noul("requires_drug_monograph")
    table_ans = batch.get_noul("requires_table_data")
    drug_prob = mono_ans.noul if mono_ans else 0.0
    table_prob = table_ans.noul if table_ans else 0.0
    needs_mono = bool(drug_prob >= cfg.search_drug_monograph_threshold)
    needs_table = bool(table_prob >= cfg.table_data_threshold)

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
        drug_monograph_prob=round(drug_prob, 3),
        monograph_confidence=round(mono_ans.confidence, 3) if mono_ans else 0.0,
        requires_table_data=needs_table,
        table_data_prob=round(table_prob, 3),
        table_confidence=round(table_ans.confidence, 3) if table_ans else 0.0,
        hint=" | ".join(hint_parts),
        input_tokens=batch.usage.total_input_tokens,
    )


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
    search_requires_table_data: bool = False,
) -> OverviewRerankResult:
    """Rank outline sections, table graphics, and related topics in one parallel forward pass."""
    cfg = config or DecisionConfig()
    if topic_type == "calc":
        calc_title = topic_title or "Calculator Tool Content"
        return OverviewRerankResult(
            topic_id=topic_id,
            topic_title=topic_title,
            section_scores=[RankedItem(id="FULL", title=calc_title, score=3.0, confidence=1.0, kind="section")],
            recommended_section_ids=["FULL"],
            ok=True,
        )

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
        return OverviewRerankResult(topic_id=topic_id, topic_title=topic_title, ok=True)

    h1_headings = [s for s in sections if int(s.get("level", 1)) == 1][:15]
    h1_digest = (
        "\n\nMajor Heading Outline:\n" + "\n".join(f"- {s.get('title', '')}" for s in h1_headings)
        if h1_headings
        else ""
    )
    topic_block = f"OPEN TOPIC:\n[{topic_id}] {topic_title} (type={topic_type or 'topic'}){h1_digest}"
    profile_text = _format_patient_profile(patient_profile)
    task_instruction = (
        "TASK: Score each item's relevance to answering QUESTION"
        + (" for PATIENT PROFILE." if profile_text else ".")
    )
    input_blocks = _build_clinical_context_blocks(
        question,
        patient_profile=patient_profile,
        extra_blocks=[topic_block, task_instruction],
    )

    questions: dict[str, dict[str, Any]] = {}
    questions["is_topic_relevant"] = noul_question(
        f"Is topic '{topic_title}' relevant to answering the clinical question?",
        true_description="This topic contains clinical information pertinent to the inquiry.",
        false_description="This topic is unrelated or off-target for the clinical question.",
    )

    is_drug_topic = (topic_type or "").lower() == "drug"
    if not is_drug_topic:
        questions["needs_drug_monograph"] = noul_question(
            f"While inspecting topic '{topic_title}', does the question also require a separate dedicated drug dosing monograph (renal dosing, interactions, formulation, or pediatric weight dosing)?",
            true_description="A dedicated drug monograph is needed in addition to this disease/management topic.",
            false_description="This topic (or its sections/tables) likely contains sufficient information without an external drug monograph.",
        )

    sec_title_map = {str(s.get("id")): str(s.get("title", "")) for s in sections if s.get("id")}
    sec_map: dict[str, tuple[str, str]] = {}
    for idx, sec in enumerate(trimmed_sections):
        sid = str(sec.get("id", "")).strip()
        stitle = str(sec.get("title", "")).strip()
        q_key = sanitize_question_key(f"sec_{idx}_{sid}", prefix="sec", index=idx)
        sec_map[q_key] = (sid, stitle)
        pid = sec.get("parentId")
        parent_title = sec_title_map.get(str(pid)) if pid else None
        hierarchical_label = f"{parent_title} > {stitle}" if parent_title else stitle
        questions[q_key] = score_question(
            f"Relevance of section '{hierarchical_label}'",
            _RELEVANCE_RUBRIC,
        )

    gfx_map: dict[str, tuple[str, str]] = {}
    for idx, gfx in enumerate(trimmed_graphics):
        gid = str(gfx.get("id", "")).strip()
        gtitle = str(gfx.get("title", "")).strip()
        q_key = sanitize_question_key(f"gfx_{idx}_{gid}", prefix="gfx", index=idx)
        gfx_map[q_key] = (gid, gtitle)
        questions[q_key] = score_question(
            f"Relevance of table '{gtitle}'",
            _RELEVANCE_RUBRIC,
        )

    rel_map: dict[str, tuple[str, str]] = {}
    for idx, rel in enumerate(trimmed_related):
        rid = str(rel.get("id", "")).strip()
        rtitle = str(rel.get("title", "")).strip()
        q_key = sanitize_question_key(f"rel_{idx}_{rid}", prefix="rel", index=idx)
        rel_map[q_key] = (rid, rtitle)
        questions[q_key] = score_question(
            f"Relevance of linked topic '{rtitle}'",
            _RELATED_TOPIC_RUBRIC,
        )

    batch = client.decide(input_blocks, questions)
    if not batch.ok and not batch.answers:
        return OverviewRerankResult(
            topic_id=topic_id,
            topic_title=topic_title,
            ok=False,
            error=batch.error,
            input_tokens=batch.usage.total_input_tokens,
        )

    def _extract_items(mapping: dict[str, tuple[str, str]], kind: str) -> list[RankedItem]:
        items = []
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
        items.sort(key=lambda it: (it.score, it.confidence), reverse=True)
        return items

    sec_scores = _extract_items(sec_map, "section")
    gfx_scores = _extract_items(gfx_map, "table")
    rel_scores = _extract_items(rel_map, "related_topic")

    # Prevent H1 parent bloat: if any descendant of an H1 section also passed threshold,
    # penalize broad ancestor so specific child subsection is preferred.
    section_level_map = {str(s.get("id")): int(s.get("level", 1)) for s in sections if s.get("id")}
    parent_map = {str(s.get("id")): s.get("parentId") for s in sections if s.get("id")}
    ancestors_with_qualifying_descendants = set()
    for sec in sec_scores:
        p = parent_map.get(sec.id)
        while p:
            if sec.score >= cfg.section_score_threshold:
                ancestors_with_qualifying_descendants.add(str(p))
            p = parent_map.get(str(p))

    def _sec_sort_key(item: RankedItem) -> tuple[float, float]:
        level = section_level_map.get(item.id, 1)
        specificity_bonus = 0.2 if level > 1 else 0.0
        ancestor_penalty = -0.5 if item.id in ancestors_with_qualifying_descendants else 0.0
        return (item.score + specificity_bonus + ancestor_penalty, item.confidence)

    qualifying_sections = [
        item for item in sec_scores if item.score >= cfg.section_score_threshold
    ]
    qualifying_sections.sort(key=_sec_sort_key, reverse=True)
    sections_passing = [item.id for item in qualifying_sections]

    dedup_passing = filter_subsumed_sections(sections_passing, sections)[: cfg.top_k_sections]
    if dedup_passing:
        rec_sections = dedup_passing
    elif sec_scores:
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
    drug_prob = mono_ans.noul if (mono_ans and not is_drug_topic) else 0.0
    needs_mono = bool(drug_prob >= cfg.overview_drug_monograph_threshold)

    best_section_score = sec_scores[0].score if sec_scores else 0.0
    dominant_related = next(
        (r for r in rel_scores if r.id != topic_id and r.score >= 2.6 and (r.score > best_section_score or r.score >= 2.8)),
        None
    )
    needs_related_topic = dominant_related is not None

    hint_lines = []
    if rec_sections:
        top_sec_str = ", ".join(rec_sections)
        hint_lines.append(f"Recommended sectionIds for getTopicSectionsText: {top_sec_str}.")
    elif sec_scores and all(s.score < 1.0 for s in sec_scores):
        hint_lines.append(
            "All sections in this topic scored low relevance (<1.0); check relatedTopics or refine searchTopics."
        )
    if rec_graphics:
        top_gfx_str = ", ".join(rec_graphics)
        hint_lines.append(f"Recommended table graphicIds: {top_gfx_str}.")
    if rec_related:
        top_rel_str = ", ".join(
            f"[{item.id}] {item.title} ({item.score:.2f})"
            for item in rel_scores
            if item.id in rec_related
        )
        hint_lines.append(f"High-signal relatedTopics: {top_rel_str}.")
    if needs_mono:
        hint_lines.append(
            "Consider checking a dedicated Lexidrug monograph if exact dosing is required."
        )
    if needs_related_topic:
        hint_lines.append("Dedicated specialty related topic likely needed.")

    return OverviewRerankResult(
        ok=batch.ok,
        error=batch.error,
        topic_id=topic_id,
        topic_title=topic_title,
        recommended_section_ids=rec_sections,
        recommended_graphic_ids=rec_graphics,
        recommended_related_topic_ids=rec_related,
        section_scores=sec_scores,
        graphic_scores=gfx_scores,
        related_topic_scores=rel_scores,
        needs_drug_monograph=needs_mono,
        needs_related_topic=needs_related_topic,
        drug_monograph_prob=round(drug_prob, 3),
        monograph_confidence=round(mono_ans.confidence, 3) if mono_ans else 0.0,
        hint=" ".join(hint_lines),
        input_tokens=batch.usage.total_input_tokens,
    )


def inject_search_rerank_into_json(
    raw_json: str,
    rerank: SearchRerankResult,
    *,
    auto_chained_topic_id: Optional[str] = None,
    auto_chained_topic_ids: Optional[list[str]] = None,
    auto_prefetched: bool = False,
    needs_drug_monograph: Optional[bool] = None,
    needs_related_topic: bool = False,
    promising_related_summary: Optional[str] = None,
    prefetched_preview: Optional[str] = None,
) -> str:
    """Injects calibrated search rerank and auto-chaining outcomes into searchTopics response JSON."""
    try:
        data = json.loads(raw_json)
    except Exception:
        return raw_json
    if not isinstance(data, dict):
        return raw_json

    effective_drug = rerank.requires_drug_monograph if needs_drug_monograph is None else needs_drug_monograph
    hint_parts = []
    if auto_chained_topic_ids:
        tid_str = ", ".join(f"[{t}]" for t in auto_chained_topic_ids)
        hint_parts.append(f"Auto-selected candidate(s) {tid_str} based on top Decision API relevance scores.")
    elif auto_chained_topic_id:
        hint_parts.append(f"Auto-selected candidate [{auto_chained_topic_id}] based on top Decision API relevance score.")
    elif rerank.top_topics:
        hint_parts.append(
            "Top topic candidates: "
            + ", ".join(f"[{r.id}] {r.title} (score={r.score:.2f})" for r in rerank.top_topics)
        )

    if effective_drug:
        hint_parts.append("Dosing/adjustment intent detected: ensure a `drug` monograph topic is also consulted.")
    if needs_related_topic:
        rel_note = f" (e.g. {promising_related_summary})" if promising_related_summary else ""
        hint_parts.append(f"Specialized clinical sub-topic needed{rel_note}.")
    if auto_prefetched:
        preview_note = " Regimen excerpt included in 'prefetchedPreview' to identify drug monographs." if prefetched_preview else ""
        hint_parts.append(f"Sections and tables already prefetched into context.{preview_note}")

    payload: dict[str, Any] = {
        "topTopics": [
            {"id": item.id, "title": item.title, "score": item.score, "type": item.kind}
            for item in rerank.top_topics
        ],
        "requiresDrugMonograph": effective_drug,
        "requiresTableData": rerank.requires_table_data,
        "needsRelatedTopic": needs_related_topic,
        "hint": " ".join(hint_parts),
    }
    if auto_chained_topic_id:
        payload["autoChainedTopicId"] = auto_chained_topic_id
    if auto_chained_topic_ids:
        payload["autoChainedTopicIds"] = auto_chained_topic_ids
    if auto_prefetched:
        payload["autoPrefetched"] = True
    if prefetched_preview:
        payload["prefetchedPreview"] = prefetched_preview

    data["decisionRerank"] = payload
    return json.dumps(data, ensure_ascii=False)


def inject_overview_rerank_into_json(
    raw_json: str,
    rerank: OverviewRerankResult,
    *,
    auto_prefetched: bool = False,
    prefetched_sections_chars: int = 0,
    prefetched_tables_chars: int = 0,
    prune_low_scoring_sections: bool = True,
    prefetched_preview: Optional[str] = None,
    needs_related_topic: bool = False,
) -> str:
    """Injects overview rerank into getTopicOverview response, optionally pruning low-scoring sections."""
    try:
        data = json.loads(raw_json)
    except Exception:
        return raw_json
    if not isinstance(data, dict):
        return raw_json

    hint_parts = []
    if rerank.recommended_section_ids:
        sec_prefix = "Auto-prefetched sectionIds" if auto_prefetched else "Recommended sectionIds for getTopicSectionsText"
        hint_parts.append(f"{sec_prefix}: {', '.join(rerank.recommended_section_ids)}.")
    elif rerank.section_scores and all(s.score < 1.0 for s in rerank.section_scores):
        hint_parts.append("All sections in this topic scored low relevance (<1.0); check relatedTopics or refine searchTopics.")

    if rerank.recommended_graphic_ids:
        gfx_prefix = "Auto-prefetched table graphicIds" if auto_prefetched else "Recommended table graphicIds for getGraphicContent"
        hint_parts.append(f"{gfx_prefix}: {', '.join(rerank.recommended_graphic_ids)}.")
    if rerank.recommended_related_topic_ids:
        rel_str = ", ".join(f"[{r.id}] {r.title}" for r in rerank.related_topic_scores if r.id in rerank.recommended_related_topic_ids)
        hint_parts.append(f"Consider related topics if initial sections lack needed depth: {rel_str}.")
    if rerank.needs_drug_monograph:
        hint_parts.append("Consider checking a dedicated Lexidrug monograph if exact dosing is required.")
    if needs_related_topic:
        hint_parts.append("Dedicated specialty related topic likely needed.")
    if auto_prefetched:
        char_info = f" ({prefetched_sections_chars} chars text, {prefetched_tables_chars} chars tables)" if (prefetched_sections_chars > 0 or prefetched_tables_chars > 0) else ""
        preview_note = " Regimen excerpt included in 'prefetchedPreview' to identify drug monographs." if prefetched_preview else ""
        hint_parts.append(f"Auto-prefetched evidence{char_info} is already added to retrieved evidence; only fetch additional sections if a gap remains.{preview_note}")

    payload: dict[str, Any] = {
        "recommendedSectionIds": rerank.recommended_section_ids,
        "recommendedGraphicIds": rerank.recommended_graphic_ids,
        "recommendedRelatedTopics": [
            {"id": item.id, "title": item.title, "score": item.score}
            for item in rerank.related_topic_scores
            if item.id in rerank.recommended_related_topic_ids
        ],
        "needsDrugMonograph": rerank.needs_drug_monograph,
        "needsRelatedTopic": needs_related_topic,
        "hint": " ".join(hint_parts),
    }
    if auto_prefetched:
        payload["autoPrefetched"] = True
        payload["prefetchedSectionsChars"] = prefetched_sections_chars
        payload["prefetchedTablesChars"] = prefetched_tables_chars
        if prefetched_preview:
            payload["prefetchedPreview"] = prefetched_preview

    # Prune low-scoring sections when auto-prefetched and list is long
    sections_list = data.get("sections")
    if auto_prefetched and prune_low_scoring_sections and isinstance(sections_list, list) and len(sections_list) > 15:
        kept_ids = set(rerank.recommended_section_ids) | {
            s.id for s in rerank.section_scores if s.score >= 1.0
        }
        data["sections"] = [
            s for s in sections_list
            if not isinstance(s, dict) or not s.get("id") or str(s.get("id")) in kept_ids
        ]

    data["decisionRerank"] = payload
    return json.dumps(data, ensure_ascii=False)


def inject_rerank_into_tool_json(raw_json: str, rerank_payload: dict[str, Any]) -> str:
    """Attach a `decisionRerank` dictionary to a JSON tool payload (backwards-compatibility helper)."""
    try:
        data = json.loads(raw_json)
    except Exception:
        return raw_json
    if not isinstance(data, dict):
        return raw_json
    data["decisionRerank"] = rerank_payload
    return json.dumps(data, ensure_ascii=False)
