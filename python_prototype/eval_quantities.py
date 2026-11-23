"""Offline quantity and number normalization for CDS evaluation benchmarks.

Grounded in Milestone 6 & AGENTS.md quantitative grounding evaluation rules.
"""

from __future__ import annotations

import re

NUM_PATTERN = r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:[.,]\d+)?"
CLINICAL_QUANTITY_REGEX = re.compile(
    r"(?<![\w.,])(" + NUM_PATTERN + r")(?:\s*(?:-|–|—|to)\s*(" + NUM_PATTERN + r"))?\s*"
    r"((?:[μµ]g|ug|mcg|mg|g|mEq|mmol|mOsm|mU|IU|units?|mL|L|ng|pg)(?:\s*/\s*(?:kg|m2|m²|dL|L|mL|min|hr|h|day|d|1\.73\s*m[2²]))+(?:\s*/\s*(?:min|hr|h|day|d|1\.73\s*m[2²]))?|"
    r"[μµ]g|ug|mcg|mg|mEq|mmol|mOsm|mU|mL|m2|m²|IU|ng|pg|kg|dL|mmHg|cmH2O|g|L|%|percent|units?|years?|months?|weeks?|days?|hours?|minutes?|q\d+h?)(?![\w/])",
    re.IGNORECASE,
)
THOUSAND_COMMA_REGEX = re.compile(r"^\d{1,3}(?:,\d{3})+(?:\.\d+)?$")

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


def canonical_number(raw: str) -> str:
    """Normalizes thousand-separated numbers while preserving decimal commas."""
    trimmed = raw.strip()
    if "," in trimmed and "." in trimmed:
        dot_normalized = trimmed.replace(",", "")
    elif THOUSAND_COMMA_REGEX.match(trimmed):
        dot_normalized = trimmed.replace(",", "")
    elif "," in trimmed:
        dot_normalized = trimmed.replace(",", ".")
    else:
        dot_normalized = trimmed
    if "." not in dot_normalized:
        return dot_normalized
    trimmed_num = dot_normalized.rstrip("0").rstrip(".")
    return trimmed_num or "0"


def canonical_unit(raw: str) -> str:
    parts = re.sub(r"\s*/\s*", "/", raw.strip().lower()).split("/")
    mapped = "/".join(
        _VARIANT_UNIT_ALIASES.get(part, part.replace("m²", "m2").replace(" ", ""))
        for part in parts
    )
    return _UNIT_ALIASES.get(mapped, mapped)


def extract_quantities(text: str) -> dict[str, str]:
    """Canonical '<number> <unit>' value -> the form first encountered in the text."""
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
