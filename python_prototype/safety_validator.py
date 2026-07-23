"""Clinical safety validator — mirrors Kotlin SafetyValidator.kt exactly.

6 validation rules, short-circuit on first hard block:
1. Tool calls required
1b. Section content required (getTopicSectionText)
2. Citation consistency with fetched sections
3. No invented numbers (regex-validated)
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

NUMERIC_TOKEN_REGEX = re.compile(
    r"\d+[\.,]?\d*\s*(?:mg|%|mL|mmol|mcg|units?|mEq|L|kg|cm|mmHg)?"
)

GRAPHIC_REF_REGEX = re.compile(r"Graphic-[a-zA-Z0-9_-]+")

VISUAL_INTERPRETATION_PATTERNS = [
    re.compile(
        r"the\s+(?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|"
        r"pathology|slide|specimen|scan|film|rogram)\s+"
        r"(?:shows?|demonstrates?|reveals?|suggests?|indicates?|is consistent with|"
        r"displays?|exhibits?|captures?)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?:image|photo|picture|x-?ray|ct|mri|ecg|ekg|ultrasound|echo|"
        r"pathology|slide|specimen|scan|film|rogram)\s+"
        r"(?:findings?|abnormalities?|results?|interpretation|read|analysis|review)",
        re.IGNORECASE,
    ),
    re.compile(
        r"(?:visual(?:ized|ly)?|visible|appears?\s+to\s+show|can\s+be\s+seen|"
        r"notable\s+on\s+(?:the\s+)?(?:image|scan|x-?ray|mri|ct))",
        re.IGNORECASE,
    ),
    re.compile(
        r"the\s+(?:figure|algorithm|diagram|picture|graphic|table)\s+"
        r"(?:shows?|demonstrates?|reveals?|illustrates?|depicts?|displays?)",
        re.IGNORECASE,
    ),
]


class SafetyValidator:
    """Clinical safety validator with 6 rules."""

    @staticmethod
    def parse_citations(answer: str) -> list[Citation]:
        """Extract citations from answer text.

        Expected format (one per line):
            Topic: <title>, Section: <title> (ID: <id>)
        """
        citations = []
        pattern = re.compile(
            r"^\s*Topic:\s*(.+?),\s*Section:\s*(.+?)(?:\s*\(ID:\s*([a-zA-Z0-9_-]+)\))?\s*$",
            re.IGNORECASE | re.MULTILINE,
        )
        for match in pattern.finditer(answer):
            citations.append(
                Citation(
                    topic_title=match.group(1).strip(),
                    section_title=match.group(2).strip(),
                    section_id=match.group(3).strip() if match.group(3) else "unknown",
                )
            )
        return citations

    def validate(self, context: TurnContext) -> ValidationResult:
        """Run all 6 validation rules. Short-circuit on first hard block (passed=False)."""
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

        # Rule 3: No invented numbers
        result = self._validate_no_invented_numbers(context)
        if result and not result.passed:
            return result

        # Rule 4: Citations required
        result = self._validate_citation_required(context)
        if result and not result.passed:
            return result

        # Rule 5: No graphic interpretation
        result = self._validate_no_graphic_interpretation(context)
        if result and not result.passed:
            if all_warnings:
                result.warnings = all_warnings + result.warnings
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
            tc.tool_name == "get_topic_section_text" and tc.success
            for tc in ctx.tool_calls
        )
        if not has_section:
            return ValidationResult(
                passed=False,
                blocked_reason="Answer requires actual section content. Only topic titles or outlines were retrieved.",
            )
        return None

    def _validate_citation_consistency(self, ctx: TurnContext) -> Optional[ValidationResult]:
        fetched_ids = {fs.section_id for fs in ctx.fetched_sections}
        warnings = []
        for citation in ctx.citations:
            if citation.section_id not in fetched_ids:
                # Title-match fallback
                title_match = any(
                    fs.section_title.lower() == citation.section_title.lower()
                    for fs in ctx.fetched_sections
                )
                if title_match:
                    warnings.append(
                        f"Citation section_id mismatch but title matches: '{citation.section_title}'"
                    )
                else:
                    return ValidationResult(
                        passed=False,
                        blocked_reason=(
                            f"Citation references unretrieved section "
                            f"'{citation.section_title}' (ID: {citation.section_id})."
                        ),
                        warnings=warnings,
                    )
        if warnings:
            return ValidationResult(passed=True, warnings=warnings)
        return None

    def _validate_no_invented_numbers(self, ctx: TurnContext) -> Optional[ValidationResult]:
        # Strip metadata and formatting before checking numbers
        answer_body = ctx.answer
        # Strip citation lines
        answer_body = re.sub(r"^\s*Topic:.*$", "", answer_body, flags=re.MULTILINE)
        # Strip markdown list markers: "1. ", "- ", "* ", "  - "
        answer_body = re.sub(r"^\s*[-*]\s+", "", answer_body, flags=re.MULTILINE)
        answer_body = re.sub(r"^\s*\d+\.\s+", "", answer_body, flags=re.MULTILINE)
        # Strip markdown headers: "### Title"
        answer_body = re.sub(r"^#{1,6}\s+.*$", "", answer_body, flags=re.MULTILINE)
        # Strip section dividers: "---"
        answer_body = re.sub(r"^---\s*$", "", answer_body, flags=re.MULTILINE)
        # Strip bold/italic markers around numbers: **500mg** -> 500mg
        answer_body = re.sub(r"\*\*(\d)", r"\1", answer_body)
        answer_body = re.sub(r"(\d)\*\*", r"\1", answer_body)
        answer_body = re.sub(r"\*(\d)", r"\1", answer_body)
        answer_body = re.sub(r"(\d)\*", r"\1", answer_body)

        answer_numbers = {m.strip() for m in NUMERIC_TOKEN_REGEX.findall(answer_body) if m.strip()}
        # Filter out list markers and formatting artifacts (e.g., "1,", "2,", "3.")
        answer_numbers = {n for n in answer_numbers if not re.match(r"^\d+[,.]$", n)}
        if not answer_numbers:
            return None

        # Extract all numeric tokens from tool results
        tool_text = " ".join(ctx.tool_results)
        tool_numbers = {m.strip() for m in NUMERIC_TOKEN_REGEX.findall(tool_text) if m.strip()}

        # Extract numbers from user's question — these are patient-specific values, not invented
        question_numbers = set()
        if ctx.user_question:
            question_numbers = {m.strip() for m in NUMERIC_TOKEN_REGEX.findall(ctx.user_question) if m.strip()}

        # Allowed numbers = tool results + user question + citation IDs
        allowed_numbers = tool_numbers | question_numbers

        # Boundary-aware matching: "12" should NOT match as part of "123"
        # Also check if the numeric part alone is in tool text (e.g., "60 mL" -> check "60")
        invented = []
        for num in answer_numbers:
            escaped = re.escape(num)
            boundary_pattern = rf"(?<![\d]){escaped}(?![\d])"
            full_match = re.search(boundary_pattern, tool_text, re.IGNORECASE)

            # Also try matching just the numeric part (strip units)
            num_only = re.match(r"^([\d\.,]+)", num)
            num_only_match = False
            if num_only:
                num_part = num_only.group(1)
                num_only_escaped = re.escape(num_part)
                num_only_pattern = rf"(?<![\d]){num_only_escaped}(?![\d])"
                num_only_match = bool(re.search(num_only_pattern, tool_text, re.IGNORECASE))

            # Also check if numeric part matches any question number
            in_question = False
            if num_only:
                num_part = num_only.group(1)
                for q_num in question_numbers:
                    q_part = re.match(r"^([\d\.,]+)", q_num)
                    if q_part and q_part.group(1) == num_part:
                        in_question = True
                        break

            if not full_match and not num_only_match and not in_question:
                invented.append(num)

        if invented:
            # Show context for each blocked number
            details = []
            for num in sorted(invented):
                # Find the sentence containing this number
                pattern = re.compile(rf"[^.]*\b{re.escape(num)}\b[^.]*\.", re.IGNORECASE)
                match = pattern.search(ctx.answer)
                if match:
                    details.append(f'"{num}" in: {match.group(0).strip()[:80]}')
                else:
                    details.append(f'"{num}"')
            detail_text = "; ".join(details)
            return ValidationResult(
                passed=False,
                blocked_reason=(
                    f"Answer contains numbers not found in retrieved content: "
                    f"{', '.join(sorted(invented))}. Details: {detail_text}"
                ),
            )
        return None

    def _validate_citation_required(self, ctx: TurnContext) -> Optional[ValidationResult]:
        if not ctx.citations:
            return ValidationResult(
                passed=False,
                blocked_reason="No citations provided. Every clinical answer must cite its source.",
            )

        # Citation density: require that most fetched sections are cited
        if ctx.fetched_sections:
            cited_section_ids = {c.section_id for c in ctx.citations}
            fetched_section_ids = {fs.section_id for fs in ctx.fetched_sections}
            uncited = fetched_section_ids - cited_section_ids

            # Allow up to 1 uncited section (some sections are supporting context)
            if len(uncited) > 1:
                uncited_titles = [
                    fs.section_title for fs in ctx.fetched_sections
                    if fs.section_id in uncited
                ]
                return ValidationResult(
                    passed=False,
                    blocked_reason=(
                        f"Fetched sections not cited in answer: "
                        f"{', '.join(uncited_titles[:3])}. "
                        f"Every section used to compose the answer must be cited."
                    ),
                )

        return None

    def _validate_no_graphic_interpretation(self, ctx: TurnContext) -> Optional[ValidationResult]:
        warnings = []
        answer = ctx.answer

        # Check for Graphic-* references in answer
        graphic_refs = GRAPHIC_REF_REGEX.findall(answer)
        if graphic_refs:
            # Hard block if referencing graphics not fetched
            for ref in graphic_refs:
                if ref not in ctx.graphic_ids:
                    return ValidationResult(
                        passed=False,
                        blocked_reason=(
                            f"Answer references graphic '{ref}' that was not fetched. "
                            f"Use getGraphicContent to retrieve table data first."
                        ),
                    )

        # Check for visual interpretation language
        for pattern in VISUAL_INTERPRETATION_PATTERNS:
            if pattern.search(answer):
                if ctx.graphic_ids:
                    # Graphics were fetched — warn but allow
                    warnings.append(
                        "Answer contains visual interpretation language. "
                        "Ensure only table data (not images) is interpreted."
                    )
                else:
                    # No graphics fetched — hard block
                    return ValidationResult(
                        passed=False,
                        blocked_reason=(
                            "Answer contains visual/image interpretation language "
                            "but no graphic content was fetched. "
                            "Only text-based clinical data should be interpreted."
                        ),
                        warnings=warnings,
                    )

        return None
