"""Unified clinical source domain models — mirrors Kotlin ClinicalSource.kt exactly.

Represents clinical citations (articles and tables) with structured section and graphic metadata.
"""

import re
from typing import Optional
from pydantic import BaseModel


class SectionRef(BaseModel):
    section_id: str
    section_title: str


class ClinicalArticle(BaseModel):
    topic_id: str
    topic_title: str
    sections: list[SectionRef] = []

    @property
    def id(self) -> str:
        return self.topic_id

    @property
    def display_title(self) -> str:
        return self.topic_title


class ClinicalTable(BaseModel):
    graphic_id: str
    table_title: str
    parent_topic_id: Optional[str] = None
    parent_topic_title: Optional[str] = None

    @property
    def id(self) -> str:
        return self.graphic_id

    @property
    def display_title(self) -> str:
        return self.table_title


def group_topic_refs_to_articles(refs: list) -> list[ClinicalArticle]:
    """Group flat TopicRef items into structured ClinicalArticle instances with section lists."""
    if not refs:
        return []
    by_topic: dict[str, list] = {}
    for r in refs:
        by_topic.setdefault(r.topic_id, []).append(r)

    articles = []
    for topic_id, group in by_topic.items():
        primary_title = ""
        for item in group:
            if getattr(item, "topic_title", "") and not str(item.topic_title).isdigit():
                primary_title = item.topic_title
                break
        if not primary_title:
            for item in group:
                label = getattr(item, "label", "")
                if label and not str(label).isdigit():
                    primary_title = label
                    break
        if not primary_title:
            primary_title = topic_id

        seen_secs = set()
        sections = []
        for item in group:
            sec_id = getattr(item, "section_id", "")
            if sec_id and sec_id.upper() != "FULL" and sec_id not in seen_secs:
                seen_secs.add(sec_id)
                sections.append(SectionRef(section_id=sec_id, section_title=getattr(item, "label", sec_id)))

        articles.append(
            ClinicalArticle(
                topic_id=topic_id,
                topic_title=primary_title,
                sections=sections,
            )
        )
    return articles


def graphic_refs_to_tables(refs: list, parent_titles: Optional[dict[str, str]] = None) -> list[ClinicalTable]:
    """Convert flat GraphicRef items into ClinicalTable instances."""
    if not refs:
        return []
    parent_titles = parent_titles or {}
    seen = set()
    tables = []
    for r in refs:
        gid = r.graphic_id
        if gid in seen:
            continue
        seen.add(gid)
        p_id = getattr(r, "topic_id", None)
        p_title = parent_titles.get(p_id) if p_id else None
        tables.append(
            ClinicalTable(
                graphic_id=gid,
                table_title=getattr(r, "label", f"Graphic {gid}"),
                parent_topic_id=p_id,
                parent_topic_title=p_title,
            )
        )
    return tables
