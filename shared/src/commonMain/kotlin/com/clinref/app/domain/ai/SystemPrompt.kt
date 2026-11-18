package com.clinref.app.domain.ai

object SystemPrompt {

    /**
     * The canonical abstention string when retrieved evidence does not answer the question.
     */
    const val CANONICAL_ABSTENTION: String = "Not specified in retrieved UpToDate sections."

    /**
     * Placeholder shown in the Stage-1 remediation template when nothing has been retrieved.
     */
    const val NOTHING_RETRIEVED_PLACEHOLDER = "Nothing has been retrieved yet."

    /**
     * Stage 1 System Prompt (Tools Bound):
     * Works identically for general reference queries, in-chat vignettes, and active patient profiles.
     */
    val RETRIEVAL_SYSTEM_PROMPT: String = """You are ClinRef AI in Retrieval Mode. Gather UpToDate evidence using tools to answer the clinician's inquiry. Do not draft a clinical answer—once sufficient evidence is loaded, stop calling tools and reply "Evidence retrieved."

### Retrieval Rules
1. **Search (`searchTopics`)**: Query 1–3 plain clinical keywords (one condition, syndrome, or generic drug per call; translate clinical vignettes into canonical diagnoses; never pass demographics, vitals, or sentences). Use parallel calls for multiple drugs/conditions. If empty, retry with `refine_with`.
2. **Inspect (`getTopicOverview`)**:
   - `calc` (Calculator): Returns full content automatically—stop retrieval.
   - `drug` (Lexidrug Monograph): Fetch whenever specific dosing, renal/hepatic cutoffs, contraindications, interactions, or `requiresDrugMonograph`/`needsDrugMonograph` apply.
3. **Fetch Content**:
   - If `"autoPrefetched": true` is in `getTopicOverview`, those `recommendedSectionIds` and `recommendedGraphicIds` are already loaded—only fetch missing sections or a needed `drug` monograph.
   - Otherwise, call `getTopicSectionsText(topicId, sectionIds)` (batch up to 20 IDs; parent sections include subsections; include organ-adjustment or allergy-alternative sections if patient factors are present) and `getGraphicContent(graphicId)` for tables (`isTable: true`)."""

    /** Kept for backwards compatibility with tests and call sites. */
    val STATIC_CLINICAL_GUIDANCE: String get() = RETRIEVAL_SYSTEM_PROMPT

    /**
     * Stage 2 System Prompt (Closed-Book, Zero Tools):
     * Adapts automatically whether an active patient profile is set, a vignette is in the inquiry, or the query is general.
     */
    val SYNTHESIS_SYSTEM_PROMPT: String = """You are ClinRef AI, a point-of-care clinical reference for physicians. Answer the inquiry using ONLY `<retrieved_evidence>`.

### Clinical Rules
1. **Source-Only**: State only facts, doses, thresholds, and regimens present in `<retrieved_evidence>`. If the evidence does not answer the question, state that plainly—never fill gaps from memory.
2. **Patient Tailoring (When Applicable)**: If `<active_patient_profile>` is provided or the inquiry mentions patient factors (age, weight, renal/hepatic function, medications, allergies), tailor the regimen, dose adjustment, or contraindication alternative accordingly (for `mg/kg` doses, show both the `mg/kg` formula and the calculated dose when weight is known). If no patient factors are specified, present the standard recommendation from `<retrieved_evidence>` without mentioning that a patient profile is absent.
3. **Recommendation Strength**: Preserve UpToDate's GRADE distinctions ("recommend" for strong vs. "suggest" for conditional).

### Output Format
**[1–2 sentence direct clinical conclusion stating the actionable regimen, dose, or decision]**
---
### [Concise Subheading]
- [Supporting details, adjustments, or monitoring from evidence]

(Omit `---` and `###` for simple single-fact answers or when evidence is absent. Include only subheadings supported by the evidence, and do not output a references list.)"""

    private fun templateLines(vararg lines: String): String = lines.joinToString("\n")

    /**
     * Stage 1 remediation: the turn ended without section text or table content.
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
            "1. If topics, section ids, or table graphics are listed above, do not search for those same topics again: if the question asks for a clinical table listed under graphics, call getGraphicContent(graphicId) first; if section ids are listed for the needed topic, call getTopicSectionsText(topicId, sectionIds) with ids copied exactly from the list; if a multi-drug question requires a second drug not yet listed above, either call getTopicSectionsText(topicId, sectionIds) on the listed topic or call searchTopics(query) with ONLY the single missing drug name.",
            "2. If nothing relevant is listed, translate the clinical question or patient vignette into a 1-3 word condition or generic drug name (no age, sex, vitals like \"BP\", or symptom adjectives like \"tearing\") and call searchTopics(query), then getTopicOverview(topicId), then getTopicSectionsText(topicId, sectionIds).",
            "3. Reply with text only after the section text is in context."
        )
    }

    /**
     * The dynamic patient profile block, kept separate from the static guidance so
     * provider-side prompt caches are not invalidated per consultation.
     */
    fun patientContextBlock(patientProfile: PatientProfile): String = patientProfile.toSystemBlock()

    /**
     * Formats fetched section content into an evidence digest string.
     */
    fun evidenceSummaryFrom(sections: List<FetchedSection>): String =
        evidenceSummaryFrom(sections, emptyList())

    /**
     * Formats fetched section text and graphic table markdown into an evidence digest string.
     */
    fun evidenceSummaryFrom(
        sections: List<FetchedSection>,
        graphics: List<FetchedGraphic>
    ): String {
        val parts = mutableListOf<String>()
        sections.forEach { sec ->
            if (sec.contentSnippet.isNotBlank()) {
                parts.add("Section [${sec.sectionId}] (${sec.sectionTitle}):\n${sec.contentSnippet}")
            } else {
                parts.add("Section [${sec.sectionId}] (${sec.sectionTitle}) from topic ${sec.topicTitle}")
            }
        }
        graphics.forEach { gfx ->
            if (gfx.markdown.isNotBlank()) {
                val title = gfx.title.ifBlank { "Clinical Table" }
                if (gfx.markdown.startsWith("### Graphic Table:")) {
                    parts.add("Graphic [${gfx.graphicId}]:\n${gfx.markdown}")
                } else {
                    parts.add("Graphic [${gfx.graphicId}] ($title):\n${gfx.markdown}")
                }
            }
        }
        return if (parts.isEmpty()) {
            "No section text was successfully fetched in the prior turn."
        } else {
            parts.joinToString("\n---\n")
        }
    }

    /**
     * Formats the Stage-2 synthesis directive for closed-book answering without tools.
     */
    fun formatSynthesisPrompt(
        pendingInput: String,
        userQuestion: String,
        turnContext: TurnContext,
        patientProfile: PatientProfile? = null
    ): String {
        if (pendingInput.startsWith("SYSTEM NOTICE")) {
            return pendingInput
        }

        val evidenceSummary = evidenceSummaryFrom(turnContext.fetchedSections, turnContext.fetchedGraphics)
        val question = if (userQuestion.isNotBlank()) userQuestion else pendingInput
        val profileBlock = (patientProfile ?: turnContext.patientProfile)?.toSystemBlock()?.trim().orEmpty()

        return buildString {
            appendLine("CLINICAL SYNTHESIS DIRECTIVE (CLOSED-BOOK — NO TOOLS ACTIVE)")
            appendLine()
            appendLine("<retrieved_evidence>")
            appendLine(evidenceSummary)
            appendLine("</retrieved_evidence>")
            if (profileBlock.isNotBlank()) {
                appendLine()
                appendLine("<active_patient_profile>")
                appendLine(profileBlock)
                appendLine("</active_patient_profile>")
            }
            appendLine()
            appendLine("CLINICAL INQUIRY:")
            append(question)
        }
    }
}
