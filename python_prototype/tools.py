"""LangChain tools for medical database access.

Mirrors Kotlin MedicalDatabaseTools — 7 tools:
1. search_topics — FTS search
2. get_topic_outline — Section list for a topic
3. get_related_topics — Related topic IDs + titles for candidate pool
4. get_topic_sections_text — Batch section retrieval
5. get_graphic_content — Table data as markdown (or error for non-table)
6. submit_clinical_answer — Terminal tool for structured citations
"""

import json
from typing import Optional

from langchain_core.tools import tool
from pydantic import BaseModel, Field

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


class TopicRefPayload(BaseModel):
    """Structured topic reference for submit_clinical_answer."""
    topic_id: str
    section_id: str = ""
    label: str


class GraphicRefPayload(BaseModel):
    """Structured graphic reference for submit_clinical_answer."""
    graphic_id: str
    label: str


def init_tools(db: ClinRefDatabase) -> list:
    """Initialize tools with a database instance. Returns tool list."""
    global _db
    _db = db
    return [search_topics, get_topic_outline, get_related_topics,
            get_topic_sections_text, get_graphic_content,
            submit_clinical_answer]


@tool
def search_topics(query: str) -> str:
    """Search medical topics by focused core keywords (e.g., 'apixaban', 'asthma', 'gout').

    Returns matching topics, optional inline outline for top match, and 'refine_with' suggestions.
    For multi-concept questions, execute separate searches per concept.
    Avoid searching full patient sentences or lab measurements.

    Returns: {query, results: [{id, title, outline: Optional[{sections, graphics}]}], refine_with: [suggested queries], message}
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    clean_query = query.strip()
    if not clean_query:
        return json.dumps({"results": [], "refine_with": []})

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

    # Speculative Bundling: embed outline for top match if asset exists
    formatted_results = []
    for i, r in enumerate(results):
        tid = r["id"]
        res_dict = {"id": tid, "title": r["title"]}
        if i == 0:
            outline_html = _db.get_topic_outline(tid)
            if outline_html and isinstance(outline_html, str):
                sections = extract_outline_sections(outline_html)
                graphics = extract_graphics_from_outline(outline_html)
                related = extract_related_topics(outline_html)
                res_dict["outline"] = {
                    "sections": sections[:10],
                    "graphics": graphics,
                    "related_topics": related,
                }
        formatted_results.append(res_dict)

    return json.dumps({
        "query": clean_query,
        "results": formatted_results,
        "refine_with": suggestions,
        "message": "Success",
    })


@tool
def get_topic_outline(topic_id: str) -> str:
    """Retrieve the topic outline containing section IDs, titles, graphic metadata, and related topics.

    ALWAYS call this after search_topics to obtain sectionId values for get_topic_sections_text.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    clean_id = topic_id.strip()
    outline_html = _db.get_topic_outline(clean_id)
    if not outline_html:
        return json.dumps({"error": f"Topic not found: {clean_id}"})

    title = _db.get_topic_title(clean_id) or clean_id
    sections = extract_outline_sections(outline_html)
    graphics = extract_graphics_from_outline(outline_html)
    related = extract_related_topics(outline_html)

    return json.dumps({
        "topicId": clean_id,
        "title": title,
        "sections": sections,
        "graphics": graphics,
        "related_topics": related,
    }, indent=2)


@tool
def get_related_topics(topic_id: str) -> str:
    """Get related topic IDs and titles for a topic. Use this to build a candidate pool before fetching sections."""
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    clean_id = topic_id.strip()
    outline_html = _db.get_topic_outline(clean_id)
    if not outline_html:
        return json.dumps({"error": f"Topic not found: {clean_id}"})

    related = extract_related_topics(outline_html)
    return json.dumps({
        "topicId": clean_id,
        "related_topics": related,
    }, indent=2)


@tool
def get_topic_sections_text(topic_id: str, section_ids: list[str]) -> str:
    """Retrieve multiple sections from the same topic in a single call."""
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    clean_topic_id = topic_id.strip()
    body_html = _db.get_topic_body(clean_topic_id)
    outline_html = _db.get_topic_outline(clean_topic_id)

    if not body_html or not outline_html:
        return "Topic not found"

    title_val = _db.get_topic_title(clean_topic_id)
    topic_title = title_val if isinstance(title_val, str) and title_val else clean_topic_id

    if isinstance(section_ids, str):
        section_ids = [section_ids]

    # Build section ID -> title map from outline
    outline_sections = extract_outline_sections(outline_html)
    id_to_title = {s["id"]: s["title"] for s in outline_sections}

    # Validate: filter out section IDs that don't exist in the outline
    valid_ids = []
    invalid_ids = []
    for sid in section_ids:
        clean_sid = sid.strip()
        if clean_sid in id_to_title:
            valid_ids.append(clean_sid)
        else:
            invalid_ids.append(clean_sid)

    if not valid_ids and invalid_ids:
        return json.dumps({
            "topicTitle": topic_title,
            "sectionTitles": {},
            "markdown": "",
            "invalidSections": invalid_ids,
        })

    sections_md = []
    section_titles = {}
    for section_id in valid_ids:
        title = id_to_title.get(section_id, "")
        section_titles[section_id] = title
        section_html = extract_section_html(body_html, outline_html, section_id)
        if section_html:
            markdown = html_to_markdown(section_html)
            sections_md.append(f"=== Section: {section_id} ===\n{markdown}")
        else:
            sections_md.append(f"=== Section: {section_id} ===\nSection not found.")

    result = {
        "topicTitle": topic_title,
        "sectionTitles": section_titles,
        "markdown": "\n\n".join(sections_md),
    }
    if invalid_ids:
        result["invalidSections"] = invalid_ids

    return json.dumps(result)





@tool
def get_graphic_content(graphic_id: str) -> str:
    """Retrieve graphic content for a graphic of type 'graphic_table' as formatted Markdown.

    Do NOT call for non-table graphics (figures, images, algorithms) as visual details cannot be analyzed.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    clean_id = graphic_id.strip()
    raw_id = clean_id.removeprefix("Graphic-").removeprefix("graphic-")

    asset = _db.get_graphic_asset(raw_id)
    if not asset:
        return json.dumps({"error": f"Graphic {clean_id} not found"})

    graphic_info = asset.get("graphicInfo", {})
    subtype = graphic_info.get("subtype", "")
    if subtype and subtype != "graphic_table":
        return json.dumps({"error": f"Graphic {clean_id} is of type '{subtype}', not a table. Only table content is retrievable."})

    table_html = asset.get("imageHtml", "")
    if not table_html:
        return json.dumps({"error": f"Graphic {clean_id} has empty content"})

    title = graphic_info.get("displayName") or graphic_info.get("title") or raw_id
    markdown = table_to_markdown(table_html)
    return f"### Graphic Table: {title}\n\n{markdown}"


@tool
def submit_clinical_answer(
    answer_text: str,
    no_data_found: bool = False,
) -> str:
    """MUST be called to present your final clinical answer to the user.

    Provide the final text response and indicate if data was unavailable.
    References are automatically extracted from your tool calls.

    Args:
        answer_text: The formatted markdown response text for the clinician.
        no_data_found: Set to true ONLY if the database search yielded no relevant clinical information.
    """
    return json.dumps({
        "status": "SUBMITTED",
        "answer": answer_text,
        "noDataFound": no_data_found,
    })
