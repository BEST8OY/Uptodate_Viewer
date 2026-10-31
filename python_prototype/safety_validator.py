"""Clinical safety validator — mirrors Kotlin SafetyValidator.kt.

Two independent gates, both driven by the values a TurnContext accumulates:
  - validate_evidence: the answer was produced from retrieved database content.
  - validate_answer: every clinical quantity in the answer exists in that content,
    and no visual/graphic findings were invented.

validate() is the composite used for the persisted verdict; the agent graph calls the
two halves separately so each phase remediates toward the right correction.
"""

import re
from typing import Any, Optional

from pydantic import BaseModel

# ── Turn record types ────────────────────────────────────────────────


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


class TopicRef(BaseModel):
    topic_id: str
    section_id: str = ""
    label: str
    topic_title: str = ""


class GraphicRef(BaseModel):
    graphic_id: str
    label: str
    topic_id: Optional[str] = None


class PatientProfile(BaseModel):
    age: str = ""
    sex: str = ""
    conditions: list[str] = []
    medications: list[str] = []
    allergies: list[str] = []
    notes: str = ""

    def __init__(self, **data):
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
    tool_calls: list[ToolCallRecord] = []
    answer: str = ""
    tool_results: list[str] = []
    fetched_sections: list[FetchedSection] = []
    graphic_ids: list[str] = []  # insertion-ordered for deterministic citation auto-population
    graphic_titles: dict[str, str] = {}
    graphic_to_topic: dict[str, str] = {}
    user_question: str = ""
    topic_titles: dict[str, str] = {}
    outline_sections: dict[str, dict[str, str]] = {}
    structured_topic_refs: list[TopicRef] = []
    structured_graphic_refs: list[GraphicRef] = []
    patient_profile: Optional[PatientProfile] = None


# ── Typed violations ─────────────────────────────────────────────────

PHASE1_TARGET = "phase1"
PHASE2_TARGET = "phase2"

VIOLATION_TARGETS = {
    "NO_TOOLS": PHASE1_TARGET,
    "NO_SECTIONS": PHASE1_TARGET,
    "BLANK_NO_EVIDENCE": PHASE1_TARGET,
    "BLANK_WITH_EVIDENCE": PHASE2_TARGET,
    "UNVERIFIED_QTY": PHASE2_TARGET,
    "VISUAL": PHASE2_TARGET,
    # An UNVERIFIED_QTY draft that survived a tool-free revision: the values are absent
    # from what was retrieved, so remediation must go back to retrieval.
    "UNRETRIEVED_DOSES": PHASE1_TARGET,
}

# Rule textures (used verbatim as Violation.detail and joined with " | " when multiple).
NO_TOOLS_BLOCKED_REASON = (
    "Clinical recommendations require database verification. No database tools were executed."
)
NO_SECTIONS_BLOCKED_REASON = (
    "Answer requires section or table content retrieval. Only topic outlines or searches were performed."
)


class Violation(BaseModel):
    code: str
    detail: str
    target: str

    @classmethod
    def of(cls, code: str, detail: str, target: Optional[str] = None) -> "Violation":
        return cls(code=code, detail=detail, target=target or VIOLATION_TARGETS.get(code, PHASE2_TARGET))


class ValidationResult(BaseModel):
    passed: bool
    warnings: list[str] = []
    topic_refs: list[TopicRef] = []
    graphic_refs: list[GraphicRef] = []
    blocked_reason: Optional[str] = None
    violations: list[Violation] = []


# ── Rule 2 vocabulary ────────────────────────────────────────────────

# A number, an optional range separator, and a measurement unit. Units are the
# vocabulary that actually occurs in the UpToDate corpus.
CLINICAL_QUANTITY_REGEX = re.compile(
    r"(?<![\w.])(\d+(?:[.,]\d+)?)(?:\s*(?:-|–|—|to)\s*(\d+(?:[.,]\d+)?))?\s*"
    r"([μµ]g|mcg|mg|mEq|mmol|mOsm|mU|mL|m2|IU|ng|pg|kg|dL|mmHg|cmH2O|g|L|%|percent|units?|years?|months?|weeks?|days?|hours?|minutes?|q\d+h?)(?!\w)",
    re.IGNORECASE,
)

_UNIT_ALIASES = {
    "percent": "%",
    "unit": "units",
    "day": "days",
    "hour": "hours",
    "week": "weeks",
    "year": "years",
    "month": "months",
    "minute": "minutes",
}

_VARIANT_UNIT_ALIASES = {
    "µg": "mcg",
    "μg": "mcg",
    "ug": "mcg",
    "m²": "m2",
}

# Tools whose contract is "return section/table text", in either naming convention.
_CONTENT_TOOL_NAMES = {
    "getTopicSectionsText", "get_topic_sections_text",
    "getGraphicContent", "get_graphic_content",
}

GRAPHIC_INTERPRETATION_PATTERNS = [
    re.compile(
        r"the\s+(?:image|photo|x-?ray|ct|mri|ecg|ekg|ultrasound|scan|film)\s+"
        r"(?:shows?|demonstrates?|reveals?|depicts?)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?:image|x-?ray|ct|mri|ecg|ekg|ultrasound|scan)\s+"
        r"(?:findings?|abnormalities?|features?)",
        re.IGNORECASE,
    ),
    re.compile(
        r"the\s+(?:figure|diagram)\s+"
        r"(?:shows?|demonstrates?|reveals?|depicts?)",
        re.IGNORECASE,
    ),
]

UNICODE_ESCAPE_REGEX = re.compile(r"\\u([0-9a-fA-F]{4})")


def _decode_unicode_escape(match: "re.Match[str]") -> str:
    try:
        return chr(int(match.group(1), 16))
    except (ValueError, OverflowError):
        # Surrogate code units have no Python character — keep the raw escape text.
        return match.group(0)


def canonical_number(raw: str) -> str:
    """'1,200' -> '1200', '2.0' -> '2', '10.50' -> '10.5'."""
    no_commas = raw.replace(",", "")
    if "." not in no_commas:
        return no_commas
    trimmed = no_commas.rstrip("0").rstrip(".")
    return trimmed or "0"


def canonical_unit(raw: str) -> str:
    lower = raw.strip().lower()
    if lower in _VARIANT_UNIT_ALIASES:
        return _VARIANT_UNIT_ALIASES[lower]
    return _UNIT_ALIASES.get(lower, lower)


def extract_quantities(text: str) -> dict[str, str]:
    """Canonical '<number> <unit>' value -> the form first encountered in the text.

    A range yields both bounds, so '5-10 mg' and '5 to 10 mg' verify identically.
    """
    if not text:
        return {}
    out: dict[str, str] = {}
    for match in CLINICAL_QUANTITY_REGEX.finditer(text):
        unit = canonical_unit(match.group(3))
        display = match.group(0).strip()
        out.setdefault(f"{canonical_number(match.group(1))} {unit}", display)
        upper = match.group(2)
        if upper:
            out.setdefault(f"{canonical_number(upper)} {unit}", display)
    return out


class SafetyValidator:
    """Clinical safety validator with typed, collect-all violations."""

    # ── Public gates ──────────────────────────────────────────────────

    def validate(self, context: TurnContext) -> ValidationResult:
        # Evidence failures own the verdict: remediation for them is the retrieval prompt,
        # which never renders answer-quality findings. Within a gate, all rules collect.
        evidence_violations = self.validate_evidence(context)
        violations = evidence_violations or self.validate_answer(context)
        return ValidationResult(
            passed=not violations,
            warnings=[],
            topic_refs=context.structured_topic_refs,
            graphic_refs=context.structured_graphic_refs,
            blocked_reason=" | ".join(v.detail for v in violations) if violations else None,
            violations=violations,
        )

    def validate_evidence(self, context: TurnContext) -> list[Violation]:
        """Gate 1 — the turn queried the database and captured clinical text.

        A topic outline is not evidence: it lists section titles, not clinical content.
        """
        if not context.tool_calls:
            return [Violation.of("NO_TOOLS", NO_TOOLS_BLOCKED_REASON, target=PHASE1_TARGET)]
        if not self._captured_content(context):
            return [Violation.of("NO_SECTIONS", NO_SECTIONS_BLOCKED_REASON, target=PHASE1_TARGET)]
        return []

    def validate_answer(self, context: TurnContext) -> list[Violation]:
        """Gate 2 — answer-content rules (verbatim quantities, no graphic interpretation)."""
        return self._rule2_violations(context) + self._graphic_interpretation_violation(context)

    # ── Rule entry points ─────────────────────────────────────────────

    @staticmethod
    def _captured_content(context: TurnContext) -> bool:
        if context.fetched_sections or context.graphic_ids:
            return True
        return any(tc.success and tc.tool_name in _CONTENT_TOOL_NAMES for tc in context.tool_calls)

    def _validate_section_content_required(self, ctx: TurnContext) -> Optional[ValidationResult]:
        violations = self.validate_evidence(ctx)
        if not violations:
            return None
        return ValidationResult(passed=False, blocked_reason=violations[0].detail, violations=violations)

    def _validate_no_invented_numbers(self, ctx: TurnContext) -> Optional[ValidationResult]:
        violations = self._rule2_violations(ctx)
        if not violations:
            return None
        return ValidationResult(passed=False, blocked_reason=violations[0].detail, violations=violations)

    def _validate_no_graphic_interpretation(self, ctx: TurnContext) -> Optional[ValidationResult]:
        violations = self._graphic_interpretation_violation(ctx)
        if not violations:
            return None
        return ValidationResult(passed=False, blocked_reason=violations[0].detail, violations=violations)

    # ── Corpus ───────────────────────────────────────────────────────

    @staticmethod
    def _build_corpus(context: TurnContext) -> str:
        """Everything the answer may cite, including facts the clinician already supplied."""
        parts: list[str] = []
        parts.extend(context.tool_results)
        parts.extend(s.content_snippet for s in context.fetched_sections if s.content_snippet)
        parts.extend(s.topic_title for s in context.fetched_sections if s.topic_title)
        parts.extend(context.topic_titles.values())
        parts.extend(s.section_title for s in context.fetched_sections if s.section_title)
        parts.extend(g.label for g in context.structured_graphic_refs if g.label)
        parts.extend(context.graphic_titles.values())
        parts.extend(t.topic_title for t in context.structured_topic_refs if t.topic_title)
        if context.user_question:
            parts.append(context.user_question)
        if context.patient_profile is not None:
            block = context.patient_profile.to_system_block()
            if block:
                parts.append(block)
        raw_corpus = " ".join(parts)
        return UNICODE_ESCAPE_REGEX.sub(_decode_unicode_escape, raw_corpus)

    # ── Rule 2: no invented quantities ───────────────────────────────

    def _rule2_violations(self, context: TurnContext) -> list[Violation]:
        answer_quantities = extract_quantities(context.answer)
        if not answer_quantities:
            return []

        verified = extract_quantities(self._build_corpus(context))
        unverified = list(dict.fromkeys(v for k, v in answer_quantities.items() if k not in verified))
        if not unverified:
            return []

        return [
            Violation.of(
                "UNVERIFIED_QTY",
                (
                    f"Unverified clinical quantities found in response: {', '.join(unverified[:10])}. "
                    "Ensure every quantity or dosage matches the retrieved database section text exactly."
                ),
                target=PHASE2_TARGET,
            )
        ]

    # ── Rule 3: no graphic interpretation ────────────────────────────

    @staticmethod
    def _graphic_interpretation_violation(ctx: TurnContext) -> list[Violation]:
        if not any(p.search(ctx.answer) for p in GRAPHIC_INTERPRETATION_PATTERNS):
            return []

        # Quoting the retrieved text is not interpretation.
        tool_text = " ".join(ctx.tool_results)
        if any(p.search(tool_text) for p in GRAPHIC_INTERPRETATION_PATTERNS):
            return []

        return [
            Violation.of(
                "VISUAL",
                (
                    "Answer describes visual details of a graphic or scan that were not retrieved as text. "
                    "Direct users to view the image source directly."
                ),
            )
        ]
