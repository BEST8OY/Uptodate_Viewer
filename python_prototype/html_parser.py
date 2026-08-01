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
    Strips leading dashes from sub-section titles (e.g., "-Antiplatelet" -> "Antiplatelet").
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
            title = re.sub(r"^[-–—]+\s*", "", title)
            sections.append({"id": section_id, "title": title})

    return sections


def extract_related_topics(outline_html: str) -> list[dict]:
    """Parse outlineHtml to extract related topic links.

    Returns [{id, title}] for links that are NOT section or graphic links.
    Skips hrefs containing "section" or "type":"graphic" (matches Kotlin logic).
    """
    soup = BeautifulSoup(outline_html, "html.parser")
    topics = []
    seen_ids = set()
    for a_tag in soup.find_all("a", href=True):
        href = a_tag["href"]
        title = a_tag.get_text(strip=True)
        if not title:
            continue
        # Skip section links
        if '"section"' in href:
            continue
        # Skip graphic links (type:"graphic" in href)
        if re.search(r'"type"\s*:\s*"graphic"', href, re.IGNORECASE):
            continue
        # Extract ID from JSON payload
        id_match = re.search(r'"id"\s*:\s*"(\d+)"', href)
        if id_match:
            tid = id_match.group(1)
            if tid not in seen_ids:
                seen_ids.add(tid)
                topics.append({"id": tid, "title": title})
    return topics


def extract_graphics_from_outline(outline_html: str) -> list[dict]:
    """Parse outlineHtml to extract graphic metadata.

    Matches Kotlin's parseGraphicsFromOutline: looks for type=graphic in
    appAction hrefs, then extracts id and subtype from JSON-like payload.
    Handles both &quot; (HTML-encoded) and " (decoded) quote styles.

    Returns [{id, type, title, is_table}].
    """
    soup = BeautifulSoup(outline_html, "html.parser")
    graphics = []
    seen_ids = set()
    for a_tag in soup.find_all("a", href=True):
        href = a_tag["href"]
        title = a_tag.get_text(strip=True)
        if not title:
            continue

        # Check if this is a graphic link (type:"graphic" in href)
        type_match = re.search(r'"type"\s*:\s*"graphic"', href, re.IGNORECASE)
        if not type_match:
            continue

        # Extract graphic ID (matches Kotlin's GRAPHIC_ID_REGEX)
        id_match = re.search(r'(?:id|graphicId)"?\s*:\s*"?([a-zA-Z0-9_-]+)"?', href, re.IGNORECASE)
        gid = id_match.group(1) if id_match else ""

        if gid and gid not in seen_ids:
            seen_ids.add(gid)
            # Extract subtype (matches Kotlin's GRAPHIC_SUBTYPE_REGEX)
            subtype_match = re.search(r'"subtype"\s*:\s*"([^"]+)"', href, re.IGNORECASE)
            subtype = subtype_match.group(1) if subtype_match else ""
            is_table = subtype == "graphic_table"
            graphics.append({"id": gid, "type": subtype or "unknown", "title": title, "is_table": is_table})

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
        # Stop at references section (matches Kotlin's referenceHeaderRegex)
        if sib.get("id") == "references":
            break
        # Also stop at next heading if no explicit next section
        if not next_section_id and sib.name and re.match(r"h[1-6]", sib.name):
            break
        parts.append(str(sib))

    return "\n".join(parts)


def clean_markdown_content(markdown: str) -> str:
    """Clean DOM noise, footnote citations, and boilerplate from markdown text."""
    if not markdown:
        return ""
    # Strip footnote references like [1], [1,2], [1-3]
    markdown = re.sub(r"\[\d+(?:\s*[-,\u2013\u2014]\s*\d+)*\]", "", markdown)
    # Strip repetitive inline disclaimers or copyright notices
    markdown = re.sub(r"(?i)official topic refund/disclaimer.*$", "", markdown)
    # Normalize excessive blank lines
    markdown = re.sub(r"\n{3,}", "\n\n", markdown)
    return markdown.strip()


def html_to_markdown(html: str, title_lookup: Optional[dict] = None) -> str:
    """Convert HTML section content to clean Markdown.

    Handles: headings, tables, lists, bold/italic, links, medical references.
    Converts appAction medical/drug/graphic links to [text](Topic-id) format.
    """
    if not html:
        return ""

    soup = BeautifulSoup(html, "html.parser")

    # Remove inline script, style, and iframe elements
    for element in soup(["script", "style", "iframe", "noscript"]):
        element.decompose()

    # Convert appAction links to readable format
    for a_tag in soup.find_all("a", href=True):
        href = a_tag["href"]
        text = a_tag.get_text(strip=True)

        # Graphic links: Graphic-XXXX or assetType: graphic
        graphic_match = re.search(r"Graphic-([a-zA-Z0-9_-]+)", href, re.IGNORECASE)
        if graphic_match:
            a_tag["href"] = f"Graphic-{graphic_match.group(1)}"
            continue

        if '"assetType":"graphic"' in href or "'assetType':'graphic'" in href:
            gid_match = re.search(r'"id"\s*:\s*"([a-zA-Z0-9_-]+)"', href)
            if gid_match:
                a_tag["href"] = f"Graphic-{gid_match.group(1)}"
                continue

        # Topic/Drug links: Topic-NNNN or assetType: topic
        topic_match = re.search(r"Topic-(\d+)", href, re.IGNORECASE)
        if topic_match:
            a_tag["href"] = f"Topic-{topic_match.group(1)}"
            continue

        if '"assetType":"topic"' in href or "'assetType':'topic'" in href:
            tid_match = re.search(r'"id"\s*:\s*"(\d+)"', href) or re.search(r'"topicId"\s*:\s*"(\d+)"', href)
            if tid_match:
                a_tag["href"] = f"Topic-{tid_match.group(1)}"
                continue

        # Remove raw javascript/appAction link wrappers, preserving text
        if "appAction" in href or href.startswith("javascript:"):
            a_tag.replace_with(text)

    # Convert to markdown
    h = html2text.HTML2Text()
    h.body_width = 0  # Don't wrap lines
    h.unicode_snob = True
    h.protect_links = True
    h.mark_code = False

    markdown = h.handle(str(soup))
    return clean_markdown_content(markdown)


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
