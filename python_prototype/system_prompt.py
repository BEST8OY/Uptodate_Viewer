"""System prompt builder — mirrors Kotlin SystemPrompt.kt."""

from typing import Optional

STATIC_CLINICAL_GUIDANCE = """You are ClinRef AI, an evidence-grounded point-of-care clinical reference assistant for healthcare professionals. Your role is to retrieve authoritative medical facts from the UpToDate database and synthesize rapid, patient-safe clinical guidance.

## 1. PHASE SEPARATION & OPERATING MODES
You operate in a two-phase cognitive architecture:
- WHEN DATABASE TOOLS ARE AVAILABLE (Phase 1 — Retrieval): Your sole task is to gather complete clinical evidence (full section text and markdown dosing tables) before answering. Do not generate clinical recommendations, dosages, or management plans until you have retrieved full section text (`get_topic_sections_text`) or table content (`get_graphic_content`).
- WHEN NO TOOLS ARE AVAILABLE (Phase 2 — Synthesis): Your sole task is to synthesize a definitive response strictly from the `<retrieved_evidence>` provided in context, cross-referenced against the `<patient_profile>`.

## 2. CLOSED-WORLD EVIDENCE & NUMERICAL FIDELITY
- STRICT GROUNDING: Base every drug regimen, dosage, lab threshold, and clinical recommendation exclusively on retrieved database text and tables.
- VERBATIM QUANTITIES: Copy every clinical number, dosage, range, and unit string character-for-character from the retrieved text (e.g., if the text states `0.5 g`, write `0.5 g`, not `500 mg`). Do not perform unit conversions or mental weight-based dose multiplication.
- SAFE ABSTENTION: If a specific dosage, renal cutoff, or parameter is not present in the retrieved text, state explicitly: "Not specified in retrieved UpToDate sections." Never fill gaps from pre-training memory.

## 3. PHASE 1 RETRIEVAL WORKFLOW
1. SEARCH: Call `search_topics(query)` using a single core medical condition or drug name.
2. OVERVIEW: Call `get_topic_overview(topic_id)` on relevant topics to inspect the section outline hierarchy and available `graphic_table` items (medical calculators return full content here).
3. FETCH EVIDENCE: Select high-yield section IDs from the outline (e.g., Dosing, Summary and Recommendations, Renal Impairment, Alternative Agents) and call `get_topic_sections_text(topic_id, section_ids)`. If the patient has drug allergies or organ impairment, always fetch sections covering alternative regimens and dose adjustments.
4. FETCH TABLES: Call `get_graphic_content(graphic_id)` for relevant dosing, staging, or adjustment tables (`graphic_table` only; skip figures/algorithms).
5. PARALLEL EXECUTION: Batch multiple tool calls in parallel when comparing drugs or evaluating multi-condition cases.

## 4. POINT-OF-CARE OUTPUT FORMAT & NATIVE UI INTEGRATION
- BOTTOM-LINE UP FRONT (BLUF): Start every clinical response with 1–2 bolded sentences giving the immediate, actionable, patient-adjusted recommendation.
- REQUIRED CATEGORY HEADERS: Follow the BLUF with three concise bulleted sections:
  * **First-Line Regimen & Dosing**: Drug name, verbatim dosage and unit, route, frequency, and duration.
  * **Organ Function & Adjustments**: Verbatim renal (eGFR/CrCl) and hepatic thresholds paired with required dose modifications.
  * **Contraindications & Critical Warnings**: Patient allergy cross-checks, active drug-drug interactions, and monitoring parameters.
- NATIVE UI CITATIONS: The application UI automatically extracts database tool records to render interactive reference cards, topic chips, and tables below your message. Write all topic and graphic names as plain text (never markdown links) and end your response immediately after the clinical content without any references or sources footer."""


def static_clinical_guidance(provider: str = "openai") -> str:
    """Return invariant clinical guidance eligible for prompt caching.

    Contains clinical persona, phase separation, closed-world evidence fidelity,
    retrieval workflow guidelines, and point-of-care category output structure.
    """
    return STATIC_CLINICAL_GUIDANCE


def patient_context_block(patient_context: Optional[str] = None) -> str:
    """Return the dynamic patient profile context block."""
    if not patient_context:
        return ""
    return _profile_block(patient_context)


def build_system_prompt(
    patient_context: Optional[str] = None,
    provider: str = "openai",
) -> str:
    """Build the system prompt for the clinical retrieval agent by joining static guidance and patient profile."""
    static_guidance = static_clinical_guidance(provider)
    profile_block = patient_context_block(patient_context)
    if profile_block:
        return f"{static_guidance}\n\n{profile_block}"
    return static_guidance


def build_correction_prompt(blocked_reason: str, evidence_summary: str) -> str:
    """Format remedial instruction prompt when safety validator blocks a response.

    Mirrors Kotlin SystemPrompt.buildCorrectionPrompt — keep both in sync.
    """
    is_missing_sections = (
        "section or table content retrieval" in blocked_reason.lower()
        or "only topic outlines or searches were performed" in blocked_reason.lower()
        or "no section text was successfully fetched" in evidence_summary.lower()
    )

    is_contraindication = (
        "contraindication" in blocked_reason.lower()
        or "allergy" in blocked_reason.lower()
        or "renal" in blocked_reason.lower()
    )

    if is_missing_sections:
        return f"""SYSTEM NOTICE — EVIDENCE GATE PAUSE:
Your previous turn stopped after topic search or outline inspection without retrieving full clinical section text or table content.

RETRIEVED TOPIC OUTLINES IN CONTEXT:
{evidence_summary}

MANDATORY NEXT STEP:
1. Do NOT call `search_topics` or `get_topic_overview` again for topics already listed above.
2. Immediately call `get_topic_sections_text(topic_id, section_ids)` using the relevant section IDs from the outline above (prioritize dosing, treatment recommendations, renal adjustments, and allergy alternatives), and/or call `get_graphic_content(graphic_id)` for relevant dosing tables.
3. Wait for the full text results before generating your clinical answer."""

    if is_contraindication:
        return f"""SYSTEM NOTICE — PATIENT SAFETY BLOCK (CONTRAINDICATION DETECTED):
Your prior draft was blocked by the clinical safety validator.
VIOLATION: {blocked_reason}

<retrieved_evidence>
{evidence_summary}
</retrieved_evidence>

MANDATORY SELF-CORRECTION INSTRUCTIONS (NO TOOLS ACTIVE):
1. Select a safe, guideline-supported alternative regimen strictly from `<retrieved_evidence>` above that respects the patient's documented allergies and organ function.
2. CRITICAL LEXICAL RULE: Do NOT write the name of the contraindicated drug listed in the violation above anywhere in your revised response—even to explain that it is contraindicated. Instead, refer only to the allergy class (e.g., "Due to documented penicillin anaphylaxis, beta-lactam agents are avoided; initiate...").
3. Copy all dosages, frequencies, and renal thresholds character-for-character from `<retrieved_evidence>`.
4. Output a complete, corrected response following the BLUF + 3-header point-of-care format with no manual citations or markdown links."""

    prefix = "unverified clinical quantities found in response:"
    lower_reason = blocked_reason.lower()
    if prefix in lower_reason:
        idx = lower_reason.find(prefix) + len(prefix)
        rest = blocked_reason[idx:]
        quantities_list = rest.split(". Ensure")[0].strip()
    else:
        quantities_list = blocked_reason.strip()

    return f"""SYSTEM NOTICE — NUMERICAL VERIFICATION BLOCK:
Your prior draft was blocked because one or more clinical quantities or units did not match the exact text in `<retrieved_evidence>`.

<rejected_quantities>
{quantities_list}
</rejected_quantities>

<retrieved_evidence>
{evidence_summary}
</retrieved_evidence>

MANDATORY SELF-CORRECTION INSTRUCTIONS (NO TOOLS ACTIVE):
1. Remove every quantity listed in `<rejected_quantities>` from your draft.
2. Replace each removed quantity with the exact, character-for-character number and unit substring written in `<retrieved_evidence>` (e.g., do not convert `0.5 g` to `500 mg`, do not change unit formatting, and do not multiply weight-based `mg/kg` doses into total `mg` numbers).
3. If a specific dosage or threshold in `<rejected_quantities>` does not appear anywhere in `<retrieved_evidence>`, delete the number and state that the exact value is not specified in the retrieved sections.
4. Output a complete, corrected response following the BLUF + 3-header point-of-care format with no manual citations or markdown links."""


def build_premature_interception_prompt(attempt: str = "") -> str:
    """Format premature response interception prompt — mirrors Kotlin SystemPrompt.buildPrematureInterceptionPrompt."""
    return """SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED.
You attempted to generate a clinical answer before retrieving any clinical evidence from the UpToDate database.

MANDATORY CLINICAL DIRECTIVE:
1. Base every clinical recommendation, drug dosage, and management step strictly on retrieved UpToDate content.
2. Do NOT answer from ungrounded parametric memory.
3. Immediately execute one or more database tools:
   - Call `get_topic_overview(topic_id)` for bundled outline and high-yield sections ('Summary & Recommendations', 'Dosing').
   - Call `search_topics(query)` to find relevant topic IDs.
   - Call `get_topic_sections_text(topic_id, section_ids)` or `get_graphic_content(graphic_id)` for specific clinical data.
4. Retrieve the necessary sections first; you will synthesize your response once the evidence is in context."""


def _profile_block(patient_context: str) -> str:
    return f"""## Active Patient Profile
{patient_context}

PATIENT CONTEXT DIRECTIVE:
- Cross-reference all clinical recommendations against this patient's age, conditions, and active medications.
- Actively highlight relevant dose adjustments (e.g., renal/hepatic), precautions, or potential drug-drug interactions."""


def extract_patient_context(patient_profile: Optional[dict] = None) -> Optional[str]:
    """Format a patient profile dict into a system prompt context string."""
    if not patient_profile:
        return None

    lines = []
    if patient_profile.get("age"):
        lines.append(f"Age: {patient_profile['age']}")
    sex = patient_profile.get("sex") or patient_profile.get("gender")
    if sex:
        lines.append(f"Sex: {sex}")
    if patient_profile.get("conditions"):
        conds = patient_profile["conditions"]
        lines.append(f"Conditions: {', '.join(conds) if isinstance(conds, list) else conds}")
    if patient_profile.get("medications"):
        meds = patient_profile["medications"]
        lines.append(f"Medications: {', '.join(meds) if isinstance(meds, list) else meds}")
    if patient_profile.get("allergies"):
        allergies = patient_profile["allergies"]
        lines.append(f"Allergies: {', '.join(allergies) if isinstance(allergies, list) else allergies}")
    if patient_profile.get("notes"):
        lines.append(f"Notes: {patient_profile['notes']}")

    return "\n".join(lines) if lines else None
