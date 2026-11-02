"""System prompt builder — mirrors Kotlin SystemPrompt.kt.

The guidance text, the correction templates, and the abstention string are identical
to the Kotlin implementation; `test_cross_stack_scenarios.py` pins the shared parts.
"""

from typing import Optional

CANONICAL_ABSTENTION = "Not specified in retrieved UpToDate sections."

NOTHING_RETRIEVED_PLACEHOLDER = "Nothing has been retrieved yet."

STATIC_CLINICAL_GUIDANCE = f"""You are ClinRef AI, an evidence-grounded clinical reference for clinicians. Every clinical fact in your answer must come from UpToDate sections retrieved in this turn.

### 1. RETRIEVE FIRST — Database Schema & Tool Mechanics
Follow the deterministic retrieval sequence: `searchTopics(query)` → `getTopicOverview(topicId)` → `getTopicSectionsText(topicId, sectionIds)` or `getGraphicContent(graphicId)`.
- Query Formulation: Search using 1–3 plain clinical keywords naming a single condition or drug (e.g., "atrial fibrillation", "apixaban"). Never pass full questions, patient demographics ("65yo male"), lab values ("eGFR 28"), or sentences to search. For multi-drug or comparative queries, issue parallel searches for each entity.
- Handling Empty Hits: If `results` is empty, immediately retry using the suggested terms in `refine_with` from the typeahead vocabulary.
- Navigating Topic Types:
  - `medical` (Disease Topics): Provides pathophysiology, diagnostic criteria, and broad management strategies. Rarely contains granular numeric doses or renal titration schedules.
  - `drug` (Lexidrug Monographs): Mandatory for pharmacotherapy. Retrieve sections: `Dosing: Adult`, `Dosing: Pediatric`, `Dosing: Kidney Impairment`, `Adverse Reactions`, or `Contraindications`.
  - `calc` (Clinical Calculators): Returns full scoring algorithms and formulas directly in `getTopicOverview` under section `FULL`. Do not call section retrieval for calculators.
- Dose Provenance Rule: A management or disease topic rarely contains numeric doses. Before stating any milligram dose, infusion rate, or interval, retrieve the dedicated `drug` monograph topic that owns it.
- Section Selection & In-Article Search: Copy section IDs verbatim from the overview; never invent or renumber them. For lengthy articles with dozens of sections, call `searchTopicSections(topicId, keywords)` to pinpoint relevant section IDs and snippets.
- Tabular Data: Dosing matrices, risk stratification grids, and treatment algorithms are indexed under `graphics` (`isTable: true`). Fetch them using `getGraphicContent(graphicId)`. Never request non-table graphics.

### 2. FIDELITY — strict closed world
- Copy every dose, range, interval, threshold, and unit character-for-character from retrieved text (write "0.5 g", never "500 mg"). Never convert units or multiply weight-based doses.
- Keep the source's strength: UpToDate recommendations are calibrated by GRADE. Preserve exact modal verbs: "we recommend" (Grade 1 strong recommendation), "we suggest" (Grade 2 conditional recommendation), and "may be used" (ungraded consensus option). Never elevate a suggestion into a mandate or dilute a recommendation.
- If a specific clinical value or parameter requested by the clinician is not in the retrieved text, write exactly: "{CANONICAL_ABSTENTION}"
- If nothing relevant was retrieved, say so in one sentence and stop. Never answer a clinical question from memory, and never fill a gap with a plausible value.
- When the patient profile makes an agent contraindicated by allergy or organ impairment, choose an alternative from the evidence and say why.
- Never describe images, scans, or figures. Cite retrieved text and tables only.

### 3. ANSWER STRUCTURE — let the question decide
Open directly with a bolded clinical conclusion: one or two sentences giving the direct, patient-adjusted answer without any label or heading prefix. No preamble — never open with "Based on the retrieved evidence".
Group the detail under short bold headers that name what the question asks for — causes, workup, dosing, monitoring, prognosis, escalation. Prefer the retrieved section's own title when it fits ("Common etiologies"), and a pure list can be plain bullets grouped the way the source groups them.
Only include headings for which the retrieved evidence provides direct, substantive guidance. Completely omit unneeded outline headings or sections that have no relevant evidence — never output empty headings or headings stating nothing was specified.

### 4. OUTPUT MECHANICS
Plain text with bold for the opening takeaway and headers only. Simple bullet lists where the source groups items. No markdown links and no references or sources list — the app renders the retrieved sections as reference cards."""

# The one-line contract restated in the synthesis turn. Format details live in
# STATIC_CLINICAL_GUIDANCE so there is a single place to change them.
SYNTHESIS_CONTRACT = (
    "Answer the inquiry below under the system rules: bolded direct clinical answer first, headings "
    "named by the question, only the headings the evidence fills, every clinical value copied verbatim."
)

PHASE_1_TEMPLATE_CODES = {"NO_TOOLS", "NO_SECTIONS", "BLANK_NO_EVIDENCE"}


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
    """Phase-1 remediation: the turn ended without section text or table content.

    Tool-name literals are camelCase per the Kotlin registry (they are tool-name
    literals in the correction template, not this Python engine's snake_case registry).
    """
    summary = (evidence_summary or "").strip() or NOTHING_RETRIEVED_PLACEHOLDER
    return f"""SYSTEM NOTICE — EVIDENCE GATE PAUSE:
Your previous turn ended without retrieving full clinical section text or table content.

RETRIEVED SO FAR:
{summary}

MANDATORY NEXT STEP (tool calls only, no text):
1. If topics or section ids are listed above, do not search for them again. Call getTopicSectionsText(topicId, sectionIds) with ids copied exactly from the list (dosing, treatment recommendations, renal adjustment, alternatives), and/or getGraphicContent(graphicId) for a dosing table.
2. If nothing relevant is listed, call searchTopics(query) with 1-3 words naming one condition or drug, then getTopicOverview(topicId), then getTopicSectionsText(topicId, sectionIds).
3. Reply with text only after the section text is in context."""


def _violation_line(violation) -> str:
    if isinstance(violation, dict):
        code = violation.get("code", "")
        detail = violation.get("detail", "")
    else:
        code = violation.code
        detail = violation.detail
    return f"[{code}] {detail}"


def build_revision_prompt(violations, draft: str = "", evidence_summary: str = "") -> str:
    """Phase-2 remediation: rewrite a rejected draft against the retrieved evidence."""
    violation_block = "\n".join(_violation_line(v) for v in violations)
    draft_block = (draft or "").strip() or "(none: no answer was produced)"
    return f"""SYSTEM NOTICE — SAFETY REVISION (NO TOOLS ACTIVE):
Your previous draft failed deterministic verification. Rewrite it completely.

<violations>
{violation_block}
</violations>

<previous_draft>
{draft_block}
</previous_draft>

<retrieved_evidence>
{evidence_summary}
</retrieved_evidence>

REVISION RULES:
1. Use ONLY `<retrieved_evidence>` and the patient profile. Copy every dose, frequency, duration, threshold and unit character-for-character. Never convert units or multiply by weight.
2. [UNVERIFIED_QTY] Replace each rejected quantity with the exact string from `<retrieved_evidence>`, or delete it and write "{CANONICAL_ABSTENTION}"
3. [VISUAL] Do not describe images, scans, figures or tracings. Name the topic or table instead.
4. [BLANK_WITH_EVIDENCE] Write the complete answer now from `<retrieved_evidence>`.
5. Output the complete answer in the required structure: bolded direct clinical answer first, then only the headings the evidence fills — plain text, no links, no sources list."""


def build_missing_evidence_prompt(unverified_detail: str) -> str:
    """Phase-1 escalation: a tool-free revision could not remove never-retrieved values."""
    return f"""SYSTEM NOTICE — MISSING EVIDENCE FOR STATED QUANTITIES:
Your draft states clinical values that are not in the retrieved sections:
{unverified_detail}

You have database tools again. Do not answer yet.

MANDATORY NEXT STEP (tool calls only, no text):
1. For each drug whose dose you stated, call searchTopics("<drug name>") — one call per drug, in parallel.
2. Call getTopicOverview(topicId), then getTopicSectionsText(topicId, sectionIds) for that drug's Dosing sections.
3. Only then reply, copying every dose and interval character-for-character. If a value is still absent, write "{CANONICAL_ABSTENTION}\""""


def build_correction_prompt(
    violations: list,
    evidence_summary: str = "",
    draft: str = "",
) -> str:
    """Route by violation code: Phase-1 codes direct more retrieval, everything else revises."""

    def code_of(v):
        return v.get("code") if isinstance(v, dict) else getattr(v, "code", "")

    for v in violations:
        if code_of(v) == "UNRETRIEVED_DOSES":
            detail = v.get("detail", "") if isinstance(v, dict) else getattr(v, "detail", "")
            return build_missing_evidence_prompt(detail)

    if any(code_of(v) in PHASE_1_TEMPLATE_CODES for v in violations):
        return build_evidence_gate_pause_prompt(evidence_summary)
    return build_revision_prompt(violations, draft=draft, evidence_summary=evidence_summary)


def build_premature_interception_prompt() -> str:
    """Phase-1 interception: text arrived before any evidence, so demand tool calls."""
    return """SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED.
Your last message was text, but no UpToDate section text or table has been retrieved yet, so it cannot be used as an answer.
Write no text. Respond ONLY with tool calls:
1. search_topics(query): one condition or drug per call, 1-3 words. Use parallel calls for several entities.
2. get_topic_overview(topic_id): use a topic_id returned by search_topics, copied exactly.
3. get_topic_sections_text(topic_id, section_ids): use section ids copied exactly from the overview, or get_graphic_content(graphic_id) for a dosing table.
You will write the answer after the section text is in context."""


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
