"""LangChain tools for medical database access.

Mirrors Kotlin MedicalDatabaseTools — 5 tools:
1. search_topics — unidex curated topic search
2. get_topic_outline — Section list for a topic
3. get_related_topics — Related topic IDs + titles for candidate pool
4. get_topic_sections_text — Batch section retrieval
5. get_graphic_content — Table data as markdown (or error for non-table)
"""

import json
import re
from typing import Optional, Union

from langchain_core.tools import tool

from database import ClinRefDatabase
from html_parser import (
    extract_graphics_from_outline,
    extract_outline_sections,
    extract_related_topics,
    extract_section_html,
    html_to_markdown,
    table_to_markdown,
)

# Global database instance — initialized by run.py
_db: Optional[ClinRefDatabase] = None


def init_tools(db: ClinRefDatabase) -> list:
    """Initialize tools with a database instance. Returns tool list."""
    global _db
    _db = db
    return [
        get_topic_overview,
        search_topics,
        get_topic_outline,
        search_topic_sections,
        get_related_topics,
        get_topic_sections_text,
        get_graphic_content,
    ]





def resolve_section_ids(requested_ids: list[str], id_to_title: dict[str, str]) -> tuple[list[str], list[str]]:
    """Smart-resolve section IDs supporting exact ID, case-insensitive ID, or title match.

    Cascade:
    1. Exact ID match (clean in id_to_title)
    2. Case-insensitive ID match (k.lower() == clean.lower())
    3. Section title match (both raw title and stripped leading bullet/dash)

    Returns:
        (valid_ids, invalid_ids) deduplicated in order of appearance.
    """
    valid_ids: list[str] = []
    invalid_ids: list[str] = []

    for req in requested_ids:
        clean = req.strip()
        if not clean:
            continue

        if clean in id_to_title:
            if clean not in valid_ids:
                valid_ids.append(clean)
        else:
            # Step 2: Case-insensitive ID match
            matched_id = next((k for k in id_to_title if k.lower() == clean.lower()), None)

            # Step 3: Section title match (raw title or stripped bullet/dash)
            if not matched_id:
                clean_stripped = re.sub(r"^[-–—•●\s]+", "", clean).lower()
                matched_id = next(
                    (
                        k for k, title in id_to_title.items()
                        if title.lower() == clean.lower()
                        or re.sub(r"^[-–—•●\s]+", "", title).lower() == clean_stripped
                    ),
                    None,
                )

            if matched_id:
                if matched_id not in valid_ids:
                    valid_ids.append(matched_id)
            else:
                invalid_ids.append(clean)

    return valid_ids, invalid_ids


@tool
def get_topic_overview(
    topic_id: str = "",
    topicId: Optional[str] = None,
    **kwargs,
) -> str:
    """Retrieve the topic outline hierarchy, table graphics, and related topics in a single round-trip.

    Bundles outline metadata (section IDs and titles), table graphics, and related topics (or full calculator content).
    Inspect the outline hierarchy to select relevant section IDs for the patient presentation,
    then fetch their content with get_topic_sections_text.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    effective_topic_id = topic_id or topicId or kwargs.get("topicId") or kwargs.get("topic_id") or ""
    clean_topic_id = str(effective_topic_id).strip()
    if not clean_topic_id:
        return json.dumps({"error": "Topic not found: "})

    if ClinRefDatabase._extract_numeric_id(clean_topic_id) is None:
        return json.dumps({"error": f"Topic not found: {clean_topic_id}"})

    body_html = _db.get_topic_body(clean_topic_id)
    outline_html = _db.get_topic_outline(clean_topic_id)
    asset = _db.get_topic_asset(clean_topic_id) if hasattr(_db, "get_topic_asset") else None
    if asset is not None and not isinstance(asset, dict):
        asset = None

    if not body_html and not outline_html and not asset:
        return json.dumps({"error": f"Topic not found: {clean_topic_id}"})

    title_val = _db.get_topic_title(clean_topic_id)
    title = title_val if isinstance(title_val, str) and title_val else clean_topic_id

    topic_type = ClinRefDatabase.get_topic_type(asset) if asset else ("calc" if not outline_html else "article")

    # Calculator topic handling
    if topic_type == "calc" or not outline_html:
        calc_body = body_html or (asset.get("bodyHtml", "") if asset else "")
        calc_markdown = html_to_markdown(calc_body)
        header = f"=== Calculator: {title} ==="
        full_md = f"{header}\n{calc_markdown}"
        return json.dumps({
            "topicId": clean_topic_id,
            "topic_id": clean_topic_id,
            "title": title,
            "topicTitle": title,
            "topic_title": title,
            "topicType": "calc",
            "topic_type": "calc",
            "outline": [{"id": "FULL", "title": "Calculator Tool Content", "level": 1}],
            "sections": [{"id": "FULL", "title": "Calculator Tool Content", "level": 1}],
            "graphics": [],
            "relatedTopics": [],
            "related_topics": [],
            "markdown": full_md,
        }, indent=2, ensure_ascii=False)

    outline_sections = extract_outline_sections(outline_html or "")
    graphics = extract_graphics_from_outline(outline_html or "")
    # NOTE: the parser emits snake_case `is_table`; do not probe camelCase only (parity with Kotlin `isTable`)
    table_graphics = [g for g in graphics if g.get("is_table", g.get("isTable", True))]
    for g in table_graphics:
        g["isTable"] = True
    related = extract_related_topics(outline_html or "")

    return json.dumps({
        "topicId": clean_topic_id,
        "topic_id": clean_topic_id,
        "title": title,
        "topicTitle": title,
        "topic_title": title,
        "topicType": topic_type,
        "topic_type": topic_type,
        "outline": outline_sections,
        "sections": outline_sections,
        "graphics": table_graphics,
        "relatedTopics": related,
        "related_topics": related,
        "markdown": "",
    }, indent=2, ensure_ascii=False)



@tool
def search_topics(
    query: str = "",
    search_query: Optional[str] = None,
    q: Optional[str] = None,
    keyword: Optional[str] = None,
    query_text: Optional[str] = None,
    queryText: Optional[str] = None,
    searchQuery: Optional[str] = None,
    **kwargs,
) -> str:
    """Search medical topics by focused core keywords (e.g., 'apixaban', 'asthma', 'gout').

    Returns matching candidate topics ({id, title}) and 'refine_with' suggestions.
    For multi-concept questions, execute separate searches per concept.
    Avoid searching full patient sentences or lab measurements.

    Returns: {query, results: [{id, title}], refine_with: [suggested queries], message}
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    effective_query = (
        query
        or search_query
        or q
        or keyword
        or query_text
        or queryText
        or searchQuery
        or kwargs.get("query")
        or kwargs.get("search_query")
        or kwargs.get("q")
        or kwargs.get("keyword")
        or kwargs.get("query_text")
        or kwargs.get("queryText")
        or kwargs.get("searchQuery")
        or ""
    )
    clean_query = str(effective_query).strip()
    if not clean_query:
        return json.dumps({
            "query": "",
            "results": [],
            "refine_with": [],
            "message": "",
        })

    results = _db.search_topics(clean_query, limit=10)
    suggestions = _db.get_suggestions(clean_query, limit=20)

    # Auto-retry: if primary query returns no results, try available suggestions internally (up to top 5)
    if not results and suggestions:
        for suggestion in suggestions[:5]:
            retry_results = _db.search_topics(suggestion, limit=10)
            if retry_results:
                remaining_suggestions = [s for s in suggestions if s != suggestion]
                results = retry_results
                suggestions = remaining_suggestions
                clean_query = suggestion
                break

    if not results:
        message = (
            f"No topic match and no suggestions available for '{query}'."
            if not suggestions
            else f"No results for '{query}'. Try one of the 'refine_with' suggestions."
        )
        return json.dumps({
            "query": query,
            "results": [],
            "refine_with": suggestions,
            "message": message,
        })

    # Pure candidate topics list
    formatted_results = [{"id": r["id"], "title": r["title"]} for r in results]

    return json.dumps({
        "query": clean_query,
        "results": formatted_results,
        "refine_with": suggestions,
        "message": "Success",
    })


@tool
def get_topic_outline(
    topic_id: str = "",
    topicId: Optional[str] = None,
    **kwargs,
) -> str:
    """Retrieve the topic outline containing section IDs, titles, graphic metadata, and related topics.

    ALWAYS call this after search_topics to obtain sectionId values for get_topic_sections_text.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    effective_topic_id = topic_id or topicId or kwargs.get("topic_id") or kwargs.get("topicId") or ""
    clean_id = str(effective_topic_id).strip()
    if not clean_id or ClinRefDatabase._extract_numeric_id(clean_id) is None:
        return json.dumps({"error": f"Topic not found: {clean_id}"})

    outline_html = _db.get_topic_outline(clean_id)
    asset = _db.get_topic_asset(clean_id) if (not outline_html and hasattr(_db, "get_topic_asset")) else None
    if asset is not None and not isinstance(asset, dict):
        asset = None

    if not outline_html and not (asset and asset.get("bodyHtml")):
        return json.dumps({"error": f"Topic not found: {clean_id}"})

    title = _db.get_topic_title(clean_id) or clean_id
    t_type = ClinRefDatabase.get_topic_type(asset) if asset else ("calc" if not outline_html else "article")

    if t_type == "calc":
        return json.dumps({
            "topicId": clean_id,
            "title": title,
            "topicType": "calc",
            "topic_type": "calc",
            "sections": [{"id": "FULL", "title": "Calculator Tool Content"}],
            "graphics": [],
            "relatedTopics": [],
            "related_topics": [],
        }, indent=2)

    sections = extract_outline_sections(outline_html or "")
    graphics = [g for g in extract_graphics_from_outline(outline_html or "") if g.get("is_table")]
    for g in graphics:
        g["isTable"] = True
    related = extract_related_topics(outline_html or "")

    return json.dumps({
        "topicId": clean_id,
        "title": title,
        "topicType": "article",
        "topic_type": "article",
        "sections": sections,
        "graphics": graphics,
        "relatedTopics": related,
        "related_topics": related,
    }, indent=2)


@tool
def get_related_topics(
    topic_id: str = "",
    topicId: Optional[str] = None,
    **kwargs,
) -> str:
    """Get related topic IDs and titles for a topic. Use this to build a candidate pool before fetching sections."""
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    effective_topic_id = topic_id or topicId or kwargs.get("topic_id") or kwargs.get("topicId") or ""
    clean_id = str(effective_topic_id).strip()
    if not clean_id or ClinRefDatabase._extract_numeric_id(clean_id) is None:
        return json.dumps({"error": f"Topic not found: {clean_id}"})

    outline_html = _db.get_topic_outline(clean_id)
    if not outline_html:
        # Calculator topics have no outlineHtml but do exist — return an
        # empty pool instead of an error (mirrors Kotlin getRelatedTopics).
        asset = _db.get_topic_asset(clean_id) if hasattr(_db, "get_topic_asset") else None
        if asset is not None and isinstance(asset, dict):
            return json.dumps({
                "topicId": clean_id,
                "relatedTopics": [],
                "related_topics": [],
            }, indent=2)
        return json.dumps({"error": f"Topic not found: {clean_id}"})

    related = extract_related_topics(outline_html)
    return json.dumps({
        "topicId": clean_id,
        "relatedTopics": related,
        "related_topics": related,
    }, indent=2)


@tool
def get_topic_sections_text(
    topic_id: str = "",
    section_ids: Optional[Union[list[str], str]] = None,
    max_tokens: int = 4000,
    topicId: Optional[str] = None,
    sectionIds: Optional[Union[list[str], str]] = None,
    maxTokens: Optional[int] = None,
    **kwargs,
) -> str:
    """Retrieve multiple sections from the same topic in a single call, bounded by a token budget (default 4000 tokens / ~16000 characters)."""
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    effective_topic_id = topic_id or topicId or kwargs.get("topic_id") or kwargs.get("topicId") or ""
    clean_topic_id = str(effective_topic_id).strip()
    if not clean_topic_id or ClinRefDatabase._extract_numeric_id(clean_topic_id) is None:
        return json.dumps({"error": f"Topic not found: {clean_topic_id}"})

    raw_sids = (
        section_ids
        if section_ids is not None
        else (
            sectionIds
            if sectionIds is not None
            else kwargs.get("section_ids", kwargs.get("sectionIds", []))
        )
    )
    if isinstance(raw_sids, str):
        raw_sids = [raw_sids]
    elif raw_sids is None:
        raw_sids = []

    raw_max_tokens = maxTokens if maxTokens is not None else kwargs.get("max_tokens", kwargs.get("maxTokens", max_tokens))
    try:
        token_budget = int(raw_max_tokens)
        if token_budget <= 0:
            token_budget = 4000
    except (TypeError, ValueError):
        token_budget = 4000
    max_chars = token_budget * 4

    body_html = _db.get_topic_body(clean_topic_id)
    outline_html = _db.get_topic_outline(clean_topic_id)
    asset = _db.get_topic_asset(clean_topic_id) if (not outline_html and hasattr(_db, "get_topic_asset")) else None
    if asset is not None and not isinstance(asset, dict):
        asset = None

    if not body_html:
        return json.dumps({"error": f"Topic not found: {clean_topic_id}"})

    title_val = _db.get_topic_title(clean_topic_id)
    topic_title = title_val if isinstance(title_val, str) and title_val else clean_topic_id

    t_type = ClinRefDatabase.get_topic_type(asset) if asset else ("calc" if not outline_html else "article")
    clean_sids = [sid.strip().upper() for sid in raw_sids if isinstance(sid, str)]

    # Explicit handling for calculator topic type or full text requests
    if t_type == "calc" or not outline_html or "FULL" in clean_sids or "ALL" in clean_sids:
        markdown = html_to_markdown(body_html)
        header = f"=== Calculator: {topic_title} ===" if t_type == "calc" else "=== Section: FULL ==="
        full_text = f"{header}\n{markdown}"
        is_truncated = False
        if len(full_text) > max_chars:
            full_text = full_text[:max_chars].rstrip() + "\n\n[Content truncated due to token limit]"
            is_truncated = True
        resp = {
            "topicId": clean_topic_id,
            "topicTitle": topic_title,
            "topic_type": t_type,
            "sectionTitles": {"FULL": topic_title},
            "markdown": full_text,
        }
        if is_truncated:
            resp["truncated"] = True
        return json.dumps(resp, indent=2, ensure_ascii=False)

    # Build section ID -> title map from outline
    outline_sections = extract_outline_sections(outline_html or "")
    id_to_title = {s["id"]: s["title"] for s in outline_sections}

    # Validate & smart-resolve section IDs (exact ID, case-insensitive ID, or title match)
    target_sids = raw_sids if raw_sids else list(id_to_title.keys())[:3]
    valid_ids, invalid_ids = resolve_section_ids(target_sids, id_to_title)

    if not valid_ids and invalid_ids:
        return json.dumps({
            "topicId": clean_topic_id,
            "topicTitle": topic_title,
            "sectionTitles": {},
            "markdown": "",
            "invalidSections": invalid_ids,
        })

    sections_md = []
    section_titles = {}
    omitted_sections = []
    current_chars = 0
    is_truncated = False

    for section_id in valid_ids:
        title = id_to_title.get(section_id, "")
        section_html = extract_section_html(body_html, outline_html or "", section_id)
        if section_html:
            markdown = html_to_markdown(section_html)
            sec_entry = f"=== Section: {section_id} ===\n{markdown}"
        else:
            sec_entry = f"=== Section: {section_id} ===\nSection not found."

        if is_truncated:
            omitted_sections.append(section_id)
            continue

        entry_len = len(sec_entry)
        separator_len = 2 if sections_md else 0

        if current_chars + separator_len + entry_len <= max_chars:
            sections_md.append(sec_entry)
            section_titles[section_id] = title
            current_chars += separator_len + entry_len
        else:
            remaining = max_chars - (current_chars + separator_len)
            if not sections_md or remaining >= 200:
                header_prefix = f"=== Section: {section_id} ===\n"
                cutoff = max(len(header_prefix) + 20, remaining - 50)
                partial = sec_entry[:cutoff].rstrip() + "\n\n[Section truncated due to token limit]"
                sections_md.append(partial)
                section_titles[section_id] = title
                current_chars += separator_len + len(partial)
            else:
                omitted_sections.append(section_id)
            is_truncated = True

    result = {
        "topicId": clean_topic_id,
        "topicTitle": topic_title,
        "sectionTitles": section_titles,
        "markdown": "\n\n".join(sections_md),
    }
    if invalid_ids:
        result["invalidSections"] = invalid_ids
    if omitted_sections:
        result["omittedSections"] = omitted_sections
    if is_truncated:
        result["truncated"] = True

    return json.dumps(result, indent=2, ensure_ascii=False)


@tool
def search_topic_sections(
    topic_id: str = "",
    query: str = "",
    topicId: Optional[str] = None,
    search_query: Optional[str] = None,
    q: Optional[str] = None,
    keyword: Optional[str] = None,
    query_text: Optional[str] = None,
    queryText: Optional[str] = None,
    searchQuery: Optional[str] = None,
    **kwargs,
) -> str:
    """Search sections within a specific topic by keywords (e.g., 'dosing', 'pregnancy', 'renal').

    Returns matching section IDs, titles, hierarchy level, and content snippets.
    Use for long topics to pinpoint exact section IDs before fetching full text.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    effective_topic_id = topic_id or topicId or kwargs.get("topic_id") or kwargs.get("topicId") or ""
    clean_topic_id = str(effective_topic_id).strip()

    effective_query = (
        query
        or search_query
        or q
        or keyword
        or query_text
        or queryText
        or searchQuery
        or kwargs.get("query")
        or kwargs.get("search_query")
        or kwargs.get("q")
        or kwargs.get("keyword")
        or kwargs.get("query_text")
        or kwargs.get("queryText")
        or kwargs.get("searchQuery")
        or ""
    )
    clean_query = str(effective_query).strip()

    if not clean_topic_id or not clean_query:
        return json.dumps({
            "topicId": clean_topic_id,
            "query": clean_query,
            "matches": [],
            "message": "Topic ID and query must be non-empty."
        })

    if ClinRefDatabase._extract_numeric_id(clean_topic_id) is None:
        return json.dumps({
            "topicId": clean_topic_id,
            "query": clean_query,
            "matches": [],
            "message": f"Topic {clean_topic_id} not found."
        })

    asset = _db.get_topic_asset(clean_topic_id)
    if not asset:
        return json.dumps({
            "topicId": clean_topic_id,
            "query": clean_query,
            "matches": [],
            "message": f"Topic {clean_topic_id} not found."
        })

    body_html = asset.get("bodyHtml", "")
    outline_html = asset.get("outlineHtml", "")
    sections = extract_outline_sections(outline_html or "")
    if not sections:
        return json.dumps({
            "topicId": clean_topic_id,
            "query": clean_query,
            "matches": [],
            "message": "No sections found in topic outline."
        })

    keywords = [k.lower() for k in clean_query.split() if k]
    matches = []

    for s in sections:
        sid = s["id"]
        title = s["title"]
        sec_html = extract_section_html(body_html, outline_html or "", sid) or ""
        sec_text = re.sub(r"<[^>]+>", " ", sec_html)
        sec_text = re.sub(r"\s+", " ", sec_text).strip()

        text_lower = sec_text.lower()
        title_lower = title.lower()

        title_hits = sum(1 for kw in keywords if kw in title_lower)
        text_hits = sum(1 for kw in keywords if kw in text_lower)

        if title_hits > 0 or text_hits > 0:
            first_idx = -1
            matched_kw = ""
            for kw in keywords:
                idx = text_lower.find(kw)
                if idx >= 0 and (first_idx < 0 or idx < first_idx):
                    first_idx = idx
                    matched_kw = kw

            if first_idx >= 0:
                start = max(0, first_idx - 60)
                end = min(len(sec_text), first_idx + len(matched_kw) + 90)
                snippet = ("..." if start > 0 else "") + sec_text[start:end].strip() + ("..." if end < len(sec_text) else "")
            else:
                snippet = sec_text[:150] + ("..." if len(sec_text) > 150 else "")

            matches.append({
                "sectionId": sid,
                "sectionTitle": title,
                "level": s.get("level", 1),
                "snippet": snippet,
                "score": title_hits * 3 + text_hits
            })

    matches.sort(key=lambda m: m["score"], reverse=True)
    top_matches = [{k: v for k, v in m.items() if k != "score"} for m in matches[:8]]

    return json.dumps({
        "topicId": clean_topic_id,
        "query": clean_query,
        "matches": top_matches,
        "message": f"Found {len(top_matches)} matching sections." if top_matches else f"No matching sections found for '{clean_query}'."
    })


@tool
def get_graphic_content(
    graphic_id: str = "",
    graphicId: Optional[str] = None,
    **kwargs,
) -> str:
    """Retrieve graphic content for a graphic of type 'graphic_table' as formatted Markdown.

    Do NOT call for non-table graphics (figures, images, algorithms) as visual details cannot be analyzed.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    effective_graphic_id = graphic_id or graphicId or kwargs.get("graphic_id") or kwargs.get("graphicId") or ""
    clean_id = str(effective_graphic_id).strip()
    # Mirror Kotlin MedicalDatabaseTools normalization: strip `graphic_`, `graphic-`,
    # `graphic_table_`, `graphic-table-`, `table_`/`table-` prefixes and fall back to the raw ID.
    raw_id = re.sub(r"(?i)^(?:graphic[-_]?(?:table[-_]?)?|table[-_]?)", "", clean_id)

    asset = _db.get_graphic_asset(raw_id) or (None if raw_id == clean_id else _db.get_graphic_asset(clean_id))
    if not asset:
        return json.dumps({"error": f"Graphic {clean_id} not found"})

    graphic_info = asset.get("graphicInfo", {})
    subtype = graphic_info.get("subtype", "")
    if subtype and subtype not in ("graphic_table", "table"):
        return json.dumps({"error": f"Graphic {clean_id} is of type '{subtype}', not a table. Only table content is retrievable."})

    table_html = asset.get("imageHtml", "")
    if not table_html:
        return json.dumps({"error": f"Graphic {clean_id} has empty content"})

    title = graphic_info.get("displayName") or graphic_info.get("title") or raw_id
    markdown = table_to_markdown(table_html)
    return f"### Graphic Table: {title}\n\n{markdown}"

