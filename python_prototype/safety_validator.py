"""Clinical safety validator — mirrors Kotlin SafetyValidator.kt exactly.

5 validation rules, short-circuit on first hard block:
1. Tool calls required
1b. Section content required (getTopicSectionText or getGraphicContent)
2. Citation consistency with fetched sections
3. No invented clinical quantities
4. Citations required
5. No graphic interpretation
"""

import re
from typing import Optional

from pydantic import BaseModel, Field


class Citation(BaseModel):
    topic_title: str
    section_title: str
    section_id: str = "unknown"


class ToolCallRecord(BaseModel):
    tool_name: str
    arguments: dict[str, str]
    result: str
    success: bool


class FetchedSection(BaseModel):
    topic_id: str
    section_id: str
    section_title: str


class TurnContext(BaseModel):
    tool_calls: list[ToolCallRecord] = []
    answer: str = ""
    citations: list[Citation] = []
    tool_results: list[str] = []
    fetched_sections: list[FetchedSection] = []
    graphic_ids: set[str] = set()
    user_question: str = ""


class ValidationResult(BaseModel):
    passed: bool
    warnings: list[str] = []
    citations: list[Citation] = []
    blocked_reason: Optional[str] = None


# ── Regex constants (matching Kotlin) ────────────────────────────────

CLINICAL_QUANTITY_REGEX = re.compile(
    r"\b\d+[\.,]?\d*\s*(?:mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg|g|mg/dL|mmol/L|mEq/L|IU|bpm|mcg/kg|mg/kg)\b",
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
    """Clinical safety validator with 5 rules."""

    @staticmethod
    def parse_citations(answer: str) -> list[Citation]:
        """Extract citations from answer text.

        Handles markdown formatting: bullets (-, *), numbered lists (1., 2.),
        bold markers (**), and inline formatting.
        """
        citations = []
        pattern = re.compile(
            r"^\s*(?:[-*]|\d+\.)?\s*(?:\*\*)?Topic:(?:\*\*)?\s*(.+?),\s*(?:\*\*)?Section:(?:\*\*)?\s*(.+?)(?:\s*\(ID:\s*([a-zA-Z0-9_-]+)\))?(?:\*\*)?\s*(?=\s*(?:$|\n))",
            re.IGNORECASE | re.MULTILINE,
        )
        for match in pattern.finditer(answer):
            topic_title = match.group(1).strip().strip("*")
            section_title = match.group(2).strip().strip("*")
            section_id = match.group(3).strip() if match.group(3) else "unknown"
            citations.append(
                Citation(
                    topic_title=topic_title,
                    section_title=section_title,
                    section_id=section_id,
                )
            )
        return citations

    def validate(self, context: TurnContext) -> ValidationResult:
        """Run all 5 validation rules. Short-circuit on first hard block (passed=False)."""
        all_warnings = []

        # Rule 1: Tool calls required
        result = self._validate_tool_call_required(context)
        if result and not result.passed:
            return result

        # Rule 1b: Section content required
        result = self._validate_section_content_required(context)
        if result and not result.passed:
            return result

        # Rule 2: Citation consistency
        result = self._validate_citation_consistency(context)
        if result and not result.passed:
            return result
        if result and result.warnings:
            all_warnings.extend(result.warnings)

        # Rule 3: No invented clinical quantities
        result = self._validate_no_invented_numbers(context)
        if result and not result.passed:
            return result
        if result and result.warnings:
            all_warnings.extend(result.warnings)

        # Rule 4: Citations required
        result = self._validate_citation_required(context)
        if result and not result.passed:
            return result

        # Rule 5: No graphic interpretation
        result = self._validate_no_graphic_interpretation(context)
        if result and not result.passed:
            return result
        if result and result.warnings:
            all_warnings.extend(result.warnings)

        return ValidationResult(passed=True, warnings=all_warnings, citations=context.citations)

    def _validate_tool_call_required(self, ctx: TurnContext) -> Optional[ValidationResult]:
        if not ctx.tool_calls:
            return ValidationResult(
                passed=False,
                blocked_reason="No tool calls were made. Clinical answers require database retrieval.",
            )
        return None

    def _validate_section_content_required(self, ctx: TurnContext) -> Optional[ValidationResult]:
        has_section = any(
            tc.tool_name in ("get_topic_section_text", "get_graphic_content") and tc.success
            for tc in ctx.tool_calls
        )
        if not has_section:
            return ValidationResult(
                passed=False,
                blocked_reason="Answer requires section or table content retrieval. Only topic outlines or searches were performed.",
            )
        return None

    def _validate_citation_consistency(self, ctx: TurnContext) -> Optional[ValidationResult]:
        fetched_ids = {fs.section_id for fs in ctx.fetched_sections}
        warnings = []
        for citation in ctx.citations:
            id_match = citation.section_id != "unknown" and citation.section_id in fetched_ids
            title_match = any(
                fs.section_title.lower() in citation.section_title.lower()
                or citation.section_title.lower() in fs.section_title.lower()
                for fs in ctx.fetched_sections
            )

            if not id_match and not title_match and ctx.fetched_sections:
                return ValidationResult(
                    passed=False,
                    blocked_reason=(
                        f"Citation references section '{citation.section_title}' "
                        f"(ID: {citation.section_id}) which was not retrieved in this turn."
                    ),
                    warnings=warnings,
                )

            if not id_match and title_match:
                warnings.append(
                    f"Citation section ID '{citation.section_id}' matched via title fallback."
                )

        if warnings:
            return ValidationResult(passed=True, warnings=warnings)
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

        # Extract numbers from user's question — these are patient-specific values
        question_metrics = set()
        if user_question_text:
            question_metrics = {m.strip() for m in CLINICAL_QUANTITY_REGEX.findall(user_question_text) if m.strip()}

        unverified = []
        for metric in answer_metrics:
            if metric in question_metrics:
                continue

            # Extract just the numeric part and check if it appears in tool text
            num_match = re.match(r"^(\d[\d.,]*)", metric)
            if num_match:
                num_part = re.escape(num_match.group(1))
                if re.search(rf"(?<!\d){num_part}(?!\d)", all_tool_text, re.IGNORECASE):
                    continue

            unverified.append(metric)

        if unverified:
            return ValidationResult(
                passed=False,
                blocked_reason=(
                    "Answer contains clinical quantities not traceable to retrieved source data: "
                    f"{', '.join(unverified[:5])}"
                ),
            )
        return None

    def _validate_citation_required(self, ctx: TurnContext) -> Optional[ValidationResult]:
        if not ctx.citations:
            return ValidationResult(
                passed=False,
                blocked_reason="No citations provided. Every clinical answer must cite its source section.",
            )

        # Require at least one citation that matches a fetched section
        if ctx.fetched_sections:
            fetched_ids = {fs.section_id for fs in ctx.fetched_sections}
            valid_citation_found = any(
                c.section_id in fetched_ids
                or any(
                    fs.section_title.lower() in c.section_title.lower()
                    for fs in ctx.fetched_sections
                )
                for c in ctx.citations
            )
            if not valid_citation_found:
                return ValidationResult(
                    passed=False,
                    blocked_reason="None of the citations match the sections fetched during database retrieval.",
                )

        return None

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
