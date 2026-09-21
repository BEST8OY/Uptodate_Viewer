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
    topic_id: Optional[str] = None


class TurnContext(BaseModel):
    tool_calls: list[ToolCallRecord] = []
    answer: str = ""
    tool_results: list[str] = []
    fetched_sections: list[FetchedSection] = []
    graphic_ids: set[str] = set()
    graphic_titles: dict[str, str] = {}
    graphic_to_topic: dict[str, str] = {}  # graphic_id -> topic_id
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


# ── Number-to-word helpers for clinical quantities ──────────────────

ONES_WORDS = {
    0: "zero", 1: "one", 2: "two", 3: "three", 4: "four", 5: "five",
    6: "six", 7: "seven", 8: "eight", 9: "nine", 10: "ten",
    11: "eleven", 12: "twelve", 13: "thirteen", 14: "fourteen", 15: "fifteen",
    16: "sixteen", 17: "seventeen", 18: "eighteen", 19: "nineteen",
}
TENS_WORDS = {
    2: "twenty", 3: "thirty", 4: "forty", 5: "fifty",
    6: "sixty", 7: "seventy", 8: "eighty", 9: "ninety",
}


def int_to_words(n: int) -> list[str]:
    """Convert integer 0-100 to English word forms."""
    if n in ONES_WORDS:
        return [ONES_WORDS[n]]
    if 20 <= n < 100:
        ten, rem = divmod(n, 10)
        t = TENS_WORDS[ten]
        return [t] if rem == 0 else [f"{t}-{ONES_WORDS[rem]}", f"{t} {ONES_WORDS[rem]}"]
    if n == 100:
        return ["one hundred", "hundred"]
    return []


# ── Regex constants (matching Kotlin) ────────────────────────────────

CLINICAL_QUANTITY_REGEX = re.compile(
    r"\b\d+(?:[\.,]\d+)?(?:\s*(?:[-–—]|to)\s*\d+(?:[\.,]\d+)?)?\s*"
    r"(?:"
    r"(?:mg|mcg|[μµ]g|g|kg|mL|L|dL|mcL|uL|[μµ]L|mmol|[uμµ]mol|nmol|pmol|mEq|IU|U|units?|bpm|mmHg|cm|mm|m2|mOsm(?:ol)?|mU|[uμµ]U|[uμµ]IU)\b"
    r"(?:/(?:kg|g|mg|mcg|[μµ]g|mL|L|dL|m2|min|hr|hour|day|24h|[a-zA-Z0-9]+))*"
    r"|"
    r"(?!(?:and/or|w/o|s/p|r/o|c/o)\b)[a-zA-Z]{1,6}/[a-zA-Z0-9]{1,10}(?:/[a-zA-Z0-9]{1,10})*"
    r"|"
    r"%|percent|percentage\b"
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
            tc.tool_name in ("get_topic_sections_text", "getTopicSectionsText", "get_graphic_content", "getGraphicContent") and tc.success
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
            # Check direct substring or numeric presence in user question
            if user_question_text:
                if metric.lower() in user_question_text.lower():
                    continue
                if self._is_quantity_in_text(metric, user_question_text):
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
    def _get_number_variants(num_str: str) -> list[str]:
        variants = [num_str]
        clean_candidates = []
        # Thousand separator: comma followed by 3 digits (e.g. 1,200)
        if re.search(r"\d+,\d{3}(?:\b|\D)", num_str):
            uncomma = num_str.replace(",", "")
            variants.append(uncomma)
            clean_candidates.append(uncomma)
        # Decimal comma: comma followed by 1 or 2 digits (e.g. 2,5)
        elif re.search(r"\d+,\d{1,2}(?:\b|\D)", num_str):
            dot_decimal = num_str.replace(",", ".")
            variants.append(dot_decimal)
            clean_candidates.append(dot_decimal)
        else:
            clean_candidates.append(num_str)

        for cand in clean_candidates:
            try:
                val = float(cand)
                if val.is_integer():
                    int_val = int(val)
                    int_str = str(int_val)
                    if int_str not in variants:
                        variants.append(int_str)
                    if 0 <= int_val <= 100:
                        for w in int_to_words(int_val):
                            if w not in variants:
                                variants.append(w)
                elif val == 0.5:
                    for f in ("half", "one-half", "one half"):
                        if f not in variants:
                            variants.append(f)
                elif val == 0.25:
                    for f in ("quarter", "one-quarter", "one quarter"):
                        if f not in variants:
                            variants.append(f)
            except ValueError:
                pass
        return variants

    @staticmethod
    def _is_single_number_in_text(num_str: str, text: str) -> bool:
        variants = SafetyValidator._get_number_variants(num_str)
        text_uncomma = text.replace(",", "")
        for variant in variants:
            escaped = re.escape(variant)
            if re.search(r"[a-zA-Z]", variant):
                if re.search(rf"\b{escaped}\b", text, re.IGNORECASE):
                    return True
            else:
                boundary_pat = rf"(?<![\d.]){escaped}(?!\.\d)(?!\d)"
                if re.search(boundary_pat, text, re.IGNORECASE):
                    return True
                uncomma = variant.replace(",", "")
                if uncomma and re.search(rf"(?<![\d.]){re.escape(uncomma)}(?!\.\d)(?!\d)", text_uncomma, re.IGNORECASE):
                    return True
        return False

    @staticmethod
    def _normalize_quantity(metric: str) -> list[str]:
        """Expand a clinical quantity into all its numeric/word components."""
        expanded = []
        range_match = re.search(r"(\d[\d.,]*)\s*(?:[-–—]|to)\s*(\d[\d.,]*)", metric, re.IGNORECASE)
        if range_match:
            expanded.extend(SafetyValidator._get_number_variants(range_match.group(1)))
            expanded.extend(SafetyValidator._get_number_variants(range_match.group(2)))
            return expanded
        num_match = re.search(r"(\d[\d.,]*)", metric)
        if num_match:
            expanded.extend(SafetyValidator._get_number_variants(num_match.group(1)))
        return expanded if expanded else [metric]

    @staticmethod
    def _is_quantity_in_text(metric: str, text: str) -> bool:
        """Check if a clinical quantity (or its normalized forms) appears in text.

        Handles ranges (requiring both bounds), word forms, trailing decimal zeros,
        and decimal-safe boundary matching.
        """
        range_match = re.search(r"(\d[\d.,]*)\s*(?:[-–—]|to)\s*(\d[\d.,]*)", metric, re.IGNORECASE)
        if range_match:
            b1, b2 = range_match.group(1), range_match.group(2)
            return SafetyValidator._is_single_number_in_text(b1, text) and SafetyValidator._is_single_number_in_text(b2, text)

        num_match = re.search(r"(\d[\d.,]*)", metric)
        if num_match:
            return SafetyValidator._is_single_number_in_text(num_match.group(1), text)
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
