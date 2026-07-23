"""LangChain tools for medical database access.

Mirrors Kotlin MedicalDatabaseTools — 6 tools:
1. search_topics — FTS search
2. get_topic_outline — Section list for a topic
3. get_topic_section_text — Full section content as markdown
4. get_related_topics — Cross-referenced topics
5. get_graphic_info — Metadata for non-table graphics
6. get_graphic_content — Table data as markdown
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
            follow_related_topic, get_graphic_info, get_graphic_content]


@tool
def search_topics(query: str) -> str:
    """Search medical topics by focused keywords.

    USE: Start with core terms (e.g., "apixaban", "asthma", "gout").
    The response includes "refine_with" suggestions — pick the most relevant and search again.
    For multi-concept questions (drug + condition), search each concept separately.
    NEVER use full sentences, lab values, or patient demographics in search.

    Returns: {results: [{id, title, url}], refine_with: [suggested queries]}
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    results = _db.search_topics(query, limit=10)
    suggestions = _db.get_suggestions(query, limit=30)

    # Tool-level validation: if no results, check if query is valid
    if not results:
        query_lower = query.lower().strip()
        suggestion_texts = [s.lower() for s in suggestions]

        # Check if query matches any suggestion (exact or prefix)
        is_valid = any(
            query_lower == s or s.startswith(query_lower) or query_lower.startswith(s)
            for s in suggestion_texts
        )

        if not is_valid and suggestions:
            # Query is invented — reject and show suggestions
            return json.dumps({
                "error": f"'{query}' is not a valid search. Use one of these suggested queries:",
                "refine_with": suggestions[:10],
            }, indent=2)

    # Return results with suggestions
    response = {
        "results": results,
        "refine_with": suggestions,
    }

    if not results and not suggestions:
        response["message"] = f"No results for '{query}'. Try a single core medical term."

    return json.dumps(response, indent=2)


@tool
def get_topic_outline(topic_id: str) -> str:
    """Retrieve the outline for a topic. Returns sections, graphics, and related topics.

    USE: Call after search_topics to get section IDs for get_topic_section_text.
    Returns section IDs needed for fetching content. Also returns related_topics
    for further exploration with follow_related_topic.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    outline_html = _db.get_topic_outline(topic_id)
    if not outline_html:
        return json.dumps({"error": f"Topic {topic_id} not found"})

    title = _db.get_topic_title(topic_id) or "Unknown"
    sections = extract_outline_sections(outline_html)
    graphics = extract_graphics_from_outline(outline_html)
    related = extract_related_topics(outline_html)

    return json.dumps({
        "topic_id": topic_id,
        "title": title,
        "sections": sections,
        "graphics": graphics,
        "related_topics": related,
    }, indent=2)


@tool
def get_topic_section_text(topic_id: str, section_id: str, section_title: str) -> str:
    """Retrieve full text content of a section. Returns Markdown.

    USE: Call after get_topic_outline with the section_id from the outline.
    Fetch all relevant sections — there is no limit on how many you can read.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    body_html = _db.get_topic_body(topic_id)
    outline_html = _db.get_topic_outline(topic_id)

    if not body_html or not outline_html:
        return json.dumps({"error": f"Topic {topic_id} content not found"})

    section_html = extract_section_html(body_html, outline_html, section_id)
    if not section_html:
        return json.dumps({"error": f"Section {section_id} not found in topic {topic_id}"})

    markdown = html_to_markdown(section_html)
    title = _db.get_topic_title(topic_id) or "Unknown"

    return f"## {title} — {section_title}\n\n{markdown}"


@tool
def follow_related_topic(topic_id: str) -> str:
    """Follow a related topic by its ID. Returns outline with sections and graphics.

    USE: When getTopicOutline shows related_topics that are relevant to the question.
    Returns the same structure as get_topic_outline — sections, graphics, related_topics.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    outline_html = _db.get_topic_outline(topic_id)
    if not outline_html:
        return json.dumps({"error": f"Topic {topic_id} not found"})

    title = _db.get_topic_title(topic_id) or "Unknown"
    sections = extract_outline_sections(outline_html)
    graphics = extract_graphics_from_outline(outline_html)
    related = extract_related_topics(outline_html)

    return json.dumps({
        "topic_id": topic_id,
        "title": title,
        "sections": sections,
        "graphics": graphics,
        "related_topics": related,
    }, indent=2)


@tool
def get_graphic_info(graphic_id: str) -> str:
    """Retrieve metadata for a graphic (FIGURE, IMAGE, etc.). Returns type and title only.

    USE: For non-table graphics. Does NOT return image data.
    For TABLE graphics, use get_graphic_content instead.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    asset = _db.get_graphic_asset(graphic_id)
    if not asset:
        return json.dumps({"error": f"Graphic {graphic_id} not found"})

    info = {
        "id": graphic_id,
        "type": asset.get("type", "unknown"),
        "title": asset.get("title", ""),
        "has_image": bool(asset.get("imageUrl")),
        "has_movie": bool(asset.get("movieUrl")),
    }
    return json.dumps(info, indent=2)


@tool
def get_graphic_content(graphic_id: str) -> str:
    """Retrieve table data from a TABLE-type graphic as Markdown.

    USE: Only for graphics with type='TABLE'. Returns table data as Markdown.
    For FIGURE/IMAGE graphics, use get_graphic_info — images cannot be interpreted.
    """
    if _db is None:
        return json.dumps({"error": "Database not initialized"})

    asset = _db.get_graphic_asset(graphic_id)
    if not asset:
        return json.dumps({"error": f"Graphic {graphic_id} not found"})

    graphic_type = asset.get("type", "")
    title = asset.get("title", "")

    if graphic_type != "TABLE":
        return json.dumps({
            "error": f"Graphic {graphic_id} is type '{graphic_type}', not TABLE. "
                     f"Use get_graphic_info for non-table graphics.",
            "title": title,
        })

    # Extract table HTML
    table_html = asset.get("html", "") or asset.get("content", "")
    if not table_html:
        return json.dumps({"error": "No table content found in graphic", "title": title})

    markdown_table = table_to_markdown(table_html)
    return f"### {title}\n\n{markdown_table}"
