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
   - Standard topics return section/table titles only (no clinical text)—never stop after `getTopicOverview` unless `topicType` is `"calc"` (which returns full calculator content automatically) or `"autoPrefetched": true` already loaded all needed sections.
   - Clinical topics and their tables (`isTable: true`) often include indication-specific dosing. Also inspect a `drug` (Lexidrug Monograph) when the inquiry is drug-specific, when `requiresDrugMonograph`/`needsDrugMonograph` is true, or when the clinical topic lacks needed renal/hepatic cutoffs, contraindications, or drug interactions.
3. **Fetch Content**:
   - If `"autoPrefetched": true` is in `getTopicOverview`, those `recommendedSectionIds` and `recommendedGraphicIds` are already loaded—only fetch missing sections or a needed `drug` monograph.
   - Otherwise, call `getTopicSectionsText(topicId, sectionIds)` (batch up to 20 IDs; parent sections include subsections; include organ-adjustment or allergy-alternative sections if patient factors are present) and `getGraphicContent(graphicId)` for relevant tables (`isTable: true`)."""

    /** Kept for backwards compatibility with tests and call sites. */
    val STATIC_CLINICAL_GUIDANCE: String get() = RETRIEVAL_SYSTEM_PROMPT

    /**
     * Stage 2 System Prompt (Closed-Book, Zero Tools):
     * Universal point-of-care synthesis across diagnosis, workup, calculators, management, and dosing.
     */
    val SYNTHESIS_SYSTEM_PROMPT: String = """You are ClinRef AI, a point-of-care clinical reference for physicians. Answer the inquiry using ONLY `<retrieved_evidence>`.

### 1. Clinical Rules
- **Source-Only**: State only facts, criteria, workups, regimens, doses, and thresholds present in `<retrieved_evidence>`—never fill gaps from memory. If `<retrieved_evidence>` contains no relevant information for the inquiry, output ONLY "$CANONICAL_ABSTENTION".
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
        // Stable-sort by relevance score descending so highest-scored evidence appears first while preserving document order for ties
        val sortedSections = sections.sortedByDescending { it.relevanceScore }
        val sortedGraphics = graphics.sortedByDescending { it.relevanceScore }

        sortedSections.forEach { sec ->
            // Suppress subsumed pointer stubs if content is already included in ancestor section above
            if (sec.contentSnippet.startsWith("(Content included in section [") &&
                sortedSections.any { !it.contentSnippet.startsWith("(Content included in section [") }
            ) {
                return@forEach
            }
            val topicAttr = sec.topicTitle.takeIf { it.isNotBlank() && it != sec.topicId }
                ?.let { " [Topic: $it]" }.orEmpty()
            val headerTitle = sec.sectionTitle.takeIf { it.isNotBlank() && it != sec.sectionId } ?: "Clinical Section"
            if (sec.contentSnippet.isNotBlank()) {
                parts.add("Section: $headerTitle$topicAttr:\n${sec.contentSnippet}")
            } else {
                val cleanTopicTitle = sec.topicTitle.takeIf { it.isNotBlank() && it != sec.topicId } ?: "Clinical Reference"
                parts.add("Section: $headerTitle from topic $cleanTopicTitle")
            }
        }
        sortedGraphics.forEach { gfx ->
            if (gfx.markdown.isNotBlank()) {
                val rawMarkdown = gfx.markdown.trim()
                val extractedTitle = if (rawMarkdown.startsWith("### Graphic Table:")) {
                    AiJsonUtils.GRAPHIC_TABLE_REGEX.find(rawMarkdown)?.groupValues?.get(1)?.trim().orEmpty()
                } else ""
                val title = gfx.title.takeIf { it.isNotBlank() && it != gfx.graphicId }
                    ?: extractedTitle.ifEmpty { "Clinical Table" }
                val topicName = gfx.topicTitle.takeIf { it.isNotBlank() && it != gfx.topicId }
                val topicAttr = topicName?.let { " [Topic: $it]" }.orEmpty()
                val cleanMarkdown = if (rawMarkdown.startsWith("### Graphic Table:")) {
                    rawMarkdown.substringAfter("\n").trimStart()
                } else {
                    rawMarkdown
                }
                parts.add("Table: $title$topicAttr:\n$cleanMarkdown")
            }
        }
        return if (parts.isEmpty()) {
            "No section text was successfully fetched in the prior turn."
        } else {
            parts.joinToString("\n---\n")
        }
    }

    /**
     * Stage 2 User Prompt: Wraps <retrieved_evidence>, <active_patient_profile>,
     * and the clinical inquiry at the final recency position.
     */
    fun formatSynthesisPrompt(
        userQuestion: String,
        turnContext: TurnContext
    ): String {
        val evidenceSummary = evidenceSummaryFrom(turnContext.fetchedSections, turnContext.fetchedGraphics)
        val patientBlock = turnContext.patientProfile?.toSystemBlock().orEmpty()

        return buildString {
            appendLine("<retrieved_evidence>")
            appendLine(evidenceSummary)
            appendLine("</retrieved_evidence>")
            if (patientBlock.isNotBlank()) {
                appendLine()
                appendLine("<active_patient_profile>")
                appendLine(patientBlock)
                appendLine("</active_patient_profile>")
            }
            appendLine()
            appendLine("<clinical_inquiry>")
            appendLine(userQuestion.trim())
            appendLine("</clinical_inquiry>")
            append("Start Line 1 immediately with your `**1–2 sentence bold direct clinical conclusion.**` followed by `---` and `###` sections as needed.")
        }
    }

    /**
     * Backwards-compatible overload for legacy call sites.
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
        val question = if (userQuestion.isNotBlank()) userQuestion else pendingInput
        val context = if (patientProfile != null && turnContext.patientProfile == null) {
            turnContext.copy(patientProfile = patientProfile)
        } else {
            turnContext
        }
        return formatSynthesisPrompt(question, context)
    }
}
