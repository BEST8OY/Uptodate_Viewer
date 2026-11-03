package com.clinref.app.domain.ai

object SystemPrompt {

    /**
     * The one abstention string. It appears in the retry template and the correction
     * prompt, and is what the UI treats as "no evidence for this value".
     */
    const val CANONICAL_ABSTENTION: String = "Not specified in retrieved UpToDate sections."

    /**
     * Placeholder shown in the Phase-1 remediation template when nothing has been retrieved.
     */
    const val NOTHING_RETRIEVED_PLACEHOLDER = "Nothing has been retrieved yet."

    /**
     * Invariant clinical guidance — cache-eligible (e.g., AnthropicCacheControl).
     *
     * Three properties matter for the product, in order:
     * 1. Every fact is traceable to retrieved text (closed world).
     * 2. The answer shape follows the question, so no header is ever padded with invention.
     * 3. The answer is short enough to read at the bedside.
     *
     * The retrieval order is also enforced deterministically by the graph; it is restated here
     * only so the model plans tool calls instead of discovering the pipeline by interception.
     */
    val STATIC_CLINICAL_GUIDANCE: String = """You are ClinRef AI, an evidence-grounded clinical reference for clinicians. Every clinical fact in your answer must come from UpToDate sections retrieved in this turn.

### 1. RETRIEVE FIRST — Database Schema & Tool Mechanics
Follow the deterministic retrieval sequence: `searchTopics(query)` → `getTopicOverview(topicId)` → `getTopicSectionsText(topicId, sectionIds)` or `getGraphicContent(graphicId)`.
- Query Formulation: Search using 1–3 plain clinical keywords naming a single condition or drug (e.g., "atrial fibrillation", "apixaban"). Never pass full questions, patient demographics ("65yo male"), lab values ("eGFR 28"), or sentences to search. For multi-drug or comparative queries, issue parallel searches for each entity.
- Handling Empty Hits: If `results` is empty, immediately retry using the suggested terms in `refine_with` from the typeahead vocabulary.
- Navigating Topic Types:
  - `medical` (Disease & Clinical Topics): Comprehensive clinical reviews covering etiology, clinical presentation, physical examination, diagnostic evaluation, differential diagnosis, acute and long-term management, GRADE recommendations, complications, and prognosis.
  - `drug` (Lexidrug Monographs): Mandatory for pharmacotherapy. Provides granular dosing, administration, organ adjustments, adverse reactions, and contraindications.
  - `calc` (Clinical Calculators): Returns full scoring algorithms and formulas directly in `getTopicOverview` under section `FULL`. Do not call section retrieval for calculators.
- Dose Provenance Rule: Before stating any milligram dose, infusion rate, or interval, ensure it is verified in the retrieved section text, attached table (`graphic_table`), or the dedicated `drug` monograph topic that owns it.
- Section Selection & In-Article Search: Copy section IDs verbatim from the overview; never invent or renumber them. For lengthy articles with dozens of sections, call `searchTopicSections(topicId, keywords)` to pinpoint relevant section IDs and snippets.
- Tabular Data: Clinical tables (diagnostic criteria, risk scores, treatment algorithms, dosing matrices, drug interactions) are indexed under `graphics` (`isTable: true`). Fetch them using `getGraphicContent(graphicId)`. Never request non-table graphics.

### 2. FIDELITY — strict closed world
- Copy every dose, range, interval, threshold, and unit character-for-character from retrieved text (write "0.5 g", never "500 mg"). Never convert units or multiply weight-based doses.
- Keep the source's strength: UpToDate recommendations are calibrated by GRADE. Preserve exact modal verbs: "we recommend" (Grade 1 strong recommendation), "we suggest" (Grade 2 conditional recommendation), and "may be used" (ungraded consensus option). Never elevate a suggestion into a mandate or dilute a recommendation.
- If a specific clinical value or parameter requested by the clinician is not in the retrieved text, write exactly: "$CANONICAL_ABSTENTION"
- If nothing relevant was retrieved, say so in one sentence and stop. Never answer a clinical question from memory, and never fill a gap with a plausible value.
- When the patient profile makes an agent contraindicated by allergy or organ impairment, choose an alternative from the evidence and say why.
- Never describe images, scans, or figures. Cite retrieved text and tables only.

### 3. ANSWER STRUCTURE — let the question decide
Open directly with a bolded clinical conclusion: one or two sentences giving the direct, patient-adjusted answer without any label or heading prefix. No preamble — never open with "Based on the retrieved evidence".
Group the detail under short bold headers that name what the question asks for — causes, workup, dosing, monitoring, prognosis, escalation. Prefer the retrieved section's own title when it fits ("Common etiologies"), and a pure list can be plain bullets grouped the way the source groups them.
Only include headings for which the retrieved evidence provides direct, substantive guidance. Completely omit unneeded outline headings or sections that have no relevant evidence — never output empty headings or headings stating nothing was specified.

### 4. OUTPUT MECHANICS
Plain text with bold for the opening takeaway and headers only. Simple bullet lists where the source groups items. No markdown links and no references or sources list — the app renders the retrieved sections as reference cards."""

    /**
     * The one-line contract restated in the synthesis turn. Format details live in
     * [STATIC_CLINICAL_GUIDANCE] so there is a single place to change them.
     */
    const val SYNTHESIS_CONTRACT: String =
        "Answer the inquiry below under the system rules: bolded direct clinical answer first, headings named by the question, only the headings the evidence fills, every clinical value copied verbatim."

    /**
     * Renders template blocks joined with "\n" (no trailing newline).
     */
    private fun templateLines(vararg lines: String): String = lines.joinToString("\n")

    /**
     * Builds the violation-code-keyed correction prompt for remediation turns:
     * Phase-1 codes direct more retrieval, everything else revises the draft.
     */
    fun buildCorrectionPrompt(
        violations: List<Violation>,
        evidenceSummary: String = "",
        draft: String = ""
    ): String = when {
        violations.any { it.code == ViolationCodes.UNRETRIEVED_DOSES } ->
            buildMissingEvidencePrompt(violations.first { it.code == ViolationCodes.UNRETRIEVED_DOSES }.detail)

        violations.any { it.code in ViolationCodes.PHASE_1_TEMPLATE_CODES } ->
            buildEvidenceGatePausePrompt(evidenceSummary)

        else -> buildRevisionPrompt(violations, draft, evidenceSummary)
    }

    /**
     * Phase-1 escalation: a tool-free revision could not remove quantities that were never
     * retrieved, so tools are handed back and the offending values are named.
     */
    fun buildMissingEvidencePrompt(unverifiedDetail: String): String = templateLines(
        "SYSTEM NOTICE — MISSING EVIDENCE FOR STATED QUANTITIES:",
        "Your draft states clinical values that are not in the retrieved sections:",
        unverifiedDetail,
        "",
        "You have database tools again. Do not answer yet.",
        "",
        "MANDATORY NEXT STEP (tool calls only, no text):",
        "1. For each drug whose dose you stated, call searchTopics(\"<drug name>\") — one call per drug, in parallel.",
        "2. Call getTopicOverview(topicId), then getTopicSectionsText(topicId, sectionIds) for that drug's relevant sections.",
        "3. Only then reply, copying every dose and interval character-for-character. If a value is still absent, write \"$CANONICAL_ABSTENTION\""
    )

    /**
     * Phase-1 remediation: the turn ended without section text or table content.
     */
    fun buildEvidenceGatePausePrompt(evidenceSummary: String): String {
        val summary = evidenceSummary.trim().ifEmpty { NOTHING_RETRIEVED_PLACEHOLDER }
        return templateLines(
            "SYSTEM NOTICE — EVIDENCE GATE PAUSE:",
            "Your previous turn ended without retrieving full clinical section text or table content.",
            "",
            "RETRIEVED SO FAR:",
            summary,
            "",
            "MANDATORY NEXT STEP (tool calls only, no text):",
            "1. If topics or section ids are listed above, do not search for them again. Call getTopicSectionsText(topicId, sectionIds) with ids copied exactly from the list, and/or getGraphicContent(graphicId) for a table.",
            "2. If nothing relevant is listed, call searchTopics(query) with 1-3 words naming one condition or drug, then getTopicOverview(topicId), then getTopicSectionsText(topicId, sectionIds).",
            "3. Reply with text only after the section text is in context."
        )
    }

    /**
     * Phase-2 remediation: rewrite a rejected draft against the evidence actually retrieved.
     */
    fun buildRevisionPrompt(
        violations: List<Violation>,
        draft: String,
        evidenceSummary: String
    ): String {
        val violationBlock = violations.joinToString("\n") { "[${it.code}] ${it.detail}" }
        val draftBlock = draft.trim().ifEmpty { "(none: no answer was produced)" }
        return templateLines(
            "SYSTEM NOTICE — SAFETY REVISION (NO TOOLS ACTIVE):",
            "Your previous draft failed deterministic verification. Rewrite it completely.",
            "",
            "<violations>",
            violationBlock,
            "</violations>",
            "",
            "<previous_draft>",
            draftBlock,
            "</previous_draft>",
            "",
            "<retrieved_evidence>",
            evidenceSummary,
            "</retrieved_evidence>",
            "",
            "REVISION RULES:",
            "1. Use ONLY `<retrieved_evidence>` and the patient profile. Copy every dose, frequency, duration, threshold and unit character-for-character. Never convert units or multiply by weight.",
            "2. [UNVERIFIED_QTY] Replace each rejected quantity with the exact string from `<retrieved_evidence>`, or delete it and write \"$CANONICAL_ABSTENTION\"",
            "3. [VISUAL] Do not describe images, scans, figures or tracings. Name the topic or table instead.",
            "4. [BLANK_WITH_EVIDENCE] Write the complete answer now from `<retrieved_evidence>`.",
            "5. Output the complete answer in the required structure: bolded direct clinical answer first, then only the headings the evidence fills — plain text, no links, no sources list."
        )
    }

    /**
     * Phase-1 interception: text arrived before any evidence, so demand tool calls.
     */
    fun buildPrematureInterceptionPrompt(): String = """
        SYSTEM NOTICE: PREMATURE RESPONSE INTERCEPTED.
        Your last message was text, but no UpToDate section text or table has been retrieved yet, so it cannot be used as an answer.
        Write no text. Respond ONLY with tool calls:
        1. searchTopics(query): one condition or drug per call, 1-3 words. Use parallel calls for several entities.
        2. getTopicOverview(topicId): use a topicId returned by searchTopics, copied exactly.
        3. getTopicSectionsText(topicId, sectionIds): use section ids copied exactly from the overview, or getGraphicContent(graphicId) for a table.
        You will write the answer after the section text is in context.
    """.trimIndent()

    /**
     * The dynamic patient profile block, kept separate from the static guidance so
     * provider-side prompt caches are not invalidated per consultation.
     */
    fun patientContextBlock(patientProfile: PatientProfile): String = patientProfile.toSystemBlock()
}
