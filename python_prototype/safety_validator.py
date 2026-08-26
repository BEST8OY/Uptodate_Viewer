"""Clinical safety validator — mirrors Kotlin SafetyValidator.kt exactly.

4 validation rules (intent-aware), short-circuit on first hard block:
0. Intent-aware tool call check (allow non-clinical responses without tools)
1. Section content required (getTopicSectionText or getTopicSectionsText or getGraphicContent)
2. No invented clinical quantities
3. No graphic interpretation
"""

import re
from typing import Any, Optional

from pydantic import BaseModel


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


class TurnContext(BaseModel):
    tool_calls: list[ToolCallRecord] = []
    answer: str = ""
    tool_results: list[str] = []
    fetched_sections: list[FetchedSection] = []
    graphic_ids: set[str] = set()
    graphic_titles: dict[str, str] = {}
    user_question: str = ""
    topic_titles: dict[str, str] = {}
    outline_sections: dict[str, dict[str, str]] = {}  # topic_id -> {section_id: section_title}
    structured_topic_refs: list[TopicRef] = []
    structured_graphic_refs: list[GraphicRef] = []


class ValidationResult(BaseModel):
    passed: bool
    warnings: list[str] = []
    topic_refs: list[TopicRef] = []
    graphic_refs: list[GraphicRef] = []
    blocked_reason: Optional[str] = None


# ── Clinical terms for intent detection ─────────────────────────────

CONVERSATIONAL_USER_REGEX = re.compile(
    r"(?i)^\s*(hi|hello|hey|greetings|who are you|thanks|thank you|help|what can you do)\b.*"
)


# ── Regex constants (matching Kotlin) ────────────────────────────────

CLINICAL_QUANTITY_REGEX = re.compile(
    r"\b\d+(?:[\.,]\d+)?\s*"
    r"(?:"
    r"(?:mg|mcg|g|kg|mL|L|mmol|mEq|IU|U|units?|bpm|mmHg|cm|mm|m2)\b"
    r"(?:/(?:kg|g|mg|mcg|mL|L|dL|m2|min|hr|hour|day|24h|[a-zA-Z0-9]+))*"
    r"|"
    r"[a-zA-Z]{1,6}/[a-zA-Z0-9]{1,10}(?:/[a-zA-Z0-9]{1,10})*"
    r"|"
    r"%"
    r")",
    re.IGNORECASE,
)

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


class SafetyValidator:
    """Clinical safety validator with intent-aware rules."""

    @staticmethod
    def is_non_clinical_response(context: TurnContext) -> bool:
        """Detect if a response is conversational (no medical claims).

        Returns True if the user question is conversational and the response
        is short, or if the response contains no dosing quantities.
        """
        # If user question is conversational and response is short, bypass
        if CONVERSATIONAL_USER_REGEX.match(context.user_question.strip()) and len(context.answer) < 500:
            return True

        # Check if response contains actual dosing quantities (not just clinical words)
        return not CLINICAL_QUANTITY_REGEX.search(context.answer)

    def validate(self, context: TurnContext) -> ValidationResult:
        """Run all validation rules. Short-circuit on first hard block (passed=False)."""
        all_warnings = []

        # Rule 0: Intent-aware tool call check
        if not context.tool_calls:
            if self.is_non_clinical_response(context):
                return ValidationResult(
                    passed=True,
                    warnings=[],
                    topic_refs=context.structured_topic_refs,
                    graphic_refs=context.structured_graphic_refs,
                )
            return ValidationResult(
                passed=False,
                blocked_reason="Clinical recommendations require database verification. No database tools were executed.",
            )

        # Rule 1: Section content required
        result = self._validate_section_content_required(context)
        if result and not result.passed:
            return result

        # Rule 2: No invented clinical quantities
        result = self._validate_no_invented_numbers(context)
        if result and not result.passed:
            return result
        if result and result.warnings:
            all_warnings.extend(result.warnings)

        # Rule 3: No graphic interpretation
        result = self._validate_no_graphic_interpretation(context)
        if result and not result.passed:
            return result
        if result and result.warnings:
            all_warnings.extend(result.warnings)

        return ValidationResult(
            passed=True,
            warnings=all_warnings,
            topic_refs=context.structured_topic_refs,
            graphic_refs=context.structured_graphic_refs,
        )

    def _validate_section_content_required(self, ctx: TurnContext) -> Optional[ValidationResult]:
        has_section = any(
            tc.tool_name in ("get_topic_sections_text", "get_graphic_content") and tc.success
            for tc in ctx.tool_calls
        )
        if not has_section:
            return ValidationResult(
                passed=False,
                blocked_reason="Answer requires section or table content retrieval. Only topic outlines or searches were performed.",
            )
        return None

    def _validate_no_invented_numbers(self, ctx: TurnContext) -> Optional[ValidationResult]:
        if not ctx.tool_results:
            return None

        all_tool_text = " ".join(ctx.tool_results)
        user_question_text = ctx.user_question

        # Extract clinical quantities from the answer
        answer_metrics = {m.strip() for m in CLINICAL_QUANTITY_REGEX.findall(ctx.answer) if m.strip()}
        if not answer_metrics:
            return None

        unverified = []
        for metric in answer_metrics:
            # Quantities stated by the user are patient-specific values —
            # substring containment mirrors Kotlin's contains(metric, ignoreCase).
            if user_question_text and metric.lower() in user_question_text.lower():
                continue
            if self._is_quantity_in_text(metric, all_tool_text):
                continue
            unverified.append(metric)

        if unverified:
            unverified_str = ", ".join(unverified[:5])
            return ValidationResult(
                passed=False,
                blocked_reason=(
                    f"Unverified clinical quantities found in response: {unverified_str}. "
                    "Ensure every quantity or dosage matches the retrieved database section text exactly."
                ),
            )
        return None

    @staticmethod
    def _normalize_quantity(metric: str) -> list[str]:
        """Expand a clinical quantity into its numeric components.

        Handles ranges ('5-10 mg' -> ['5', '10']), thousand commas ('1,200 mg' -> ['1,200', '1200']),
        and direct numeric parts.
        """
        expanded = []
        # Expand ranges: "5-10 mg" -> ["5", "10"]
        range_match = re.search(r"(\d[\d.,]*)\s*[-–—]\s*(\d[\d.,]*)", metric)
        if range_match:
            expanded.append(range_match.group(1))
            expanded.append(range_match.group(2))
        # Also extract the numeric part for direct match
        num_match = re.search(r"(\d[\d.,]*)", metric)
        if num_match:
            raw_num = num_match.group(1)
            expanded.append(raw_num)
            uncomma = raw_num.replace(",", "")
            if uncomma != raw_num:
                expanded.append(uncomma)
        return expanded if expanded else [metric]

    @staticmethod
    def _is_quantity_in_text(metric: str, text: str) -> bool:
        """Check if a clinical quantity (or its normalized forms) appears in text.

        Handles ranges, missing spaces ('10mg' vs '10 mg'), thousand commas ('1,200' vs '1200'),
        and boundary matching.
        """
        variants = SafetyValidator._normalize_quantity(metric)
        text_uncomma = text.replace(",", "")
        text_unspace = re.sub(r"\s+", "", text)

        for variant in variants:
            escaped = re.escape(variant)
            if re.search(rf"(?<!\d){escaped}(?!\d)", text, re.IGNORECASE):
                return True
            # Check in text stripped of thousand separator commas
            uncomma = variant.replace(",", "")
            if uncomma:
                uncomma_escaped = re.escape(uncomma)
                if re.search(rf"(?<!\d){uncomma_escaped}(?!\d)", text_uncomma, re.IGNORECASE):
                    return True
            # Also try without space before unit (e.g., "10mg" in text when metric is "10 mg")
            compact = re.sub(r"\s+", "", metric)
            if compact != metric and re.search(rf"(?<!\d){escaped}(?!\d)", text_unspace, re.IGNORECASE):
                return True
        return False

    def _validate_no_graphic_interpretation(self, ctx: TurnContext) -> Optional[ValidationResult]:
        answer = ctx.answer
        has_visual_desc = any(p.search(answer) for p in GRAPHIC_INTERPRETATION_PATTERNS)
        if not has_visual_desc:
            return None

        # Check if visual language appears in retrieved text (quoting vs interpreting)
        tool_text = " ".join(ctx.tool_results)
        visual_in_tool_text = any(p.search(tool_text) for p in GRAPHIC_INTERPRETATION_PATTERNS)
        if visual_in_tool_text:
            return None

        return ValidationResult(
            passed=False,
            blocked_reason=(
                "Answer describes visual details of a graphic or scan that were not retrieved as text. "
                "Direct users to view the image source directly."
            ),
        )
