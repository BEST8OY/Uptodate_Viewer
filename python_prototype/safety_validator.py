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


class TurnContext(BaseModel):
    tool_calls: list[ToolCallRecord] = []
    answer: str = ""
    tool_results: list[str] = []
    fetched_sections: list[FetchedSection] = []
    graphic_ids: list[str] = []  # insertion-ordered for deterministic citation auto-population (cf. Kotlin CopyOnWriteArraySet)
    graphic_titles: dict[str, str] = {}
    graphic_to_topic: dict[str, str] = {}  # graphic_id -> topic_id
    user_question: str = ""
    topic_titles: dict[str, str] = {}
    outline_sections: dict[str, dict[str, str]] = {}  # topic_id -> {section_id: section_title}
    structured_topic_refs: list[TopicRef] = []
    structured_graphic_refs: list[GraphicRef] = []
    patient_profile: Optional[PatientProfile] = None


class ValidationResult(BaseModel):
    passed: bool
    warnings: list[str] = []
    topic_refs: list[TopicRef] = []
    graphic_refs: list[GraphicRef] = []
    blocked_reason: Optional[str] = None


# ── Clinical terms for intent detection ─────────────────────────────

CONVERSATIONAL_USER_REGEX = re.compile(
    r"(?i)^\s*(hi|hello|hey|greetings|good\s+(?:morning|afternoon|evening)|who are you|thanks|thank you|help|what can you do|what are your capabilities)\b.*"
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
    r"(?!(?:and/or|w/o|s/p|r/o|c/o|times?|doses?|tabs?|tablets?|caps?|capsules?|puffs?|drops?)\b)[a-zA-Z]{1,6}/[a-zA-Z0-9]{1,10}(?:/[a-zA-Z0-9]{1,10})*"
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


NOISE_ALLERGY_WORDS = {
    "allergy", "allergies", "allergic", "reaction", "reactions", "anaphylaxis",
    "severe", "mild", "moderate", "hives", "rash", "swelling", "induced",
    "drugs", "drug", "history", "known", "of", "to", "with", "and", "or", "patient", "has"
}

ALLERGY_CROSS_REACTIVITY = {
    "penicillin": [
        "penicillin", "amoxicillin", "ampicillin", "piperacillin", "ticarcillin",
        "oxacillin", "nafcillin", "dicloxacillin", "augmentin", "unasyn", "zosyn",
        "amoxil", "clavulanate",
    ],
    "penicillins": [
        "penicillin", "amoxicillin", "ampicillin", "piperacillin", "ticarcillin",
        "oxacillin", "nafcillin", "dicloxacillin", "augmentin", "unasyn", "zosyn",
        "amoxil", "clavulanate",
    ],
    "amoxicillin": [
        "amoxicillin", "penicillin", "ampicillin", "augmentin",
    ],
    "ampicillin": [
        "ampicillin", "amoxicillin", "penicillin", "unasyn",
    ],
    "sulfa": [
        "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
        "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole",
    ],
    "sulfonamide": [
        "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
        "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole",
    ],
    "sulfonamides": [
        "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
        "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole",
    ],
    "bactrim": [
        "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
        "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole",
    ],
    "septra": [
        "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
        "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole",
    ],
    "sulfamethoxazole": [
        "sulfa", "sulfamethoxazole", "bactrim", "septra", "sulfadiazine",
        "sulfasalazine", "sulfisoxazole", "trimethoprim-sulfamethoxazole",
    ],
    "cephalosporin": [
        "cephalosporin", "cephalexin", "cefazolin", "ceftriaxone", "cefuroxime",
        "cefpodoxime", "cefepime", "cefaclor", "cefdinir",
    ],
    "cephalosporins": [
        "cephalosporin", "cephalexin", "cefazolin", "ceftriaxone", "cefuroxime",
        "cefpodoxime", "cefepime", "cefaclor", "cefdinir",
    ],
    "aspirin": [
        "aspirin", "acetylsalicylic", "asa", "ibuprofen", "naproxen", "indomethacin",
        "ketorolac", "meloxicam", "celecoxib", "diclofenac", "nsaid", "nsaids",
    ],
    "asa": [
        "aspirin", "acetylsalicylic", "asa", "ibuprofen", "naproxen", "indomethacin",
        "ketorolac", "meloxicam", "celecoxib", "diclofenac", "nsaid", "nsaids",
    ],
    "acetylsalicylic": [
        "aspirin", "acetylsalicylic", "asa", "ibuprofen", "naproxen", "indomethacin",
        "ketorolac", "meloxicam", "celecoxib", "diclofenac", "nsaid", "nsaids",
    ],
    "nsaid": [
        "nsaid", "nsaids", "ibuprofen", "naproxen", "indomethacin", "ketorolac",
        "meloxicam", "celecoxib", "diclofenac", "aspirin",
    ],
    "nsaids": [
        "nsaid", "nsaids", "ibuprofen", "naproxen", "indomethacin", "ketorolac",
        "meloxicam", "celecoxib", "diclofenac", "aspirin",
    ],
    "fluoroquinolone": [
        "ciprofloxacin", "levofloxacin", "moxifloxacin", "ofloxacin",
    ],
    "fluoroquinolones": [
        "ciprofloxacin", "levofloxacin", "moxifloxacin", "ofloxacin",
    ],
    "ciprofloxacin": [
        "ciprofloxacin", "levofloxacin", "moxifloxacin",
    ],
    "macrolide": [
        "azithromycin", "clarithromycin", "erythromycin",
    ],
    "macrolides": [
        "azithromycin", "clarithromycin", "erythromycin",
    ],
    "azithromycin": [
        "azithromycin", "clarithromycin", "erythromycin",
    ],
    "tetracycline": [
        "doxycycline", "minocycline", "tetracycline",
    ],
    "tetracyclines": [
        "doxycycline", "minocycline", "tetracycline",
    ],
    "doxycycline": [
        "doxycycline", "minocycline", "tetracycline",
    ],
}

RENAL_CONTRAINDICATED_DRUGS = [
    "ibuprofen", "indomethacin", "naproxen", "ketorolac", "meloxicam", "celecoxib",
    "diclofenac", "sulindac", "piroxicam", "etodolac", "nsaid", "nsaids",
    "metformin", "nitrofurantoin",
]

RENAL_ADJUSTMENT_REQUIRED_DRUGS = [
    "colchicine", "apixaban", "enoxaparin", "gabapentin", "pregabalin",
    "allopurinol", "ciprofloxacin", "levofloxacin", "vancomycin", "digoxin", "spironolactone",
]

RENAL_ADJUSTMENT_KEYWORDS_REGEX = re.compile(
    r"(?i)\b(?:renal (?:dose|dosing|adjustment|adjustments|impairment|clearance)|dose (?:adjustment|adjustments|reduction|reduced)|reduced? dose|decreased dose|adjusted (?:dose|dosing|for)|eGFR|CrCl|creatinine clearance|kidney function|reduced frequency)\b"
)


class SafetyValidator:
    """Clinical safety validator with intent-aware rules."""

    @staticmethod
    def is_non_clinical_response(context: TurnContext) -> bool:
        """Detect if a response is conversational (no medical claims).

        Returns True if the user question is conversational and the response
        is short, or if the response contains no dosing quantities.
        """
        # If response contains actual clinical quantities or percentages, it cannot bypass as non-clinical
        if CLINICAL_QUANTITY_REGEX.search(context.answer):
            return False

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

        # Rule 4: Patient profile bounds (renal & allergy)
        result = self._validate_patient_profile_bounds(context)
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
            tc.tool_name in (
                "get_topic_sections_text",
                "getTopicSectionsText",
                "get_topic_overview",
                "getTopicOverview",
                "get_graphic_content",
                "getGraphicContent",
            )
            and tc.success
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
        # Decode any escaped unicode sequences (e.g. \u2265 -> ≥) so trailing digits do not corrupt boundary regexes
        all_tool_text = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), all_tool_text)
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
        thousand_comma = re.compile(r"\d{1,3}(,\d{3})+")
        for variant in variants:
            escaped = re.escape(variant)
            if re.search(r"[a-zA-Z]", variant):
                if re.search(rf"\b{escaped}\b", text, re.IGNORECASE):
                    return True
            else:
                boundary_pat = rf"(?<![\d.]){escaped}(?!\.\d)(?!\d)"
                if re.search(boundary_pat, text, re.IGNORECASE):
                    return True
                # Only uncomma when the variant itself is comma-free (no-op strip) or a strict thousand-
                # separator form (e.g. 1,200); decimal commas (e.g. 1,5) must NOT collapse to ("15")
                # — that would false-verify a 10x dose. Mirrors Kotlin thousandSeparatorOnly guard.
                uncomma = (
                    variant.replace(",", "")
                    if ("," not in variant or thousand_comma.fullmatch(variant))
                    else ""
                )
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
    def _get_metric_equivalents(metric: str) -> list[str]:
        m = re.search(r"^(\d+(?:[\.,]\d+)?)\s*(mg|mcg|[μµ]g|g|kg|mL|L)\b", metric.strip(), re.IGNORECASE)
        if not m:
            return []
        num_str = m.group(1).replace(",", ".")
        unit = m.group(2).lower()
        try:
            d_val = float(num_str)
        except ValueError:
            return []

        def fmt(d: float) -> str:
            return str(int(d)) if d.is_integer() else str(d)

        eqs = []
        if unit == "g":
            eqs.append(fmt(d_val * 1000.0))
        elif unit == "mg":
            eqs.append(fmt(d_val / 1000.0))
            eqs.append(fmt(d_val * 1000.0))
        elif unit in ("mcg", "μg", "µg"):
            eqs.append(fmt(d_val / 1000.0))
        elif unit == "l":
            eqs.append(fmt(d_val * 1000.0))
        elif unit == "ml":
            eqs.append(fmt(d_val / 1000.0))
        return eqs

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
            if SafetyValidator._is_single_number_in_text(num_match.group(1), text):
                return True
            for eq_num in SafetyValidator._get_metric_equivalents(metric):
                if SafetyValidator._is_single_number_in_text(eq_num, text):
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

    def _validate_patient_profile_bounds(self, ctx: TurnContext) -> Optional[ValidationResult]:
        profile = ctx.patient_profile
        if not profile:
            return None
        answer = ctx.answer

        # 1. Programmatic Allergy Cross-Referencing
        allergy_res = self._check_patient_allergies(profile, answer)
        if allergy_res and not allergy_res.passed:
            return allergy_res

        # 2. Programmatic Renal Impairment & Dose Adjustments
        renal_res = self._check_patient_renal_bounds(profile, answer, ctx.user_question)
        if renal_res and not renal_res.passed:
            return renal_res

        return None

    def _check_patient_allergies(self, profile: PatientProfile, answer: str) -> Optional[ValidationResult]:
        if not profile.allergies:
            return None

        for raw_allergy in profile.allergies:
            if not raw_allergy.strip():
                continue
            tokens = self._extract_allergen_tokens(raw_allergy)
            for token in tokens:
                candidate_drugs = ALLERGY_CROSS_REACTIVITY.get(token, [token])
                for drug in candidate_drugs:
                    drug_pattern = rf"(?i)\b{re.escape(drug)}\b"
                    if re.search(drug_pattern, answer):
                        if not self._is_drug_explicitly_avoided(drug, answer):
                            return ValidationResult(
                                passed=False,
                                blocked_reason=(
                                    f"Documented patient allergy contraindication: Patient has documented allergy to "
                                    f"'{raw_allergy}', but the response recommends '{drug}'. "
                                    f"Prescribe an alternative non-cross-reactive medication."
                                ),
                            )
        return None

    def _check_patient_renal_bounds(self, profile: PatientProfile, answer: str, user_question: str) -> Optional[ValidationResult]:
        combined_text = f"{' '.join(profile.conditions)} {profile.notes}"
        if user_question:
            combined_text += f" {user_question}"

        renal_desc = self._detect_renal_impairment(combined_text)
        if not renal_desc:
            return None

        # 1. Strictly contraindicated medications
        for drug in RENAL_CONTRAINDICATED_DRUGS:
            drug_pattern = rf"(?i)\b{re.escape(drug)}\b"
            if re.search(drug_pattern, answer):
                if not self._is_drug_explicitly_avoided(drug, answer):
                    return ValidationResult(
                        passed=False,
                        blocked_reason=(
                            f"Documented patient renal contraindication: Patient has documented {renal_desc}, "
                            f"but the response recommends {drug}. {drug} is contraindicated in severe renal impairment."
                        ),
                    )

        # 2. Medications requiring explicit renal dose adjustment
        mentions_adjustment = bool(RENAL_ADJUSTMENT_KEYWORDS_REGEX.search(answer))
        for drug in RENAL_ADJUSTMENT_REQUIRED_DRUGS:
            drug_pattern = rf"(?i)\b{re.escape(drug)}\b"
            if re.search(drug_pattern, answer):
                if not self._is_drug_explicitly_avoided(drug, answer) and not mentions_adjustment:
                    return ValidationResult(
                        passed=False,
                        blocked_reason=(
                            f"Documented patient renal contraindication: Patient has documented {renal_desc}. "
                            f"{drug} requires renal dosage adjustment or caution, but no renal adjustment was specified."
                        ),
                    )

        return None

    @staticmethod
    def _detect_renal_impairment(text: str) -> Optional[str]:
        if not text.strip():
            return None

        if re.search(r"(?i)\b(?:eGFR|CrCl|GFR|creatinine clearance)\s*<\s*30\b", text):
            return "severe renal impairment (eGFR/CrCl < 30 mL/min)"

        num_match = re.search(r"(?i)\b(?:eGFR|CrCl|GFR|creatinine clearance)\s*(?:of|is|:|=|<=|<)?\s*(\d+(?:\.\d+)?)\s*(?:mL/min)?\b", text)
        if num_match:
            try:
                d_val = float(num_match.group(1))
                if d_val < 30.0:
                    return f"severe renal impairment (eGFR/CrCl < 30 mL/min: {d_val} mL/min)"
            except ValueError:
                pass

        if re.search(r"(?i)\b(?:severe (?:renal impairment|ckd|chronic kidney disease|renal failure|kidney disease)|ckd stage [45]|ckd [45]|stage [45] (?:ckd|chronic kidney disease)|esrd|end-stage renal disease)\b", text):
            return "severe renal impairment (CKD Stage 4/5 or ESRD)"

        return None

    @staticmethod
    def _is_drug_explicitly_avoided(drug: str, text: str) -> bool:
        escaped = re.escape(drug)
        p1 = rf"(?i)\b(?:avoid|avoided|avoiding|do not (?:use|prescribe|administer|give)|should not (?:be used|be prescribed|be given)|not recommended|hold|withhold|discontinue)\s+(?:\w+\s+){{0,3}}{escaped}\b"
        p2 = rf"(?i)\b{escaped}\s+(?:\w+\s+){{0,4}}(?:is\s+)?(?:contraindicated|avoided|not recommended|inappropriate)\b"
        p3 = rf"(?i)\b(?:contraindicated|contraindications?)\b[^.!?\n]*?\b{escaped}\b"
        p4 = rf"(?i)\b{escaped}\s+allerg(?:y|ies|ic)\b"
        p5 = rf"(?i)\ballerg(?:y|ies|ic)\s+(?:to\s+)?{escaped}\b"
        return bool(re.search(p1, text) or re.search(p2, text) or re.search(p3, text) or re.search(p4, text) or re.search(p5, text))

    @staticmethod
    def _extract_allergen_tokens(raw_allergy: str) -> list[str]:
        clean = re.sub(r"[^a-z0-9]", " ", raw_allergy.lower())
        tokens = [t.strip() for t in clean.split() if t.strip()]
        return [t for t in tokens if len(t) >= 3 and t not in NOISE_ALLERGY_WORDS]
