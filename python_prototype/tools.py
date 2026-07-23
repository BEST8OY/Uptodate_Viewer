"""LangChain tools for medical database access.

Mirrors Kotlin MedicalDatabaseTools — 5 tools:
1. search_topics — FTS search
2. get_topic_outline — Section list for a topic
3. get_topic_section_text — Full section content as markdown
4. follow_related_topic — Cross-referenced topics
5. get_graphic_content — Table data as markdown (or error for non-table)
"""

import json
from typing import Optional

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
    return [search_topics, get_topic_outline, get_topic_section_text,
            follow_related_topic, get_graphic_content]


@tool
def search_topics(query: str) -> str:
    """Search medical topics by focused core keywords (e.g., 'apixaban', 'asthma', 'gout').

    Returns matching topics and 'refine_with' suggestions for follow-up query refining.
    For multi-concept questions, execute separate searches per concept.
    Avoid searching full patient sentences or lab measurements.

    Returns: {query, results: [{id, title}], refine_with: [suggested queries], message}
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    clean_query = query.strip()
    if not clean_query:
        return json.dumps({"results": [], "refine_with": []})

    results = _db.search_topics(clean_query, limit=10)
    suggestions = _db.get_suggestions(clean_query, limit=20)

    response = {
        "query": clean_query,
        "results": [{"id": r["id"], "title": r["title"]} for r in results],
        "refine_with": suggestions,
        "message": "Success" if results else "No direct topic match found. Consider refining query with 'refine_with' suggestions.",
    }

    return json.dumps(response)


@tool
def get_topic_outline(topic_id: str) -> str:
    """Retrieve the topic outline containing section IDs, titles, graphic metadata, and related topics.

    ALWAYS call this after search_topics to obtain exact sectionId values for get_topic_section_text.
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
def get_topic_section_text(topic_id: str, section_id: str, section_title: str = "") -> str:
    """Retrieve full markdown text content of a specific section.

    Call this with sectionId retrieved from get_topic_outline.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    clean_topic_id = topic_id.strip()
    clean_section_id = section_id.strip()

    body_html = _db.get_topic_body(clean_topic_id)
    outline_html = _db.get_topic_outline(clean_topic_id)

    if not body_html or not outline_html:
        return "Topic not found"

    section_html = extract_section_html(body_html, outline_html, clean_section_id)
    if not section_html and section_title:
        # Fallback: try to find section by title
        sections = extract_outline_sections(outline_html)
        matched = next(
            (s for s in sections if section_title.lower() in s["title"].lower()
             or s["title"].lower() in section_title.lower()),
            None,
        )
        if matched:
            section_html = extract_section_html(body_html, outline_html, matched["id"])

    if not section_html:
        return "Section not found."

    return html_to_markdown(section_html)


@tool
def follow_related_topic(topic_id: str) -> str:
    """Follow a related topic ID to inspect its outline and section structure."""
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

    graphic_type = asset.get("type", "")
    if graphic_type and graphic_type != "graphic_table":
        return json.dumps({"error": f"Graphic {clean_id} is of type '{graphic_type}', not a table. Only table content is retrievable."})

    table_html = asset.get("html", "") or asset.get("content", "")
    if not table_html:
        return json.dumps({"error": f"Graphic {clean_id} has empty content"})

    title = asset.get("title", raw_id)
    markdown = table_to_markdown(table_html)
    return f"### Graphic Table: {title}\n\n{markdown}"
