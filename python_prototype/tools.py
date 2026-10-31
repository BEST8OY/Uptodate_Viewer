"""LangChain tools for medical database access.

Mirrors Kotlin MedicalDatabaseTools — 5 tools:
1. search_topics — unidex curated topic search
2. get_topic_overview — Outline sections + table graphics + related topics (+ calculator markdown)
3. get_topic_sections_text — Batch section retrieval
4. search_topic_sections — In-topic keyword section locator
5. get_graphic_content — Table data as markdown (or error for non-table)
"""

import re
from typing import Any, Dict, List, Optional, Union

from langchain_core.tools import tool
from pydantic import BaseModel, ConfigDict, Field

from database import ClinRefDatabase
from html_parser import (
    extract_outline_sections,
    extract_section_html,
    html_to_markdown,
    table_to_markdown,
)

# Global database instance — initialized by run.py
_db: Optional[ClinRefDatabase] = None

# Maximum sections returned per get_topic_sections_text call; extras land in omittedSections
MAX_SECTIONS_PER_CALL = 10


# ---------------------------------------------------------------------------
# Pydantic Response Models (1:1 with Kotlin MedicalDatabaseResponses.kt)
# ---------------------------------------------------------------------------


class SearchResultTopicItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    id: str
    title: str
    type: str = "topic"

    def __str__(self) -> str:
        return self.model_dump_json()


class SearchTopicsResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    query: str = ""
    results: List[SearchResultTopicItem] = Field(default_factory=list)
    refine_with: List[str] = Field(default_factory=list)
    message: str = ""
    autoRefined: bool = Field(default=False, alias="auto_refined")
    refinedQuery: Optional[str] = Field(default=None, alias="refined_query")
    error: Optional[str] = None

    @property
    def auto_refined(self) -> bool:
        return self.autoRefined

    @property
    def refined_query(self) -> Optional[str]:
        return self.refinedQuery

    def __str__(self) -> str:
        return self.model_dump_json()


SearchTopicsResult = SearchTopicsResponse


class OutlineSectionItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    id: str
    title: str
    level: int = 1
    parentId: Optional[str] = None

    def __str__(self) -> str:
        return self.model_dump_json()


class OutlineGraphicItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    id: str
    title: str
    isTable: bool = True

    def __str__(self) -> str:
        return self.model_dump_json()


class RelatedTopicItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    id: str
    title: str

    def __str__(self) -> str:
        return self.model_dump_json()


class TopicOverviewResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    topicId: str = Field(default="", alias="topic_id")
    title: str = Field(default="", alias="topic_title")
    topicType: str = Field(default="", alias="topic_type")
    sections: List[OutlineSectionItem] = Field(default_factory=list, alias="outline")
    graphics: List[OutlineGraphicItem] = Field(default_factory=list)
    relatedTopics: List[RelatedTopicItem] = Field(default_factory=list, alias="related_topics")
    markdown: str = ""
    error: Optional[str] = None

    def __str__(self) -> str:
        return self.model_dump_json()


TopicOverviewResult = TopicOverviewResponse


class TopicSectionsResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    topicId: str = Field(default="", alias="topic_id")
    topicTitle: str = Field(default="", alias="topic_title")
    sectionTitles: Dict[str, str] = Field(default_factory=dict, alias="section_titles")
    markdown: str = ""
    # Serialized JSON must contain exactly ONE key per field, in camelCase (matching Kotlin
    # kotlinx.serialization output). The old snake_case pydantic field names + camelCase
    # @computed_field shims emitted the same payload twice under both keys, polluting the
    # Rule-2 verification corpus. camelCase is the pydantic field name (dumped verbatim by
    # model_dump_json); `alias` keeps snake_case dict input valid and the snake_case
    # properties below preserve the old attribute names for internal Python consumers.
    invalidSections: Optional[List[str]] = Field(default_factory=list, alias="invalid_sections")
    omittedSections: Optional[List[str]] = Field(default_factory=list, alias="omitted_sections")
    truncated: Optional[bool] = None
    error: Optional[str] = None

    @property
    def invalid_sections(self) -> Optional[List[str]]:
        """snake_case attribute kept for internal Python consumers (agent.py tool-result triage)."""
        return self.invalidSections

    @property
    def omitted_sections(self) -> Optional[List[str]]:
        return self.omittedSections

    def __str__(self) -> str:
        return self.model_dump_json()


TopicSectionsResult = TopicSectionsResponse


class GraphicContentResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    graphicId: str = Field(default="", alias="graphic_id")
    title: str = ""
    markdown: str = ""
    error: Optional[str] = None

    def __str__(self) -> str:
        return self.model_dump_json()


GraphicContentResult = GraphicContentResponse


class SectionSearchResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    sectionId: str = Field(..., alias="section_id")
    sectionTitle: str = Field(..., alias="section_title")
    level: int = 1
    snippet: str

    def __str__(self) -> str:
        return self.model_dump_json()


class SearchTopicSectionsResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    topicId: str = Field(default="", alias="topic_id")
    query: str = ""
    matches: List[SectionSearchResult] = Field(default_factory=list)
    message: str = ""
    error: Optional[str] = None

    def __str__(self) -> str:
        return self.model_dump_json()


SearchTopicSectionsResult = SearchTopicSectionsResponse


# ---------------------------------------------------------------------------
# Tool Initialization & Helper Functions
# ---------------------------------------------------------------------------


# The model is prompted with camelCase tool names (one prompt text for both stacks), so the
# LangChain registry must expose the same names even though the functions are snake_case.
_CAMEL_TOOL_NAMES = {
    "search_topics": "searchTopics",
    "get_topic_overview": "getTopicOverview",
    "get_topic_sections_text": "getTopicSectionsText",
    "search_topic_sections": "searchTopicSections",
    "get_graphic_content": "getGraphicContent",
}


def init_tools(db: ClinRefDatabase) -> list:
    """Initialize tools with a database instance. Returns tool list under camelCase names."""
    global _db
    _db = db
    tool_list = [
        get_topic_overview,
        search_topics,
        search_topic_sections,
        get_topic_sections_text,
        get_graphic_content,
    ]
    return [
        t.model_copy(update={"name": _CAMEL_TOOL_NAMES[t.name]}) if t.name in _CAMEL_TOOL_NAMES else t
        for t in tool_list
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


# ---------------------------------------------------------------------------
# Retrieval Tools
# ---------------------------------------------------------------------------


@tool
def get_topic_overview(
    topic_id: str = "",
    topicId: Optional[str] = None,
    **kwargs,
) -> TopicOverviewResponse:
    """Step 2 after search_topics: Retrieve the section outline hierarchy (sectionIds and titles) plus the topic's table graphics and related topics.

    Note: Standard clinical topics return outline titles only—you MUST subsequently call get_topic_sections_text to read actual clinical text. (Medical calculator topics return full markdown content directly).

    Args:
        topic_id: Exact numeric topic ID string returned by search_topics (e.g., '12345').
    """
    if _db is None:
        return TopicOverviewResponse(error="Database not initialized")

    effective_topic_id = topic_id or topicId or kwargs.get("topicId") or kwargs.get("topic_id") or ""
    clean_topic_id = str(effective_topic_id).strip()
    if not clean_topic_id:
        return TopicOverviewResponse(error="Topic not found: ")

    if ClinRefDatabase._extract_numeric_id(clean_topic_id) is None:
        return TopicOverviewResponse(topicId=clean_topic_id, error=f"Topic not found: {clean_topic_id}")

    # One decompression of the payload serves body, outline, type, graphics, and related topics.
    asset = _db.get_topic_asset(clean_topic_id)
    if not isinstance(asset, dict):
        return TopicOverviewResponse(topicId=clean_topic_id, error=f"Topic not found: {clean_topic_id}")

    title_val = _db.get_topic_title(clean_topic_id)
    title = title_val if isinstance(title_val, str) and title_val else clean_topic_id
    topic_type = ClinRefDatabase.get_topic_type(asset)
    outline_html = asset.get("outlineHtml") or ""

    if topic_type == "calc" or not outline_html:
        calc_markdown = html_to_markdown(asset.get("bodyHtml") or "")
        return TopicOverviewResponse(
            topicId=clean_topic_id,
            title=title,
            topicType="calc",
            sections=[OutlineSectionItem(id="FULL", title="Calculator Tool Content", level=1)],
            markdown=f"=== Calculator: {title} ===\n{calc_markdown}",
        )

    sections = [
        OutlineSectionItem(
            id=s["id"],
            title=s["title"],
            level=s.get("level", 1),
            parentId=s.get("parentId"),
        )
        for s in extract_outline_sections(outline_html)
        if s.get("id")
    ]

    # The payload's structured inventory is the only place a graphic's subtype is recorded;
    # outline hrefs carry no subtype, so an outline-derived list cannot tell a table from a photo.
    table_graphics = [
        OutlineGraphicItem(id=str(info["id"]), title=str(info.get("displayName") or info["id"]), isTable=True)
        for info in _iter_graphic_infos(asset)
        if info.get("subtype") == "graphic_table" and info.get("id")
    ]
    related = [
        RelatedTopicItem(id=str(t["id"]), title=str(t.get("title") or t["id"]))
        for t in _iter_related_topics(asset)
        if t.get("id") and not t.get("hideInResults")
    ]

    return TopicOverviewResponse(
        topicId=clean_topic_id,
        title=title,
        topicType=topic_type,
        sections=sections,
        graphics=table_graphics,
        relatedTopics=related,
        markdown="",
    )


# Lexidrug appendix tables attached to nearly every drug topic — reference boilerplate,
# never dosing content (mirrors Kotlin ContentDao.BOILERPLATE_GRAPHIC_IDS).
BOILERPLATE_GRAPHIC_IDS = frozenset({"57469", "116421"})


def _iter_graphic_infos(asset: dict):
    """Yield every retrievable `graphicInfo` object from a payload's `relatedGraphics` groups."""
    for group in asset.get("relatedGraphics") or []:
        for entry in (group or {}).get("graphics") or []:
            info = (entry or {}).get("graphicInfo")
            if not isinstance(info, dict) or info.get("hideInResults"):
                continue
            if str(info.get("id")) in BOILERPLATE_GRAPHIC_IDS:
                continue
            yield info


def _iter_related_topics(asset: dict):
    """Yield every topic object from a payload's `relatedTopics` block."""
    for topic in ((asset.get("relatedTopics") or {}).get("topics") or []):
        if isinstance(topic, dict):
            yield topic


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
) -> SearchTopicsResponse:
    """Search the UpToDate index by a single medical condition, syndrome, or drug name (1–3 words max, e.g., 'apixaban', 'acute gout', 'community acquired pneumonia').

    Returns candidate {id, title} pairs and refine_with keywords. For multi-condition or multi-drug queries, invoke multiple search_topics calls in parallel. Never pass full sentences, patient ages, or lab values.

    Args:
        query: Core clinical entity or generic drug name (1–3 words, e.g., 'metformin', 'atrial fibrillation'). Do not include boolean operators, patient demographics, or punctuation.
    """
    if _db is None:
        return SearchTopicsResponse(error="Database not initialized")

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
        return SearchTopicsResponse(
            query="",
            results=[],
            refine_with=[],
            message="",
        )

    results = _db.search_topics(clean_query, limit=8)
    if results:
        return SearchTopicsResponse(
            query=clean_query,
            results=[SearchResultTopicItem(id=r["id"], title=r["title"]) for r in results][:8],
            message="Success",
        )

    # No direct hit: consult the typeahead vocabulary once, then retry its best terms.
    suggestions = _db.get_suggestions(clean_query, limit=20)
    for suggestion in suggestions[:5]:
        retry_results = _db.search_topics(suggestion, limit=8)
        if retry_results:
            return SearchTopicsResponse(
                query=suggestion,
                results=[SearchResultTopicItem(id=r["id"], title=r["title"]) for r in retry_results][:8],
                refine_with=[s for s in suggestions if s != suggestion],
                message=f"Auto-refined from '{clean_query}' to '{suggestion}'",
                autoRefined=True,
                refinedQuery=suggestion,
            )

    message = (
        f"No topic match and no suggestions available for '{clean_query}'."
        if not suggestions
        else f"No results for '{clean_query}'. Retry with one of the 'refine_with' terms."
    )
    return SearchTopicsResponse(
        query=clean_query,
        results=[],
        refine_with=suggestions,
        message=message,
    )


@tool
def get_topic_sections_text(
    topic_id: str = "",
    section_ids: Optional[Union[list[str], str]] = None,
    topicId: Optional[str] = None,
    sectionIds: Optional[Union[list[str], str]] = None,
    section_id: Optional[Union[list[str], str]] = None,
    sectionId: Optional[Union[list[str], str]] = None,
    **kwargs,
) -> TopicSectionsResponse:
    """Step 3 (Mandatory for clinical answers): Retrieve full, unredacted clinical text for up to 10 sectionIds within a single topicId.

    Select sections covering dosing, first-line and alternative regimens (especially if patient allergies exist), and renal/hepatic adjustments. Up to 10 sections are returned in full; extras are listed in omittedSections.

    Args:
        topic_id: Exact numeric topic ID string.
        section_ids: Array of 1 to 10 section ID strings copied from get_topic_overview or search_topic_sections (e.g., ['H12', 'H15']).
    """
    if _db is None:
        return TopicSectionsResponse(error="Database not initialized")

    effective_topic_id = topic_id or topicId or kwargs.get("topic_id") or kwargs.get("topicId") or ""
    clean_topic_id = str(effective_topic_id).strip()
    if not clean_topic_id or ClinRefDatabase._extract_numeric_id(clean_topic_id) is None:
        return TopicSectionsResponse(topicId=clean_topic_id, error=f"Topic not found: {clean_topic_id}")

    raw_sids = (
        section_ids
        if section_ids is not None
        else (
            sectionIds
            if sectionIds is not None
            else (
                section_id
                if section_id is not None
                else (
                    sectionId
                    if sectionId is not None
                    else (
                        kwargs.get("section_ids")
                        or kwargs.get("sectionIds")
                        or kwargs.get("section_id")
                        or kwargs.get("sectionId")
                        or []
                    )
                )
            )
        )
    )
    if isinstance(raw_sids, str):
        raw_sids = [raw_sids]
    elif raw_sids is None:
        raw_sids = []

    # One decompression serves body, outline, and the declared topic type.
    asset = _db.get_topic_asset(clean_topic_id)
    if not isinstance(asset, dict):
        return TopicSectionsResponse(topicId=clean_topic_id, error=f"Topic not found: {clean_topic_id}")

    body_html = asset.get("bodyHtml") or ""
    outline_html = asset.get("outlineHtml") or ""
    if not body_html:
        return TopicSectionsResponse(topicId=clean_topic_id, error=f"Topic not found: {clean_topic_id}")

    title_val = _db.get_topic_title(clean_topic_id)
    topic_title = title_val if isinstance(title_val, str) and title_val else clean_topic_id

    t_type = ClinRefDatabase.get_topic_type(asset)
    clean_sids = [sid.strip().upper() for sid in raw_sids if isinstance(sid, str)]

    # Explicit handling for calculator topic type or full text requests
    if t_type == "calc" or not outline_html or "FULL" in clean_sids or "ALL" in clean_sids:
        markdown = html_to_markdown(body_html)
        header = f"=== Calculator: {topic_title} ===" if t_type == "calc" else "=== Section: FULL ==="
        full_text = f"{header}\n{markdown}"
        return TopicSectionsResponse(
            topicId=clean_topic_id,
            topicTitle=topic_title,
            sectionTitles={"FULL": topic_title},
            markdown=full_text,
        )

    if not raw_sids:
        return TopicSectionsResponse(
            topicId=clean_topic_id,
            topicTitle=topic_title,
            sectionTitles={},
            markdown="",
            error="sectionIds cannot be empty. Specify 1 to 10 section IDs from get_topic_overview.",
        )

    # Build section ID -> title map from outline
    raw_outline_sections = extract_outline_sections(outline_html or "")
    id_to_title = {s["id"]: s["title"] for s in raw_outline_sections if s.get("id")}

    # Validate & smart-resolve section IDs (exact ID, case-insensitive ID, or title match)
    valid_ids, invalid_ids = resolve_section_ids(raw_sids, id_to_title)

    if not valid_ids and invalid_ids:
        return TopicSectionsResponse(
            topicId=clean_topic_id,
            topicTitle=topic_title,
            sectionTitles={},
            markdown="",
            invalidSections=invalid_ids,
        )

    sections_md = []
    section_titles = {}
    omitted_sections = []

    for index, section_id in enumerate(valid_ids):
        # Count guard: at most MAX_SECTIONS_PER_CALL sections are returned per call;
        # extra IDs are reported in omittedSections (never dropped silently).
        if index >= MAX_SECTIONS_PER_CALL:
            omitted_sections.append(section_id)
            continue

        title = id_to_title.get(section_id, "")
        section_html = extract_section_html(body_html, outline_html or "", section_id)
        if section_html:
            markdown = html_to_markdown(section_html)
            sec_entry = f"=== Section: {section_id} ===\n{markdown}"
        else:
            sec_entry = f"=== Section: {section_id} ===\nSection not found."

        sections_md.append(sec_entry)
        section_titles[section_id] = title

    return TopicSectionsResponse(
        topicId=clean_topic_id,
        topicTitle=topic_title,
        sectionTitles=section_titles,
        markdown="\n\n".join(sections_md),
        invalidSections=invalid_ids,
        omittedSections=omitted_sections,
    )


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
) -> SearchTopicSectionsResponse:
    """Perform a targeted keyword search inside a single known topicId to locate specific section IDs and text snippets (e.g., 'renal', 'dosing', 'pregnancy', 'penicillin allergy').

    Use this on large topics when get_topic_overview has dozens of sections and you need to pinpoint exact sectionIds before calling get_topic_sections_text.

    Args:
        topic_id: Exact numeric topic ID string.
        query: 1–2 clinical keywords to locate within the topic (e.g., 'renal dose', 'contraindications').
    """
    if _db is None:
        return SearchTopicSectionsResponse(error="Database not initialized")

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
        return SearchTopicSectionsResponse(
            topicId=clean_topic_id,
            query=clean_query,
            matches=[],
            message="Topic ID and query must be non-empty.",
        )

    if ClinRefDatabase._extract_numeric_id(clean_topic_id) is None:
        return SearchTopicSectionsResponse(
            topicId=clean_topic_id,
            query=clean_query,
            matches=[],
            error=f"Topic not found: {clean_topic_id}",
            message=f"Topic {clean_topic_id} not found.",
        )

    asset = _db.get_topic_asset(clean_topic_id)
    if not asset:
        return SearchTopicSectionsResponse(
            topicId=clean_topic_id,
            query=clean_query,
            matches=[],
            error=f"Topic not found: {clean_topic_id}",
            message=f"Topic {clean_topic_id} not found.",
        )

    body_html = asset.get("bodyHtml", "")
    outline_html = asset.get("outlineHtml", "")
    raw_sections = extract_outline_sections(outline_html or "")
    if not raw_sections:
        return SearchTopicSectionsResponse(
            topicId=clean_topic_id,
            query=clean_query,
            matches=[],
            message="No sections found in topic outline.",
        )

    keywords = [k.lower() for k in clean_query.split() if k]
    scored = []

    for s in raw_sections:
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

            scored.append({
                "item": SectionSearchResult(
                    sectionId=sid,
                    sectionTitle=title,
                    level=s.get("level", 1),
                    snippet=snippet,
                ),
                "score": title_hits * 3 + text_hits,
            })

    scored.sort(key=lambda m: m["score"], reverse=True)
    top_matches = [m["item"] for m in scored[:8]]

    return SearchTopicSectionsResponse(
        topicId=clean_topic_id,
        query=clean_query,
        matches=top_matches,
        message=f"Found {len(top_matches)} matching sections." if top_matches else f"No matching sections found for '{clean_query}'.",
    )


@tool
def get_graphic_content(
    graphic_id: str = "",
    graphicId: Optional[str] = None,
    **kwargs,
) -> GraphicContentResponse:
    """Retrieve full structured Markdown table content (e.g., empirical antibiotic tables, renal dosing adjustment tables, staging criteria) for an item whose type is 'graphic_table' in get_topic_overview.

    Only call for 'graphic_table' items; skip figures, flowcharts, and images.

    Args:
        graphic_id: Exact graphic ID string from get_topic_overview for a 'graphic_table' item (e.g., 'Graphic-12345' or '12345').
    """
    if _db is None:
        return GraphicContentResponse(error="Database not initialized")

    effective_graphic_id = graphic_id or graphicId or kwargs.get("graphic_id") or kwargs.get("graphicId") or ""
    clean_id = str(effective_graphic_id).strip()
    # Mirror Kotlin MedicalDatabaseTools normalization: strip `graphic_`, `graphic-`,
    # `graphic_table_`, `graphic-table-`, `table_`/`table-` prefixes and fall back to the raw ID.
    raw_id = re.sub(r"(?i)^(?:graphic[-_]?(?:table[-_]?)?|table[-_]?)", "", clean_id)

    asset = _db.get_graphic_asset(raw_id) or (None if raw_id == clean_id else _db.get_graphic_asset(clean_id))
    if not asset:
        return GraphicContentResponse(graphicId=clean_id, error=f"Graphic {clean_id} not found")

    graphic_info = asset.get("graphicInfo", {})
    subtype = graphic_info.get("subtype", "")
    if subtype and subtype not in ("graphic_table", "table"):
        return GraphicContentResponse(
            graphicId=clean_id,
            error=f"Graphic {clean_id} is of type '{subtype}', not a table. Only table content is retrievable.",
        )

    table_html = asset.get("imageHtml", "")
    if not table_html:
        return GraphicContentResponse(graphicId=clean_id, error=f"Graphic {clean_id} has empty content")

    title = graphic_info.get("displayName") or graphic_info.get("title") or raw_id
    markdown = table_to_markdown(table_html)
    full_markdown = f"### Graphic Table: {title}\n\n{markdown}"
    return GraphicContentResponse(
        graphicId=clean_id,
        title=title,
        markdown=full_markdown,
    )
