"""System prompt builder — mirrors Kotlin SystemPrompt.kt.

The guidance text, retrieval rules, synthesis prompt, and evidence formatting
mirror the Kotlin implementation exactly.
"""

from __future__ import annotations

import re
from typing import Optional

from evidence_models import FetchedGraphic, FetchedSection, TurnContext

CANONICAL_ABSTENTION = "Not specified in retrieved UpToDate sections."

NOTHING_RETRIEVED_PLACEHOLDER = "Nothing has been retrieved yet."

RETRIEVAL_SYSTEM_PROMPT = """You are ClinRef AI in Retrieval Mode. Gather UpToDate evidence using tools to answer the clinician's inquiry. Do not draft a clinical answer—once sufficient evidence is loaded, stop calling tools and reply "Evidence retrieved."

### Retrieval Rules
1. **Search (`searchTopics`)**: Query 1–3 plain clinical keywords (one condition, syndrome, or generic drug per call; translate clinical vignettes into canonical diagnoses; never pass demographics, vitals, or sentences). Use parallel calls for multiple drugs/conditions. If empty, retry with `refine_with`.
2. **Inspect (`getTopicOverview`)**:
   - Standard topics return section/table titles only (no clinical text)—never stop after `getTopicOverview` unless `topicType` is `"calc"` (which returns full calculator content automatically) or `"autoPrefetched": true` already loaded all needed sections.
   - Clinical topics and their tables (`isTable: true`) often include indication-specific dosing. Also inspect a `drug` (Lexidrug Monograph) when the inquiry is drug-specific, when `requiresDrugMonograph`/`needsDrugMonograph` is true, or when the clinical topic lacks needed renal/hepatic cutoffs, contraindications, or drug interactions.
3. **Fetch Content**:
   - If `"autoPrefetched": true` is in `getTopicOverview`, those `recommendedSectionIds` and `recommendedGraphicIds` are already loaded—only fetch missing sections or a needed `drug` monograph.
   - Otherwise, call `getTopicSectionsText(topicId, sectionIds)` (batch up to 20 IDs; parent sections include subsections; include organ-adjustment or allergy-alternative sections if patient factors are present) and `getGraphicContent(graphicId)` for relevant tables (`isTable: true`)."""

STATIC_CLINICAL_GUIDANCE = RETRIEVAL_SYSTEM_PROMPT

SYNTHESIS_SYSTEM_PROMPT = f"""You are ClinRef AI, a point-of-care clinical reference for physicians. Answer the inquiry using ONLY `<retrieved_evidence>`.

### 1. Clinical Rules
- **Source-Only**: State only facts, criteria, workups, regimens, doses, and thresholds present in `<retrieved_evidence>`—never fill gaps from memory. If `<retrieved_evidence>` contains no relevant information for the inquiry, output ONLY "{CANONICAL_ABSTENTION}".
- **Patient Tailoring**: If `<active_patient_profile>` is provided or the inquiry mentions patient factors (age, renal/hepatic function, pregnancy, allergies, comorbidities, medications), apply the retrieved adjustments, contraindications, or alternatives directly—leading with the patient-appropriate recommendation on Line 1. Do not perform arithmetic dose calculations; state the exact rules and doses from `<retrieved_evidence>`. If no patient factors are specified, present the standard information without mentioning that a patient profile is absent.

### 2. Output Format
Follow this Markdown structure (no preamble, no `#` heading on Line 1, no `**Label:**` colon on Line 1, and no references list):

**[1–2 complete bold declarative sentences giving the direct clinical answer, takeaway, or patient-tailored recommendation.]**

---

### [Concise Subheading]
- [Details from `<retrieved_evidence>` using bullet points, numbered steps for sequential workflows, or a Markdown table for scores/criteria/dosing]

### [Additional Subheading (include as many `###` sections as the inquiry and evidence require)]
- [Additional supporting details from `<retrieved_evidence>`]

Formatting Rules:
- Line 1 MUST start immediately with `**` and contain 1–2 complete sentences ending with a period inside the closing `**`.
- Keep a blank line before and after `---`.
- Use ONLY `###` for subheadings (never `#`, `##`, or `####`), and only include subheadings supported by `<retrieved_evidence>`.
- Omit `---` and `###` if the entire answer is 1–2 sentences or an abstention."""

# Recency-anchored synthesis contract restated in the synthesis turn.
SYNTHESIS_CONTRACT = (
    "Answer the inquiry below strictly from <retrieved_evidence> and the Active Patient Profile: "
    "(1) Open Line 1 immediately with **1–2 bold declarative sentences** stating the direct, patient-adjusted clinical conclusion (no preamble, no colon-terminated title/label); "
    "(2) If details follow, add `---` then only the `###` subheadings the question asks for and the evidence fills (omit empty headings; omit `---` and `###` for single-fact answers); "
    "(3) Copy every number, range, frequency, and compound slash unit (`mg/kg/day`, `mg/day`, `mL/min`) verbatim—never convert units (`1 g` ≠ `1000 mg`), never multiply weight-based formulas (`mg/kg`) by patient weight, and never convert spelled-out number words to digits; "
    "(4) Preserve exact GRADE verbs (`recommend` vs `suggest` vs `may`; never write `recommend`/`recommended` for a Grade 2 `suggest`); "
    f'(5) If a parameter is absent, state inline that it is "{CANONICAL_ABSTENTION}" (or output only that phrase if nothing in <retrieved_evidence> answers the question); '
    "(6) Plain text only—no `#`/`##`/`####` headers, no reference lists, and no unretrieved visual descriptions."
)


def static_clinical_guidance() -> str:
    """Invariant clinical guidance, eligible for provider-side prompt caching."""
    return STATIC_CLINICAL_GUIDANCE


def patient_context_block(patient_context: Optional[str] = None) -> str:
    """Return the dynamic patient profile context block."""
    if not patient_context:
        return ""
    return _profile_block(patient_context)


def build_system_prompt(patient_context: Optional[str] = None) -> str:
    """Join the invariant guidance with the active patient profile block."""
    profile_block = patient_context_block(patient_context)
    if profile_block:
        return f"{static_clinical_guidance()}\n\n{profile_block}"
    return static_clinical_guidance()


def build_evidence_gate_pause_prompt(evidence_summary: str = "") -> str:
    """Stage 1 remediation: the turn ended without section text or table content.

    Mirrors Kotlin SystemPrompt.buildEvidenceGatePausePrompt().
    """
    summary = (evidence_summary or "").strip() or NOTHING_RETRIEVED_PLACEHOLDER
    return "\n".join([
        "SYSTEM NOTICE — EVIDENCE GATE PAUSE:",
        "Your previous turn ended without retrieving full clinical section text or table content.",
        "",
        "RETRIEVED SO FAR:",
        summary,
        "",
        "MANDATORY NEXT STEP (tool calls only, no text):",
        "1. If topics, section ids, or table graphics are listed above, do not search for those same topics again: if the question asks for a clinical table listed under graphics, call getGraphicContent(graphicId) first; if section ids are listed for the needed topic, call getTopicSectionsText(topicId, sectionIds) with ids copied exactly from the list; if a multi-drug question requires a second drug not yet listed above, either call getTopicSectionsText(topicId, sectionIds) on the listed topic or call searchTopics(query) with ONLY the single missing drug name.",
        "2. If nothing relevant is listed, translate the clinical question or patient vignette into a 1-3 word condition or generic drug name (no age, sex, vitals like \"BP\", or symptom adjectives like \"tearing\") and call searchTopics(query), then getTopicOverview(topicId), then getTopicSectionsText(topicId, sectionIds).",
        "3. Reply with text only after the section text is in context.",
    ])


def build_premature_interception_prompt() -> str:
    """Phase-1 interception: text arrived before any evidence, so demand tool calls."""
    return """SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED.
Your last message was text, but no UpToDate section text or table has been retrieved yet, so it cannot be used as an answer.
Write no text. Respond ONLY with tool calls:
1. searchTopics(query): one condition or drug per call, 1-3 words. Use parallel calls for several entities.
2. getTopicOverview(topicId): use a topicId returned by searchTopics, copied exactly.
3. getTopicSectionsText(topicId, sectionIds): use section ids copied exactly from the overview, or getGraphicContent(graphicId) for a table.
You will write the answer after the section text is in context."""


def evidence_summary_from(
    sections: list[FetchedSection],
    graphics: Optional[list[FetchedGraphic]] = None,
) -> str:
    """Formats fetched section text and graphic table markdown into an evidence digest string.

    Mirrors Kotlin SystemPrompt.evidenceSummaryFrom().
    """
    graphics = graphics or []
    sorted_sections = sorted(sections, key=lambda s: getattr(s, "relevance_score", 0.0), reverse=True)
    sorted_graphics = sorted(graphics, key=lambda g: getattr(g, "relevance_score", 0.0), reverse=True)
    parts = []

    for sec in sorted_sections:
        if sec.content_snippet.startswith("(Content included in section [") and any(
            not s.content_snippet.startswith("(Content included in section [") for s in sorted_sections
        ):
            continue
        topic_attr = f" [Topic: {sec.topic_title}]" if sec.topic_title and sec.topic_title != sec.topic_id else ""
        header_title = sec.section_title if sec.section_title and sec.section_title != sec.section_id else "Clinical Section"
        if sec.content_snippet.strip():
            parts.append(f"Section: {header_title}{topic_attr}:\n{sec.content_snippet}")
        else:
            clean_topic = sec.topic_title if sec.topic_title and sec.topic_title != sec.topic_id else "Clinical Reference"
            parts.append(f"Section: {header_title} from topic {clean_topic}")

    for gfx in sorted_graphics:
        if gfx.markdown.strip():
            raw_md = gfx.markdown.strip()
            table_match = re.search(r"^### Graphic Table:\s*(.+)$", raw_md, re.MULTILINE)
            extracted_title = table_match.group(1).strip() if table_match else ""
            title = gfx.title if gfx.title and gfx.title != gfx.graphic_id else (extracted_title or "Clinical Table")
            topic_attr = f" [Topic: {gfx.topic_title}]" if gfx.topic_title and gfx.topic_title != gfx.topic_id else ""
            clean_md = re.sub(r"^### Graphic Table:[^\n]*\n*", "", raw_md).strip()
            parts.append(f"Table: {title}{topic_attr}:\n{clean_md}")

    if not parts:
        return "No section text was successfully fetched in the prior turn."
    return "\n---\n".join(parts)


def format_synthesis_prompt(user_question: str, tc: TurnContext) -> str:
    """Stage 2 User Prompt: Wraps <retrieved_evidence>, <active_patient_profile>, and inquiry.

    Mirrors Kotlin SystemPrompt.formatSynthesisPrompt().
    """
    evidence_summary = evidence_summary_from(tc.fetched_sections, tc.fetched_graphics)
    patient_block = tc.patient_profile.to_system_block() if tc.patient_profile else ""

    parts = [
        "<retrieved_evidence>",
        evidence_summary,
        "</retrieved_evidence>",
    ]
    if patient_block.strip():
        parts.extend(["", "<active_patient_profile>", patient_block.strip(), "</active_patient_profile>"])
    parts.extend([
        "",
        "<clinical_inquiry>",
        user_question.strip(),
        "</clinical_inquiry>",
        "Start Line 1 immediately with your `**1–2 sentence bold direct clinical conclusion.**` followed by `---` and `###` sections as needed.",
    ])
    return "\n".join(parts)


def _profile_block(patient_context: str) -> str:
    return f"""## Active Patient Profile
{patient_context}

Adjust every recommendation for this patient's age, organ function, active medications, and documented allergies."""


def extract_patient_context(patient_profile: Optional[dict] = None) -> Optional[str]:
    """Format a patient profile dict into a system prompt context string."""
    if not patient_profile:
        return None

    lines = []
    if patient_profile.get("age"):
        lines.append(f"- Age: {patient_profile['age']}")
    sex = patient_profile.get("sex") or patient_profile.get("gender")
    if sex:
        lines.append(f"- Sex: {sex}")
    if patient_profile.get("conditions"):
        conds = patient_profile["conditions"]
        lines.append(f"- Conditions: {', '.join(conds) if isinstance(conds, list) else conds}")
    if patient_profile.get("medications"):
        meds = patient_profile["medications"]
        lines.append(f"- Medications: {', '.join(meds) if isinstance(meds, list) else meds}")
    if patient_profile.get("allergies"):
        allergies = patient_profile["allergies"]
        lines.append(f"- Allergies: {', '.join(allergies) if isinstance(allergies, list) else allergies}")
    if patient_profile.get("notes"):
        lines.append(f"- Notes: {patient_profile['notes']}")

    return "\n".join(lines) if lines else None
