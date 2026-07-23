"""HTML-to-Markdown conversion for ClinRef topic content.

Mirrors the Kotlin MedicalDatabaseTools.htmlToMarkdown() and
extractSectionHtml() patterns using BeautifulSoup + html2text.
"""

import re
from typing import Optional

from bs4 import BeautifulSoup
import html2text


def extract_outline_sections(outline_html: str) -> list[dict]:
    """Parse outlineHtml to extract section IDs and titles.

    Handles two formats:
    1. appAction({"section":"H123"}) — JSON section ID in JavaScript href
    2. section=SEC123 — simple URL parameter

    Returns [{id, title}] for each section link.
    """
    soup = BeautifulSoup(outline_html, "html.parser")
    sections = []
    seen_ids = set()
    for a_tag in soup.find_all("a", href=True):
        href = a_tag["href"]
        title = a_tag.get_text(strip=True)
        if not title:
            continue

        section_id = None

        # Format 1: appAction({"section":"H123"}) — JSON in JS href
        # BeautifulSoup decodes &quot; to " automatically
        json_section_match = re.search(r'"section"\s*:\s*"([^"]+)"', href)
        if json_section_match:
            section_id = json_section_match.group(1)
        else:
            # Format 2: section=SEC123 — simple parameter
            simple_match = re.search(r"section=([a-zA-Z0-9_-]+)", href)
            if simple_match:
                section_id = simple_match.group(1)

        if section_id and section_id not in seen_ids:
            seen_ids.add(section_id)
            sections.append({"id": section_id, "title": title})

    return sections


def extract_related_topics(outline_html: str) -> list[dict]:
    """Parse outlineHtml to extract related topic links.

    Returns [{id, title}] for Topic-... links that are NOT section/graphic links.
    """
    soup = BeautifulSoup(outline_html, "html.parser")
    topics = []
    seen_ids = set()
    for a_tag in soup.find_all("a", href=True):
        href = a_tag["href"]
        title = a_tag.get_text(strip=True)
        if not title:
            continue
        # Match Topic-NNNN in href or in JSON data
        topic_match = re.search(r"Topic-(\d+)", href)
        if not topic_match:
            topic_match = re.search(r'"id"\s*:\s*"(\d+)"', href)
        if topic_match and '"section"' not in href:
            tid = topic_match.group(1)
            if tid not in seen_ids:
                seen_ids.add(tid)
                topics.append({"id": tid, "title": title})
    return topics


def extract_graphics_from_outline(outline_html: str) -> list[dict]:
    """Parse outlineHtml to extract graphic metadata.

    Returns [{id, type, title}] for Graphic-... links.
    """
    soup = BeautifulSoup(outline_html, "html.parser")
    graphics = []
    seen_ids = set()
    for a_tag in soup.find_all("a", href=True):
        href = a_tag["href"]
        title = a_tag.get_text(strip=True)
        if not title:
            continue

        gid = None
        # Match Graphic-XXXX in href
        graphic_match = re.search(r"Graphic-([a-zA-Z0-9_-]+)", href)
        if graphic_match:
            gid = graphic_match.group(1)

        if gid and gid not in seen_ids:
            seen_ids.add(gid)
            gtype = "TABLE" if "TABLE" in href.upper() else "FIGURE"
            graphics.append({"id": gid, "type": gtype, "title": title})

    return graphics


def extract_section_html(body_html: str, outline_html: str, section_id: str) -> Optional[str]:
    """Extract HTML for a specific section from bodyHtml using outline boundaries.

    Mirrors the Kotlin extractSectionHtml() logic:
    1. Find the section's start tag by id attribute
    2. Find the next section boundary from the outline sequence
    3. Slice the HTML between those boundaries
    """
    soup = BeautifulSoup(body_html, "html.parser")

    # Find the start element by id
    start_elem = soup.find(id=section_id)
    if not start_elem:
        return None

    # Get ordered section IDs from outline
    section_ids = [s["id"] for s in extract_outline_sections(outline_html)]

    try:
        current_idx = section_ids.index(section_id)
    except ValueError:
        # Section ID not in outline — try to get content until next heading
        parts = []
        for sib in start_elem.find_next_siblings():
            if sib.name and re.match(r"h[1-6]", sib.name):
                break
            parts.append(str(sib))
        return "\n".join(parts) if parts else str(start_elem)

    # Find the next section boundary
    next_section_id = section_ids[current_idx + 1] if current_idx + 1 < len(section_ids) else None

    # Collect HTML from start to next section
    parts = [str(start_elem)]
    for sib in start_elem.find_next_siblings():
        if next_section_id and sib.get("id") == next_section_id:
            break
        # Also stop at next heading if no explicit next section
        if not next_section_id and sib.name and re.match(r"h[1-6]", sib.name):
            break
        parts.append(str(sib))

    return "\n".join(parts)


def html_to_markdown(html: str, title_lookup: Optional[dict] = None) -> str:
    """Convert HTML section content to clean Markdown.

    Handles: headings, tables, lists, bold/italic, links, medical references.
    Converts appAction medical/drug/graphic links to [text](Topic-id) format.
    """
    if not html:
        return ""

    soup = BeautifulSoup(html, "html.parser")

    # Convert appAction links to readable format
    for a_tag in soup.find_all("a", href=True):
        href = a_tag["href"]
        text = a_tag.get_text(strip=True)

        # Medical/drug topic links: appAction('medical/drug/Topic-NNNN...')
        topic_match = re.search(r"Topic-(\d+)", href)
        if topic_match:
            a_tag["href"] = f"Topic-{topic_match.group(1)}"
            continue

        # Graphic links: appAction('...Graphic-XXXX...')
        graphic_match = re.search(r"Graphic-([a-zA-Z0-9_-]+)", href)
        if graphic_match:
            a_tag["href"] = f"Graphic-{graphic_match.group(1)}"
            continue

    # Convert to markdown
    h = html2text.HTML2Text()
    h.body_width = 0  # Don't wrap lines
    h.unicode_snob = True
    h.protect_links = True
    h.mark_code = False

    markdown = h.handle(str(soup))

    # Clean up: normalize blank lines, strip trailing whitespace
    markdown = re.sub(r"\n{3,}", "\n\n", markdown)
    markdown = markdown.strip()

    return markdown


def table_to_markdown(html: str) -> str:
    """Convert an HTML table to Markdown table format."""
    if not html:
        return ""
    soup = BeautifulSoup(html, "html.parser")
    table = soup.find("table")
    if not table:
        return html_to_markdown(html)

    rows = []
    for tr in table.find_all("tr"):
        cells = [td.get_text(strip=True) for td in tr.find_all(["td", "th"])]
        rows.append(cells)

    if not rows:
        return ""

    # Build markdown table
    num_cols = max(len(r) for r in rows)
    lines = []
    for i, row in enumerate(rows):
        padded = row + [""] * (num_cols - len(row))
        lines.append("| " + " | ".join(padded) + " |")
        if i == 0:
            lines.append("| " + " | ".join(["---"] * num_cols) + " |")

    return "\n".join(lines)
