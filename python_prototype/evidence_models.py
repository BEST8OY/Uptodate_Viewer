"""Unified evidence and clinical citation domain models — mirrors Kotlin EvidenceModels.kt exactly."""

from __future__ import annotations

from typing import Any, Optional
from pydantic import BaseModel, Field


class ToolCallRecord(BaseModel):
    tool_name: str
    arguments: dict[str, Any]
    result: str
    success: bool


class FetchedSection(BaseModel):
    topic_id: str
    topic_title: str = ""
    section_id: str
    section_title: str
    content_snippet: str = ""
    relevance_score: float = 0.0


class FetchedGraphic(BaseModel):
    graphic_id: str
    title: str = ""
    markdown: str = ""
    topic_id: str = ""
    topic_title: str = ""
    relevance_score: float = 0.0


class TopicRef(BaseModel):
    topic_id: str
    section_id: str = ""
    label: str
    topic_title: str = ""
    is_graphic: bool = False


class GraphicRef(BaseModel):
    graphic_id: str
    label: str
    topic_id: Optional[str] = None


class PatientProfile(BaseModel):
    age: str = ""
    sex: str = ""
    conditions: list[str] = Field(default_factory=list)
    medications: list[str] = Field(default_factory=list)
    allergies: list[str] = Field(default_factory=list)
    notes: str = ""

    def __init__(self, **data: Any):
        if "gender" in data and "sex" not in data:
            data["sex"] = data.pop("gender")
        super().__init__(**data)

    def to_system_block(self) -> str:
        """Mirror of Kotlin PatientProfile.toSystemBlock()."""
        if not any([self.age, self.sex, self.conditions, self.medications, self.allergies, self.notes]):
            return ""
        parts = []
        if self.age:
            parts.append(f"- Age: {self.age}")
        if self.sex:
            parts.append(f"- Sex: {self.sex}")
        if self.conditions:
            parts.append(f"- Conditions: {', '.join(self.conditions)}")
        if self.medications:
            parts.append(f"- Medications: {', '.join(self.medications)}")
        if self.allergies:
            parts.append(f"- Allergies: {', '.join(self.allergies)}")
        if self.notes:
            parts.append(f"- Notes: {self.notes}")
        return "\n".join([
            "## Active Patient Profile",
            *parts,
            "",
            "Adjust every recommendation for this patient's age, organ function, active medications, and documented allergies.",
        ])


class TurnContext(BaseModel):
    tool_calls: list[ToolCallRecord] = Field(default_factory=list)
    answer: str = ""
    tool_results: list[str] = Field(default_factory=list)
    fetched_sections: list[FetchedSection] = Field(default_factory=list)
    fetched_graphics: list[FetchedGraphic] = Field(default_factory=list)
    graphic_ids: list[str] = Field(default_factory=list)  # insertion-ordered for deterministic citation auto-population
    graphic_titles: dict[str, str] = Field(default_factory=dict)
    graphic_to_topic: dict[str, str] = Field(default_factory=dict)
    user_question: str = ""
    topic_titles: dict[str, str] = Field(default_factory=dict)
    outline_sections: dict[str, dict[str, str]] = Field(default_factory=dict)
    structured_topic_refs: list[TopicRef] = Field(default_factory=list)
    structured_graphic_refs: list[GraphicRef] = Field(default_factory=list)
    patient_profile: Optional[PatientProfile] = None

    @property
    def has_evidence(self) -> bool:
        return bool(self.fetched_sections or self.graphic_ids or self.fetched_graphics)

    def has_fetched_section(self, topic_id: str, section_id: str) -> bool:
        return any(
            s.topic_id == topic_id and (s.section_id == section_id or s.section_id.upper() == "FULL")
            for s in self.fetched_sections
        )

    def has_fetched_graphic(self, graphic_id: str) -> bool:
        clean_gid = str(graphic_id or "").strip()
        return clean_gid in self.graphic_ids or any(g.graphic_id == clean_gid for g in self.fetched_graphics)


class ValidationResult(BaseModel):
    passed: bool = True
    warnings: list[str] = Field(default_factory=list)
    blocked_reason: Optional[str] = None
    violations: list[Any] = Field(default_factory=list)


from eval_quantities import extract_quantities, canonical_number, canonical_unit
NO_TOOLS_BLOCKED_REASON = (
    "Clinical recommendations require database verification. No database tools were executed."
)
NO_SECTIONS_BLOCKED_REASON = (
    "Answer requires section or table content retrieval. Only topic outlines or searches were performed."
)
