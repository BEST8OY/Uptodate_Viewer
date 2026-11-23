"""LangChain tools for medical database access.

Mirrors Kotlin MedicalDatabaseTools — 4 tools:
1. search_topics — unidex curated topic search
2. get_topic_overview — Outline sections + table graphics + related topics (+ calculator markdown)
3. get_topic_sections_text — Batch section retrieval
4. get_graphic_content — Table data as markdown (or error for non-table)
"""

import re
from typing import Any, Dict, List, Optional, Union

from langchain_core.tools import tool
from pydantic import AliasChoices, BaseModel, ConfigDict, Field

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
MAX_SECTIONS_PER_CALL = 20


def infer_topic_type(title: str) -> str:
    """Infers topic type from topic title (matches Kotlin MedicalDatabaseTools.inferTopicType)."""
    clean = (title or "").strip().lower()
    if "drug information" in clean:
        return "drug"
    if clean.startswith("calculator:") or "calculator" in clean:
        return "calc"
    if clean.startswith("lab interpretation:"):
        return "narrative"
    return "medical"


# ---------------------------------------------------------------------------
# Pydantic Response Models (1:1 with Kotlin MedicalDatabaseResponses.kt)
# ---------------------------------------------------------------------------


class SearchResultTopicItem(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")
    id: str
    title: str
    type: str = "medical"

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


# ---------------------------------------------------------------------------
# Tool Initialization & Helper Functions
# ---------------------------------------------------------------------------


# The model is prompted with camelCase tool names (one prompt text for both stacks), so the
# LangChain registry must expose the same names even though the functions are snake_case.
_CAMEL_TOOL_NAMES = {
    "search_topics": "searchTopics",
    "get_topic_overview": "getTopicOverview",
    "get_topic_sections_text": "getTopicSectionsText",
    "get_graphic_content": "getGraphicContent",
}


def init_tools(db: ClinRefDatabase) -> list:
    """Initialize tools with a database instance. Returns tool list under camelCase names."""
    global _db
    _db = db
    tool_list = [
        get_topic_overview,
        search_topics,
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


def find_subsumed_sections(
    sorted_valid_ids: list[str],
    outline_sections: list[dict],
) -> dict[str, str]:
    """Determine which requested sections are structurally subsumed by an ancestor section.

    In UpToDate articles, extracting an ancestor section (e.g. level 1) includes all of its
    child subsections (level > 1) up to the next sibling/ancestor. If both an ancestor and
    its descendants are requested, extracting each descendant separately causes massive
    duplication in LLM context.

    Returns a dict mapping child_section_id -> ancestor_section_id.
    """
    if not outline_sections or len(sorted_valid_ids) <= 1:
        return {}

    outline_map = {
        s["id"].upper(): (idx, s.get("level", 1))
        for idx, s in enumerate(outline_sections)
        if s.get("id")
    }
    subsumed: dict[str, str] = {}
    emitted_ancestors: list[tuple[str, int, int]] = []

    for sid in sorted_valid_ids:
        sid_upper = sid.upper()
        if sid_upper not in outline_map:
            continue
        sec_idx, sec_level = outline_map[sid_upper]

        subsumed_by = None
        for anc_id, anc_idx, anc_level in emitted_ancestors:
            if anc_idx < sec_idx:
                is_all_deeper = True
                for k in range(anc_idx + 1, sec_idx + 1):
                    if outline_sections[k].get("level", 1) <= anc_level:
                        is_all_deeper = False
                        break
                if is_all_deeper:
                    subsumed_by = anc_id
                    break

        if subsumed_by:
            subsumed[sid] = subsumed_by
        else:
            emitted_ancestors.append((sid, sec_idx, sec_level))

    return subsumed


# ---------------------------------------------------------------------------
# Retrieval Tool Arguments Schemas (Lean schemas for LLMs + transparent alias deserialization)
# ---------------------------------------------------------------------------


class SearchTopicsArgs(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)
    query: str = Field(
        default="",
        description="Core clinical entity or generic drug name (1–3 words, e.g., 'metformin', 'atrial fibrillation').",
        validation_alias=AliasChoices(
            "query", "search_query", "q", "keyword", "query_text", "queryText", "searchQuery"
        ),
    )


class GetTopicOverviewArgs(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)
    topic_id: str = Field(
        default="",
        description="Exact numeric topic ID string returned by search_topics (e.g., '12345').",
        validation_alias=AliasChoices("topic_id", "topicId", "id"),
    )


class GetTopicSectionsTextArgs(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)
    topic_id: str = Field(
        default="",
        description="Exact numeric topic ID string.",
        validation_alias=AliasChoices("topic_id", "topicId", "id"),
    )
    section_ids: Union[list[str], str] = Field(
        default_factory=list,
        description="Array of 1 to 20 section ID strings copied from get_topic_overview (e.g., ['H12', 'H15']).",
        validation_alias=AliasChoices(
            "section_ids", "sectionIds", "section_id", "sectionId"
        ),
    )


class GetGraphicContentArgs(BaseModel):
    model_config = ConfigDict(extra="ignore", populate_by_name=True)
    graphic_id: str = Field(
        default="",
        description="Numeric graphic ID string (e.g., '12345').",
        validation_alias=AliasChoices("graphic_id", "graphicId", "id"),
    )


def _pick(*vals: Any) -> str:
    """Return the first non-empty string or value representation from candidates."""
    for v in vals:
        if v is not None:
            if isinstance(v, str):
                s = v.strip()
                if s:
                    return s
            elif v:
                return str(v).strip()
    return ""


# ---------------------------------------------------------------------------
# Retrieval Tools
# ---------------------------------------------------------------------------


@tool(args_schema=GetTopicOverviewArgs)
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

    clean_topic_id = _pick(topic_id, topicId, kwargs.get("topicId"), kwargs.get("topic_id"))
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


@tool(args_schema=SearchTopicsArgs)
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

    clean_query = _pick(
        query,
        search_query,
        q,
        keyword,
        query_text,
        queryText,
        searchQuery,
        kwargs.get("query"),
        kwargs.get("search_query"),
        kwargs.get("q"),
        kwargs.get("keyword"),
        kwargs.get("keywords"),
        kwargs.get("query_text"),
        kwargs.get("queryText"),
        kwargs.get("searchQuery"),
    )
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
            results=[
                SearchResultTopicItem(id=r["id"], title=r["title"], type=infer_topic_type(r["title"]))
                for r in results
            ][:8],
            message="Success",
        )

    # No direct hit: consult the typeahead vocabulary once, then retry its best terms.
    suggestions = _db.get_suggestions(clean_query, limit=20)
    for suggestion in suggestions[:5]:
        retry_results = _db.search_topics(suggestion, limit=8)
        if retry_results:
            return SearchTopicsResponse(
                query=suggestion,
                results=[
                    SearchResultTopicItem(id=r["id"], title=r["title"], type=infer_topic_type(r["title"]))
                    for r in retry_results
                ][:8],
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


@tool(args_schema=GetTopicSectionsTextArgs)
def get_topic_sections_text(
    topic_id: str = "",
    section_ids: Optional[Union[list[str], str]] = None,
    topicId: Optional[str] = None,
    sectionIds: Optional[Union[list[str], str]] = None,
    section_id: Optional[Union[list[str], str]] = None,
    sectionId: Optional[Union[list[str], str]] = None,
    **kwargs,
) -> TopicSectionsResponse:
    """Step 3 (Mandatory for clinical answers): Retrieve full, unredacted clinical text for up to 20 sectionIds within a single topicId.

    Select sections covering dosing, first-line and alternative regimens (especially if patient allergies exist), and renal/hepatic adjustments. Up to 20 sections are returned in full; extras are listed in omittedSections.

    Args:
        topic_id: Exact numeric topic ID string.
        section_ids: Array of 1 to 20 section ID strings copied from get_topic_overview (e.g., ['H12', 'H15']).
    """
    if _db is None:
        return TopicSectionsResponse(error="Database not initialized")

    clean_topic_id = _pick(topic_id, topicId, kwargs.get("topic_id"), kwargs.get("topicId"))
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
            error=f"sectionIds cannot be empty. Specify 1 to {MAX_SECTIONS_PER_CALL} section IDs from get_topic_overview.",
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

    # Sort valid_ids by outline order and compute subsumed sections
    outline_order_map = {
        s["id"].upper(): idx
        for idx, s in enumerate(raw_outline_sections)
        if s.get("id")
    }
    sorted_valid_ids = sorted(
        valid_ids,
        key=lambda sid: outline_order_map.get(sid.upper(), float("inf")),
    )
    subsumed_map = find_subsumed_sections(sorted_valid_ids, raw_outline_sections)

    sections_md = []
    section_titles = {}
    omitted_sections = []
    successfully_extracted_ids = set()
    extracted_content_count = 0

    for section_id in sorted_valid_ids:
        title = id_to_title.get(section_id, "")
        subsumed_by = subsumed_map.get(section_id)
        if subsumed_by and subsumed_by in successfully_extracted_ids:
            sec_entry = f"=== Section: {section_id} ===\n(Content included in section [{subsumed_by}] above)"
            sections_md.append(sec_entry)
            section_titles[section_id] = title
        else:
            # Count guard: at most MAX_SECTIONS_PER_CALL content sections are returned per call
            if extracted_content_count >= MAX_SECTIONS_PER_CALL:
                omitted_sections.append(section_id)
                continue

            section_html = extract_section_html(body_html, outline_html or "", section_id)
            if section_html:
                successfully_extracted_ids.add(section_id)
                extracted_content_count += 1
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


@tool(args_schema=GetGraphicContentArgs)
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

    clean_id = _pick(graphic_id, graphicId, kwargs.get("graphic_id"), kwargs.get("graphicId"))
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
