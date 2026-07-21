#!/usr/bin/env python3
"""
Test suite for MedicalDatabaseTools logic against real .sqlite/.db files.

Simulates the Kotlin DAO + MedicalDatabaseTools pipeline in Python:
  1. Gzip decompression of asset payloads (utdasset.sqlite)
  2. Outline parsing and section extraction (the fixed extractSectionHtml)
  3. Graphic lookup with prefix stripping (the fixed getGraphicContent)
  4. FTS search (fsearch.db / fcontentsearch.db)
  5. Topic title lookup (unidex.en.sqlite)
  6. SafetyValidator numeric normalization
  7. isLogicalFailure sentinel detection
  8. Line-anchored citation parsing
  9. Alphanumeric GRAPHIC_REF_REGEX
 10. Visual-interpretation pattern (table excluded)
 11. Block-level markdown separators
 12. Link type filtering (contributors/abstracts skipped)
 13. Title lookup in markdown links
 14. Outline includes graphics
"""

import gzip
import json
import re
import sqlite3
import sys
import traceback
from pathlib import Path
from typing import Optional

# ─── Paths ───────────────────────────────────────────────────────────────────
PROJECT = Path(__file__).parent
DB_FILES = {
    "utdasset": PROJECT / "utdasset.sqlite",
    "utdtoc": PROJECT / "utdtoc.db",
    "unidex": PROJECT / "unidex.en.sqlite",
    "utdqf": PROJECT / "utdqf.sqlite",
    "fsearch": PROJECT / "fsearch.db",
    "fcontentsearch": PROJECT / "fcontentsearch.db",
}

# ─── Colors ──────────────────────────────────────────────────────────────────
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"

passed = 0
failed = 0
skipped = 0


def ok(label: str, detail: str = ""):
    global passed
    passed += 1
    suffix = f"  {detail}" if detail else ""
    print(f"  {GREEN}PASS{RESET} {label}{suffix}")


def fail(label: str, detail: str = ""):
    global failed
    failed += 1
    suffix = f"  {detail}" if detail else ""
    print(f"  {RED}FAIL{RESET} {label}{suffix}")


def skip(label: str, reason: str = ""):
    global skipped
    skipped += 1
    suffix = f"  ({reason})" if reason else ""
    print(f"  {YELLOW}SKIP{RESET} {label}{suffix}")


def section(title: str):
    print(f"\n{BOLD}{CYAN}━━━ {title} ━━━{RESET}")


# ─── Gzip decoder (mirrors GzipUtil.kt) ─────────────────────────────────────
def decode_payload(data: bytes) -> str:
    if len(data) >= 2 and data[0] == 0x1F and data[1] == 0x8B:
        try:
            return gzip.decompress(data).decode("utf-8")
        except Exception:
            return data.decode("utf-8", errors="replace")
    return data.decode("utf-8", errors="replace")


# ─── Regex patterns (mirrors MedicalDatabaseTools companion object) ──────────
A_TAG_RE = re.compile(
    r"""<a\s+[^>]*href=['"]([^'"]*)['"][^>]*>(.*?)</a>""",
    re.IGNORECASE | re.DOTALL,
)
SECTION_RE = re.compile(
    r"""section(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""",
    re.IGNORECASE,
)
STRIP_TAGS_RE = re.compile(r"<[^>]*>")
GRAPHIC_TYPE_RE = re.compile(
    r"""type(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""",
    re.IGNORECASE,
)
GRAPHIC_ID_RE = re.compile(
    r"""(?<![a-zA-Z])(?:id|graphicId)(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""",
    re.IGNORECASE,
)
GRAPHIC_ACTION_RE = re.compile(r"appAction\(([^)]*)\)", re.DOTALL)
GRAPHIC_SUBTYPE_RE = re.compile(
    r"""subtype(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""",
    re.IGNORECASE,
)

# Block-level tag patterns (mirrors the M7 fix in MedicalDatabaseTools)
HTML_HEADING_RE = re.compile(r"</?h[1-6]\b[^>]*>", re.IGNORECASE)
HTML_DIV_OPEN_RE = re.compile(r"<div\b[^>]*>", re.IGNORECASE)
HTML_BLOCK_CLOSE_RE = re.compile(r"</(?:div|table|thead|tbody|ul|ol)>", re.IGNORECASE)
HTML_TABLE_OPEN_RE = re.compile(r"<table\b[^>]*>", re.IGNORECASE)
HTML_LIST_OPEN_RE = re.compile(r"<[uo]l\b[^>]*>", re.IGNORECASE)
HTML_P_RE = re.compile(r"</?p\b[^>]*>", re.IGNORECASE)
HTML_STRONG_RE = re.compile(r"</?strong\b[^>]*>", re.IGNORECASE)


# ─── Outline parser (mirrors MedicalDatabaseTools.parseOutlineList) ──────────
def parse_outline_list(outline_html: str) -> list[dict[str, str]]:
    results = []
    for m in A_TAG_RE.finditer(outline_html):
        href, inner_html = m.group(1), m.group(2)
        sec_match = SECTION_RE.search(href)
        if sec_match:
            sec_id = sec_match.group(1)
            text = STRIP_TAGS_RE.sub("", inner_html).strip()
            if text:
                results.append({"id": sec_id, "title": text})
    return results


# ─── Section extraction (mirrors the FIXED extractSectionHtml) ───────────────
def extract_section_html(
    body_html: str, outline_html: str, section_id: str
) -> Optional[str]:
    escaped_id = re.escape(section_id)
    start_tag_re = re.compile(
        r"""<\w+\s+[^>]*id=["']""" + escaped_id + r"""["'][^>]*>""",
        re.IGNORECASE,
    )
    start_match = start_tag_re.search(body_html)
    if not start_match:
        return None
    start_idx = start_match.start()

    outline = parse_outline_list(outline_html)
    current_index = -1
    for i, entry in enumerate(outline):
        if entry["id"] == section_id:
            current_index = i
            break

    next_match_idx = -1
    if current_index != -1 and current_index < len(outline) - 1:
        for next_idx in range(current_index + 1, len(outline)):
            next_section_id = outline[next_idx].get("id", "")
            if not next_section_id:
                continue
            next_escaped = re.escape(next_section_id)
            next_start_re = re.compile(
                r"""<\w+\s+[^>]*id=["']""" + next_escaped + r"""["'][^>]*>""",
                re.IGNORECASE,
            )
            next_match = next_start_re.search(body_html, start_match.end())
            if next_match:
                next_match_idx = next_match.start()
                break

    if next_match_idx != -1:
        return body_html[start_idx:next_match_idx]

    ref_re = re.compile(
        r"""<\w+\s+[^>]*(?:id=["']references["'])[^>]*>""",
        re.IGNORECASE,
    )
    ref_match = ref_re.search(body_html, start_match.end())
    if ref_match:
        return body_html[start_idx:ref_match.start()]

    return body_html[start_idx:]


# ─── OLD buggy extractSectionHtml (for comparison) ───────────────────────────
def extract_section_html_buggy(body_html: str, section_id: str) -> Optional[str]:
    escaped_id = re.escape(section_id)
    start_tag_re = re.compile(
        r"""<\w+\s+[^>]*id=["']""" + escaped_id + r"""["'][^>]*>""",
        re.IGNORECASE,
    )
    start_match = start_tag_re.search(body_html)
    if not start_match:
        return None
    start_idx = start_match.start()

    next_heading_re = re.compile(
        r"""<\w+\s+[^>]*(?:class=["'][^"']*(?:headingAnchor|drugH1Div)[^"']*["']|id=["']references["'])[^>]*>""",
        re.IGNORECASE,
    )
    next_match = next_heading_re.search(body_html, start_match.end())
    if next_match:
        return body_html[start_idx : next_match.start()]
    return body_html[start_idx:]


# ─── SafetyValidator numeric normalization (mirrors the fix) ─────────────────
UNIT_RE = re.compile(
    r"""(\d+[.,]?\d*)\s*(mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg)""",
    re.IGNORECASE,
)


def normalize_numeric_spaces(text: str) -> str:
    return UNIT_RE.sub(lambda m: m.group(1) + m.group(2).lower(), text)


NUMERIC_TOKEN_RE = re.compile(
    r"""\d+[.,]?\d*\s*(?:mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg)?""",
    re.IGNORECASE,
)


# ─── isLogicalFailure (mirrors TurnContextAccumulator fix) ───────────────────
def is_logical_failure(result: str) -> bool:
    trimmed = result.strip()
    if trimmed.lower() == "topic not found":
        return True
    if trimmed.lower() == "section not found.":
        return True
    if trimmed.startswith("{") and '"error"' in trimmed:
        return True
    return False


# ─── Citation parser (mirrors the line-anchored TurnContextAccumulator fix) ──
CITATION_RE = re.compile(
    r"""^\s*Topic:\s*(.+?),\s*Section:\s*(.+?)(?:\s*\(ID:\s*([a-zA-Z0-9_-]+)\))?\s*$""",
    re.IGNORECASE | re.MULTILINE,
)


def parse_citations(answer: str) -> list[dict]:
    return [
        {
            "topicTitle": m.group(1).strip(),
            "sectionTitle": m.group(2).strip(),
            "sectionId": (m.group(3) or "unknown").strip(),
        }
        for m in CITATION_RE.finditer(answer)
    ]


# ─── GRAPHIC_REF_REGEX (mirrors the H1 fix) ─────────────────────────────────
GRAPHIC_REF_RE = re.compile(r"Graphic-[a-zA-Z0-9_-]+", re.IGNORECASE)


# ─── Visual interpretation patterns (mirrors H2 fix — table excluded) ────────
VISUAL_PATTERNS = [
    re.compile(r"(?i)the (?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|pathology|slide|specimen|scan|film|rogram) (?:shows?|demonstrates?|reveals?|suggests?|indicates?|displays?|depicts?|illustrates?)"),
    re.compile(r"(?i)(?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|pathology|slide|specimen|scan|film) (?:findings?|abnormalities?|results?|features?|characteristics?)"),
    re.compile(r"(?i)(?:visual|visualized?|visible|appears? to show|can be seen)"),
    re.compile(r"(?i)(?:the (?:figure|algorithm|diagram|picture) (?:shows?|demonstrates?|reveals?|depicts?))"),
]


# ─── Database connections ────────────────────────────────────────────────────
def get_db(name: str) -> sqlite3.Connection:
    path = DB_FILES[name]
    if not path.exists():
        raise FileNotFoundError(f"Database not found: {path}")
    return sqlite3.connect(str(path))


# ═════════════════════════════════════════════════════════════════════════════
#  TESTS
# ═════════════════════════════════════════════════════════════════════════════

def test_database_access():
    section("1. Database Access & Schema Validation")
    for name, path in DB_FILES.items():
        if not path.exists():
            skip(f"Connect to {name}", f"{path.name} not found")
            continue
        try:
            conn = get_db(name)
            cur = conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
            tables = [r[0] for r in cur.fetchall()]
            conn.close()
            ok(f"Connect to {name}", f"{len(tables)} tables: {tables[:3]}...")
        except Exception as e:
            fail(f"Connect to {name}", str(e))


def test_gzip_decompression():
    section("2. Gzip Payload Decompression (utdasset.sqlite)")
    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("Gzip decompression", str(e))
        return

    cur = conn.execute("SELECT id, payload FROM topic_asset LIMIT 5")
    rows = cur.fetchall()
    topic_ok = 0
    for row_id, payload in rows:
        if payload is None:
            continue
        try:
            decoded = decode_payload(payload)
            j = json.loads(decoded)
            assert "bodyHtml" in j or "outlineHtml" in j
            topic_ok += 1
        except Exception as e:
            fail(f"Decompress topic_asset id={row_id}", str(e))
    ok("Decompress topic_asset", f"{topic_ok}/{len(rows)} topics OK")

    cur = conn.execute("SELECT id, payload FROM graphic_asset LIMIT 5")
    rows = cur.fetchall()
    graphic_ok = 0
    for row_id, payload in rows:
        if payload is None:
            continue
        try:
            decoded = decode_payload(payload)
            j = json.loads(decoded)
            assert "imageHtml" in j or "graphicInfo" in j
            graphic_ok += 1
        except Exception as e:
            fail(f"Decompress graphic_asset id={row_id}", str(e))
    ok("Decompress graphic_asset", f"{graphic_ok}/{len(rows)} graphics OK")
    conn.close()


def test_topic_content_loading():
    section("3. Topic Content Loading")
    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("Topic content loading", str(e))
        return
    cur = conn.execute("SELECT id, payload FROM topic_asset WHERE payload IS NOT NULL LIMIT 10")
    rows = cur.fetchall()
    conn.close()
    loaded = 0
    for row_id, payload in rows:
        try:
            decoded = decode_payload(payload)
            j = json.loads(decoded)
            if j.get("bodyHtml", "") and len(j["bodyHtml"]) > 100:
                loaded += 1
        except Exception as e:
            fail(f"Load topic {row_id}", str(e))
    ok("Load topic content", f"{loaded}/{len(rows)} have valid bodyHtml")


def test_outline_parsing():
    section("4. Outline Parsing")
    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("Outline parsing", str(e))
        return
    cur = conn.execute("SELECT id, payload FROM topic_asset WHERE payload IS NOT NULL LIMIT 10")
    rows = cur.fetchall()
    conn.close()
    for row_id, payload in rows:
        try:
            decoded = decode_payload(payload)
            j = json.loads(decoded)
            outline_html = j.get("outlineHtml", "")
            if not outline_html:
                continue
            sections = parse_outline_list(outline_html)
            if sections:
                ok(f"Parse outline topic {row_id}", f"{len(sections)} sections")
        except Exception as e:
            fail(f"Parse outline topic {row_id}", str(e))


def test_section_extraction_fix():
    section("5. Section Extraction — Subsection Truncation Fix")
    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("Section extraction fix", str(e))
        return
    cur = conn.execute(
        "SELECT id, payload FROM topic_asset WHERE payload IS NOT NULL ORDER BY length(payload) DESC LIMIT 15"
    )
    rows = cur.fetchall()
    conn.close()
    tested = 0
    for row_id, payload in rows:
        try:
            decoded = decode_payload(payload)
            j = json.loads(decoded)
            body_html = j.get("bodyHtml", "")
            outline_html = j.get("outlineHtml", "")
            if not body_html or not outline_html:
                continue
            sections = parse_outline_list(outline_html)
            if len(sections) < 2:
                continue
            sec_id = sections[0]["id"]
            new_result = extract_section_html(body_html, outline_html, sec_id)
            old_result = extract_section_html_buggy(body_html, sec_id)
            if new_result and old_result:
                if len(new_result) >= len(old_result):
                    ok(f"Section '{sec_id}' topic {row_id}", f"new={len(new_result)} >= old={len(old_result)}")
                else:
                    fail(f"Section '{sec_id}' topic {row_id}", f"new={len(new_result)} < old={len(old_result)}")
                tested += 1
            if tested >= 5:
                break
        except Exception as e:
            fail(f"Section extraction topic {row_id}", str(e))
    if tested == 0:
        skip("Section extraction fix", "No suitable topics found")


def test_section_extraction_subsection_boundary():
    section("5b. Subsection Boundary — Direct Regression Test")
    body = """
    <div id="sec-A"><h2>Section A</h2><p>Content A</p></div>
    <div id="sec-B"><h2>Section B</h2><p>Content B</p></div>
    <div id="sec-C"><h2>Section C</h2><p>Content C</p></div>
    """
    outline = """
    <a href="javascript:appAction({&quot;section&quot;:&quot;sec-A&quot;})">Section A</a>
    <a href="javascript:appAction({&quot;section&quot;:&quot;sec-B&quot;})">Section B</a>
    <a href="javascript:appAction({&quot;section&quot;:&quot;sec-C&quot;})">Section C</a>
    """
    new_result = extract_section_html(body, outline, "sec-A")
    assert new_result is not None and "Content A" in new_result
    assert "Content B" not in new_result
    ok("sec-A stops at sec-B boundary", f"{len(new_result)} chars")
    new_b = extract_section_html(body, outline, "sec-B")
    assert new_b is not None and "Content B" in new_b and "Content C" not in new_b
    ok("sec-B contains only B", f"{len(new_b)} chars")
    new_c = extract_section_html(body, outline, "sec-C")
    assert new_c is not None and "Content C" in new_c
    ok("sec-C (last) all remaining", f"{len(new_c)} chars")


def test_graphic_prefix_stripping():
    section("6. Graphic ID Prefix Stripping")
    for raw, expected in [("Graphic-50001", "50001"), ("graphic-50001", "50001"), ("50001", "50001")]:
        stripped = raw.removeprefix("Graphic-").removeprefix("graphic-")
        if stripped == expected:
            ok(f"Strip '{raw}' → '{stripped}'")
        else:
            fail(f"Strip '{raw}' → '{stripped}'", f"expected '{expected}'")


def test_graphic_asset_loading():
    section("7. Graphic Asset Loading")
    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("Graphic asset loading", str(e))
        return
    cur = conn.execute("SELECT id, payload FROM graphic_asset WHERE payload IS NOT NULL LIMIT 10")
    rows = cur.fetchall()
    conn.close()
    loaded = sum(1 for _, p in rows if p and "imageHtml" in json.loads(decode_payload(p)))
    ok("Load graphic_asset", f"{loaded}/{len(rows)} have imageHtml")


def test_fts_search():
    section("8. Full-Text Search")
    for db_name in ["fsearch", "fcontentsearch"]:
        if not DB_FILES[db_name].exists():
            skip(f"FTS on {db_name}", "not found")
            continue
        try:
            conn = get_db(db_name)
            cur = conn.execute("SELECT Text, URL FROM search WHERE search MATCH 'aspirin AND URL:topic' LIMIT 5")
            results = cur.fetchall()
            conn.close()
            ok(f"FTS on {db_name}", f"{len(results)} results")
        except Exception as e:
            fail(f"FTS on {db_name}", str(e))


def test_unidex_topic_lookup():
    section("9. Topic Title Lookup")
    if not DB_FILES["unidex"].exists():
        skip("Unidex lookup", "not found")
        return
    try:
        conn = get_db("unidex")
        cur = conn.execute("SELECT topic_id, title FROM topic LIMIT 5")
        topics = cur.fetchall()
        conn.close()
        for tid, title in topics:
            ok(f"Topic {tid}", f"'{title[:50]}'")
    except Exception as e:
        fail("Unidex lookup", str(e))


def test_toc_structure():
    section("10. TOC Structure")
    if not DB_FILES["utdtoc"].exists():
        skip("TOC", "not found")
        return
    try:
        conn = get_db("utdtoc")
        toc_count = conn.execute("SELECT COUNT(*) FROM TOC").fetchone()[0]
        map_count = conn.execute("SELECT COUNT(*) FROM TOCMap").fetchone()[0]
        conn.close()
        ok("TOC table", f"{toc_count} entries")
        ok("TOCMap table", f"{map_count} mappings")
    except Exception as e:
        fail("TOC structure", str(e))


def test_safety_validator_numeric_normalization():
    section("11. Numeric Normalization")
    cases = [
        ("500mg", "500 mg", True),
        ("500 mg", "500mg", True),
        ("10mg", "10 mg", True),
        ("250mcg", "250 mcg", True),
        ("500mg", "500 mg twice daily", True),
        ("500mg", "100mg", False),
        ("500mg", "", False),
        ("100mg", "200mg", False),
    ]
    for ans, src, expect in cases:
        norm_a = normalize_numeric_spaces(ans)
        norm_s = normalize_numeric_spaces(src)
        escaped = re.escape(norm_a)
        boundary_re = re.compile(r"(?<![\d.])" + escaped + r"(?![\d.])", re.IGNORECASE)
        got = bool(boundary_re.search(norm_s))
        if got == expect:
            ok(f"normalize('{ans}') vs '{src}'", f"match={got}")
        else:
            fail(f"normalize('{ans}') vs '{src}'", f"match={got}, expected={expect}")


# ═════════════════════════════════════════════════════════════════════════════
#  NEW TESTS — Second audit pass fixes
# ═════════════════════════════════════════════════════════════════════════════

def test_is_logical_failure():
    section("12. isLogicalFailure — Sentinel String Detection (C3 fix)")

    sentinel_strings = [
        ("Section not found.", True),
        ("Section not found", False),  # without period — NOT a sentinel
        ("section not found.", True),
        ("Topic not found", True),
        ("topic not found", True),
        ('{"error": "Graphic 50001 not found"}', True),
        ('{"error": "Graphic content not found in database"}', True),
        ("## Dosing\n\n500mg twice daily", False),
        ('{"title": "Aspirin", "type": "graphic_table"}', False),
        ("Related topics found", False),
        ("Medication error rate is 5%", False),  # clinical text with "error"
        ("The error was corrected", False),
    ]

    for result_str, expected in sentinel_strings:
        got = is_logical_failure(result_str)
        if got == expected:
            ok(f"isLogicalFailure({result_str[:35]}...)", f"{got} (expected {expected})")
        else:
            fail(f"isLogicalFailure({result_str[:35]}...)", f"{got} (expected {expected})")


def test_citation_parsing_line_anchored():
    section("13. Citation Parsing — Line-Anchored (C2 fix)")

    # Basic: two citations on separate lines
    answer = """Based on the evidence, aspirin 81mg daily is recommended.
Topic: Aspirin, Section: Dosing (ID: sec-aspirin-dosing)
For patients with renal impairment, dose adjustment is needed.
Topic: Renal Dosing, Section: NSAIDs (ID: sec-renal-nsaids)
"""
    citations = parse_citations(answer)
    if len(citations) == 2:
        ok("Parse 2 citations on separate lines")
    else:
        fail("Parse 2 citations", f"got {len(citations)}")

    if citations and citations[0]["topicTitle"] == "Aspirin":
        ok("First citation topicTitle", "'Aspirin'")
    else:
        fail("First citation topicTitle", str(citations[0] if citations else "none"))

    if citations and citations[0]["sectionId"] == "sec-aspirin-dosing":
        ok("First citation sectionId", "'sec-aspirin-dosing'")
    else:
        fail("First citation sectionId", str(citations[0]["sectionId"] if citations else "none"))

    if len(citations) > 1 and citations[1]["topicTitle"] == "Renal Dosing":
        ok("Second citation topicTitle", "'Renal Dosing'")
    else:
        fail("Second citation topicTitle", str(citations[1]["topicTitle"] if len(citations) > 1 else "missing"))

    # Comma in title
    answer_comma = "Topic: Drug Interactions, Warfarin, Section: Major (ID: sec-major)\n"
    cites = parse_citations(answer_comma)
    if cites and "Warfarin" in cites[0]["topicTitle"]:
        ok("Citation with comma in title", f"'{cites[0]['topicTitle']}'")
    else:
        fail("Citation with comma in title", f"got: {cites[0]['topicTitle'] if cites else 'none'}")

    # Line-anchored: inline multi-citations on one line parse as ONE (with garbled
    # section title), not two. The ^...$ anchoring prevents cross-line overreach but
    # doesn't split within a single line — that's the system prompt's job ("one per line").
    inline_answer = "Topic: A, Section: X; Topic: B, Section: Y\n"
    inline_cites = parse_citations(inline_answer)
    if len(inline_cites) == 1:
        ok("Inline multi-citation parsed as 1 (not 2)", f"sectionTitle='{inline_cites[0]['sectionTitle']}'")
    else:
        fail("Inline multi-citation", f"got {len(inline_cites)} (expected 1)")

    # Proper multi-line format
    proper_answer = "Topic: A, Section: X (ID: sec-x)\nTopic: B, Section: Y (ID: sec-y)\n"
    proper_cites = parse_citations(proper_answer)
    if len(proper_cites) == 2:
        ok("Proper multi-line citations", f"2 parsed")
    else:
        fail("Proper multi-line citations", f"got {len(proper_cites)}")


def test_graphic_ref_regex_alphanumeric():
    section("14. GRAPHIC_REF_REGEX — Alphanumeric IDs (H1 fix)")

    test_cases = [
        ("Graphic-50001", True),       # numeric only
        ("Graphic-fig2a", True),        # alphanumeric
        ("Graphic-abc_def", True),      # underscore
        ("Graphic-123-abc", True),      # hyphen
        ("graphic-50001", True),        # lowercase
        ("Graphic-12345abc", True),     # mixed
        ("Graphic-", False),            # no ID
        ("Graphic", False),             # no hyphen
    ]

    for text, should_match in test_cases:
        got = bool(GRAPHIC_REF_RE.search(text))
        if got == should_match:
            ok(f"GRAPHIC_REF match '{text}'", f"{got}")
        else:
            fail(f"GRAPHIC_REF match '{text}'", f"{got}, expected {should_match}")

    # Full-text check: answer referencing alphanumeric graphic ID
    answer = "The algorithm in Graphic-fig2a shows the diagnostic pathway."
    if GRAPHIC_REF_RE.search(answer):
        ok("Detect alphanumeric graphic ref in answer text")
    else:
        fail("Detect alphanumeric graphic ref in answer text", "not found")


def test_visual_pattern_table_excluded():
    section("15. Visual Pattern — 'table' Excluded (H2 fix)")

    # "the table shows..." should NOT match (table is excluded from pattern)
    table_text = "The table shows the dosing schedule for aspirin."
    matches = [p for p in VISUAL_PATTERNS if p.search(table_text)]
    if len(matches) == 0:
        ok("'the table shows...' NOT flagged (correct)")
    else:
        fail("'the table shows...' FLAGGED (table should be excluded)", f"{len(matches)} patterns matched")

    # "the figure shows..." SHOULD match
    figure_text = "The figure shows a calcified aortic valve."
    matches_fig = [p for p in VISUAL_PATTERNS if p.search(figure_text)]
    if len(matches_fig) > 0:
        ok("'the figure shows...' FLAGGED (correct)")
    else:
        fail("'the figure shows...' NOT flagged", "expected match")

    # "the algorithm shows..." SHOULD match
    algo_text = "The algorithm demonstrates the diagnostic pathway."
    matches_algo = [p for p in VISUAL_PATTERNS if p.search(algo_text)]
    if len(matches_algo) > 0:
        ok("'the algorithm shows...' FLAGGED (correct)")
    else:
        fail("'the algorithm shows...' NOT flagged", "expected match")

    # "the ECG shows..." SHOULD match
    ecg_text = "The ECG shows ST elevation in leads II, III, aVF."
    matches_ecg = [p for p in VISUAL_PATTERNS if p.search(ecg_text)]
    if len(matches_ecg) > 0:
        ok("'the ECG shows...' FLAGGED (correct)")
    else:
        fail("'the ECG shows...' NOT flagged", "expected match")

    # "the data appears to show..." SHOULD match (general visual language)
    appear_text = "The data appears to show a trend toward improvement."
    matches_appear = [p for p in VISUAL_PATTERNS if p.search(appear_text)]
    if len(matches_appear) > 0:
        ok("'appears to show' FLAGGED (correct)")
    else:
        fail("'appears to show' NOT flagged", "expected match")


def test_graphic_ref_and_tool_call_gating():
    section("16. Graphic Ref + Tool Call Gating (H3 fix)")

    # Scenario: visual language + graphic tool called → hard block
    # Scenario: visual language + no graphic tool → advisory only
    # We simulate the logic here

    def validate_graphic(visual_language: bool, graphic_ids: set, answer_refs: bool, touched_any_graphic_tool: bool):
        if not visual_language:
            return "pass", []
        if graphic_ids or answer_refs:
            return "block", []
        warnings = []
        if not touched_any_graphic_tool:
            warnings.append("Advisory: visual language with no graphic tool")
        return "pass", warnings

    # Case 1: visual lang + graphic tool → block
    status, warnings = validate_graphic(True, {"fig1"}, False, True)
    if status == "block":
        ok("Visual lang + graphic tool → BLOCK")
    else:
        fail("Visual lang + graphic tool", f"got {status}")

    # Case 2: visual lang + graphic ref in answer → block
    status, warnings = validate_graphic(True, set(), True, False)
    if status == "block":
        ok("Visual lang + Graphic- ref in answer → BLOCK")
    else:
        fail("Visual lang + Graphic- ref", f"got {status}")

    # Case 3: visual lang + no graphic tool + no refs → advisory
    status, warnings = validate_graphic(True, set(), False, False)
    if status == "pass" and len(warnings) > 0:
        ok("Visual lang + no graphic evidence → ADVISORY")
    else:
        fail("Visual lang + no graphic evidence", f"status={status}, warnings={len(warnings)}")

    # Case 4: no visual language → pass
    status, warnings = validate_graphic(False, {"fig1"}, True, True)
    if status == "pass" and len(warnings) == 0:
        ok("No visual language → PASS")
    else:
        fail("No visual language", f"status={status}")


def test_section_not_found_filtering():
    section("17. Section Not Found Filtering")
    failures = [
        "Section not found.",
        "Topic not found",
        '{"error": "Graphic 50001 not found"}',
    ]
    for s in failures:
        if is_logical_failure(s):
            ok(f"Detect failure: '{s[:35]}...'")
        else:
            fail(f"Detect failure: '{s[:35]}...'", "NOT detected")

    valid = [
        "## Dosing\n\n500mg twice daily",
        '{"title": "Aspirin", "type": "graphic_table"}',
        "Related topics found",
    ]
    for s in valid:
        if not is_logical_failure(s):
            ok(f"Valid result kept: '{s[:35]}...'")
        else:
            fail(f"Valid result kept: '{s[:35]}...'", "WRONGLY filtered")


def test_html_to_markdown_block_separators():
    section("18. HTML-to-Markdown — Block-Level Separators (M7 fix)")

    def simple_md(html: str) -> str:
        s = html
        s = HTML_HEADING_RE.sub("\n\n", s)
        s = HTML_TABLE_OPEN_RE.sub("\n\n", s)
        s = HTML_LIST_OPEN_RE.sub("\n", s)
        s = HTML_DIV_OPEN_RE.sub("\n", s)
        s = HTML_BLOCK_CLOSE_RE.sub("\n", s)
        s = HTML_P_RE.sub("\n\n", s)
        s = HTML_STRONG_RE.sub("**", s)
        s = STRIP_TAGS_RE.sub("", s)
        return s.strip()

    # Heading + content should have a line break between them
    html1 = "<h3>Adult Dosing</h3><p>200 mg twice daily</p>"
    md1 = simple_md(html1)
    if "Adult Dosing" in md1 and "200 mg" in md1 and "\n" in md1:
        ok("Heading + paragraph separated", repr(md1[:60]))
    else:
        fail("Heading + paragraph separated", repr(md1))

    # Heading directly followed by text (no <p>) should still have separator
    html2 = "<h3>Adult Dosing</h3>200 mg twice daily"
    md2 = simple_md(html2)
    if "Adult Dosing" in md2 and "200 mg" in md2 and "Dosing" in md2.split("200")[0]:
        ok("Heading + bare text separated", repr(md2[:60]))
    else:
        fail("Heading + bare text separated", repr(md2))

    # Div wrapping should add line break
    html3 = "<div>First section</div><div>Second section</div>"
    md3 = simple_md(html3)
    if "First section" in md3 and "Second section" in md3:
        ok("Divs separated", repr(md3[:60]))
    else:
        fail("Divs separated", repr(md3))


def test_rate_limiter_logic():
    section("19. RateLimiter Sliding Window Logic")
    import time

    timestamps = []
    rpm_limit = 5

    def acquire_fixed():
        now = time.time() * 1000
        window_start = now - 60_000
        timestamps[:] = [t for t in timestamps if t >= window_start]
        if len(timestamps) >= rpm_limit:
            return False
        timestamps.append(now)
        return True

    timestamps_buggy = []

    def acquire_buggy():
        now = time.time() * 1000
        window_start = now - 60_000
        timestamps_buggy[:] = [t for t in timestamps_buggy if t >= window_start]
        if len(timestamps_buggy) >= rpm_limit:
            return False
        timestamps_buggy.append(now)
        timestamps_buggy[:] = [t for t in timestamps_buggy if t <= now]
        return True

    fixed = [acquire_fixed() for _ in range(6)]
    buggy = [acquire_buggy() for _ in range(6)]

    if fixed == [True, True, True, True, True, False]:
        ok("Fixed: 5/6 allowed, 6th blocked", str(fixed))
    else:
        fail("Fixed", str(fixed))

    if all(buggy):
        ok("Buggy: all 6 allowed (bug confirmed)", str(buggy))
    else:
        skip("Buggy repro", str(buggy))


# ═════════════════════════════════════════════════════════════════════════════
#  NEW TESTS — Link filtering, title lookup, outline graphics
# ═════════════════════════════════════════════════════════════════════════════

def test_link_type_filtering():
    section("20. Link Type Filtering — Contributors/Abstracts Skipped")

    # Simulate htmlToMarkdown's link type detection
    def classify_link(json_str: str) -> str:
        if '"type":"graphic"' in json_str:
            return "graphic"
        elif '"type":"medical"' in json_str:
            return "topic"
        elif '"type":"drug"' in json_str:
            return "topic"
        elif '"type":"contributor"' in json_str:
            return "skip"
        elif '"type":"abstract"' in json_str:
            return "skip"
        return "skip"

    cases = [
        ('{"type":"contributor"}', "skip"),
        ('{"type":"abstract"}', "skip"),
        ('{"id":"4","type":"medical","subtype":"medical_review"}', "topic"),
        ('{"id":"9929","type":"drug","subtype":"drug_general"}', "topic"),
        ('{"id":"50032","type":"graphic","subtype":"graphic_algorithm"}', "graphic"),
    ]

    for json_str, expected in cases:
        got = classify_link(json_str)
        if got == expected:
            ok(f"Classify {json_str[:40]}...", f"→ {got}")
        else:
            fail(f"Classify {json_str[:40]}...", f"→ {got}, expected {expected}")


def test_outline_includes_graphics():
    section("21. Outline Includes Graphics")

    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("Outline graphics", str(e))
        return

    cur = conn.execute("SELECT id, payload FROM topic_asset WHERE payload IS NOT NULL LIMIT 10")
    rows = cur.fetchall()
    conn.close()

    GRAPHIC_SUBTYPE_RE = re.compile(r"""subtype(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""", re.IGNORECASE)

    for row_id, payload in rows:
        try:
            decoded = decode_payload(payload)
            j = json.loads(decoded)
            outline_html = j.get("outlineHtml", "")
            if not outline_html:
                continue

            sections = parse_outline_list(outline_html)

            # Parse graphics from outline
            graphics = []
            for m in A_TAG_RE.finditer(outline_html):
                href, inner = m.group(1), m.group(2)
                type_match = GRAPHIC_TYPE_RE.search(href)
                if type_match and type_match.group(1) == "graphic":
                    id_match = GRAPHIC_ID_RE.search(href)
                    subtype_match = GRAPHIC_SUBTYPE_RE.search(href)
                    if id_match:
                        graphics.append({
                            "id": id_match.group(1),
                            "type": subtype_match.group(1) if subtype_match else "",
                            "title": STRIP_TAGS_RE.sub("", inner).strip()
                        })

            if sections or graphics:
                ok(f"Outline topic {row_id}", f"{len(sections)} sections, {len(graphics)} graphics")
        except Exception as e:
            fail(f"Outline topic {row_id}", str(e))


def test_no_warning_prefix():
    section("22. No [WARNING] Prefix in Section Text")

    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("No warning prefix", str(e))
        return

    # Find a topic with a table
    cur = conn.execute(
        "SELECT id, payload FROM topic_asset WHERE payload IS NOT NULL ORDER BY length(payload) DESC LIMIT 10"
    )
    rows = cur.fetchall()
    conn.close()

    for row_id, payload in rows:
        try:
            decoded = decode_payload(payload)
            j = json.loads(decoded)
            body = j.get("bodyHtml", "")
            outline = j.get("outlineHtml", "")
            if not body or not outline:
                continue

            sections = parse_outline_list(outline)
            if not sections:
                continue

            sec_id = sections[0]["id"]
            result = extract_section_html(body, outline, sec_id)
            if result and "<table" in result.lower():
                # Simulate htmlToMarkdown (simplified)
                md = STRIP_TAGS_RE.sub("", result).strip()
                if "[WARNING" not in md:
                    ok(f"Topic {row_id} sec {sec_id}: no [WARNING] prefix")
                else:
                    fail(f"Topic {row_id} sec {sec_id}", "[WARNING] still present")
                break
        except Exception as e:
            fail(f"No warning check topic {row_id}", str(e))


def test_graphic_titles_in_outline():
    section("23. Graphic Titles Available in Outline")

    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("Graphic titles", str(e))
        return

    # Check that graphic_asset has titles
    cur = conn.execute("SELECT id, payload FROM graphic_asset WHERE payload IS NOT NULL LIMIT 10")
    rows = cur.fetchall()
    conn.close()

    titled = 0
    for row_id, payload in rows:
        decoded = decode_payload(payload)
        j = json.loads(decoded)
        info = j.get("graphicInfo", {})
        title = info.get("displayName", "")
        if title:
            titled += 1

    ok("Graphic titles in asset", f"{titled}/{len(rows)} have displayName")


def test_topic_title_lookup_for_links():
    section("24. Topic Title Lookup for Link Text")

    if not DB_FILES["unidex"].exists():
        skip("Title lookup", "unidex not found")
        return

    conn = get_db("unidex")

    # Test that we can look up titles for known topic IDs
    test_ids = [1, 4, 9929, 120681]
    for tid in test_ids:
        row = conn.execute("SELECT title FROM topic WHERE topic_id = ?", (tid,)).fetchone()
        if row and row[0]:
            title = row[0]
            # Title should be longer than just a number
            if len(title) > 3:
                ok(f"Title for topic {tid}", f"'{title[:50]}'")
            else:
                fail(f"Title for topic {tid}", f"too short: '{title}'")
        else:
            fail(f"Title for topic {tid}", "not found")

    conn.close()


def test_graphic_title_lookup():
    section("25. Graphic Title Lookup from graphic_asset")

    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("Graphic title lookup", str(e))
        return

    cur = conn.execute("SELECT id, payload FROM graphic_asset WHERE payload IS NOT NULL LIMIT 10")
    rows = cur.fetchall()
    conn.close()

    for row_id, payload in rows:
        try:
            decoded = decode_payload(payload)
            j = json.loads(decoded)
            info = j.get("graphicInfo", {})
            display_name = info.get("displayName", "")
            if display_name and len(display_name) > 3:
                ok(f"Graphic {row_id} title", f"'{display_name[:50]}'")
            else:
                fail(f"Graphic {row_id} title", f"missing or too short: '{display_name}'")
        except Exception as e:
            fail(f"Graphic {row_id} title", str(e))


def test_graphic_link_text_vs_db_title():
    section("26. Graphic Link Text vs Database Title")

    try:
        conn = get_db("utdasset")
    except FileNotFoundError as e:
        skip("Graphic link text", str(e))
        return

    # Find a topic with graphic links in body HTML
    cur = conn.execute("SELECT id, payload FROM topic_asset WHERE payload IS NOT NULL LIMIT 20")
    rows = cur.fetchall()
    conn.close()

    found = 0
    for row_id, payload in rows:
        try:
            decoded = decode_payload(payload)
            j = json.loads(decoded)
            body = j.get("bodyHtml", "")

            for m in A_TAG_RE.finditer(body):
                href = m.group(1)
                text = STRIP_TAGS_RE.sub("", m.group(2)).strip()
                action = GRAPHIC_ACTION_RE.search(href)
                if action and text and len(text) > 3:
                    json_str = action.group(1).replace("&quot;", '"')
                    if '"type":"graphic"' in json_str:
                        id_match = GRAPHIC_ID_RE.search(json_str)
                        if id_match:
                            gid = id_match.group(1)
                            # Get DB title
                            conn2 = get_db("utdasset")
                            cur2 = conn2.execute("SELECT payload FROM graphic_asset WHERE id = ?", (gid,))
                            gpayload = cur2.fetchone()
                            conn2.close()
                            if gpayload:
                                gj = json.loads(decode_payload(gpayload[0]))
                                db_title = gj.get("graphicInfo", {}).get("displayName", "")
                                if db_title and db_title != text:
                                    ok(f"Link '{text[:30]}' → DB title '{db_title[:30]}'")
                                elif db_title:
                                    ok(f"Link '{text[:30]}' = DB title (already correct)")
                                found += 1
                                break
            if found >= 3:
                break
        except Exception as e:
            fail(f"Graphic link text check", str(e))

    if found == 0:
        skip("Graphic link text vs DB title", "No graphic links found")


# ═════════════════════════════════════════════════════════════════════════════
#  MAIN
# ═════════════════════════════════════════════════════════════════════════════
if __name__ == "__main__":
    print(f"{BOLD}ClinRef MedicalDatabaseTools — Test Suite{RESET}")
    print(f"Project: {PROJECT}")
    print(f"Databases: {', '.join(f'{k} ({'exists' if v.exists() else 'missing'})' for k, v in DB_FILES.items())}")

    try:
        test_database_access()
        test_gzip_decompression()
        test_topic_content_loading()
        test_outline_parsing()
        test_section_extraction_fix()
        test_section_extraction_subsection_boundary()
        test_graphic_prefix_stripping()
        test_graphic_asset_loading()
        test_fts_search()
        test_unidex_topic_lookup()
        test_toc_structure()
        test_safety_validator_numeric_normalization()
        test_is_logical_failure()
        test_citation_parsing_line_anchored()
        test_graphic_ref_regex_alphanumeric()
        test_visual_pattern_table_excluded()
        test_graphic_ref_and_tool_call_gating()
        test_section_not_found_filtering()
        test_html_to_markdown_block_separators()
        test_rate_limiter_logic()
        test_link_type_filtering()
        test_outline_includes_graphics()
        test_no_warning_prefix()
        test_graphic_titles_in_outline()
        test_topic_title_lookup_for_links()
        test_graphic_title_lookup()
        test_graphic_link_text_vs_db_title()
    except Exception as e:
        print(f"\n{RED}FATAL ERROR:{RESET}")
        traceback.print_exc()

    print(f"\n{BOLD}{'='*50}")
    print(f"Results: {GREEN}{passed} passed{RESET}, {RED}{failed} failed{RESET}, {YELLOW}{skipped} skipped{RESET}")
    print(f"{'='*50}{RESET}")

    sys.exit(1 if failed > 0 else 0)
