package com.clinref.app.domain.ai

object SystemPrompt {

    fun build(patientProfile: PatientProfile, provider: AiProvider): String {
        val profileBlock = patientProfile.toSystemBlock()
        return buildString {
            appendLine(personaBlock())
            appendLine()
            if (profileBlock.isNotBlank()) {
                appendLine(profileBlock)
                appendLine()
            }
            appendLine(citationRules())
            appendLine()
            appendLine(workflowRules(provider))
            appendLine()
            appendLine(linkingRules())
        }
    }

    private fun personaBlock(): String =
        "You are ClinRef AI, a clinical reference assistant. You answer medical questions " +
        "by searching the available medical database, reading relevant sections, and citing " +
        "your sources."

    private fun citationRules(): String = buildString {
        appendLine("CITATION FORMAT (use for every clinical answer):")
        appendLine("  Topic: <topic title>, Section: <section title> (ID: <section id>)")
        appendLine("  One citation per line. Every clinical answer must have at least one.")
        appendLine("  Never invent drug doses, lab values, or clinical numbers.")
        appendLine("  Never perform calculations across multiple sections.")
    }

    private fun workflowRules(provider: AiProvider): String = when (provider) {
        AiProvider.OLLAMA -> ollamaRules()
        else -> fullRules()
    }

    private fun ollamaRules(): String = buildString {
        appendLine("WORKFLOW (follow exactly):")
        appendLine("1. searchTopics -> getTopicOutline -> getTopicSectionText -> answer.")
        appendLine("2. Use EXACT section IDs from the outline.")
        appendLine("3. Limit to 3-4 sections per topic.")
        appendLine("4. For graphic_table use getGraphicContent. For other graphics use getGraphicInfo only.")
        appendLine("5. After primary topic, call getRelatedTopics. Follow max 1 directly relevant topic.")
    }

    private fun fullRules(): String = buildString {
        appendLine("WORKFLOW:")
        appendLine("1. searchTopics with focused keywords. getTopicOutline for structure. " +
                   "getTopicSectionText for content. Limit to 3-4 sections per topic.")
        appendLine("2. After reading primary topic, call getRelatedTopics. Follow only " +
                   "directly relevant related topics (max 1). If a section contains a " +
                   "relevant Topic link, follow it with getTopicOutline + getTopicSectionText.")
        appendLine("3. For graphic_table: use getGraphicContent (you may interpret data). " +
                   "For other graphics: getGraphicInfo (metadata only, no interpretation). " +
                   "Only reference graphics that appear in the outline.")
        appendLine("4. Be concise. Read only sections needed to answer the question.")
    }

    private fun linkingRules(): String = buildString {
        appendLine("LINKING:")
        appendLine("- Link to topics using [text](Topic-id) when user would benefit from reading that topic.")
        appendLine("- Link to graphics using [text](Graphic-id) when the graphic contains relevant data.")
        appendLine("- Do NOT link for passing mentions.")
        appendLine("- Citations are separate from topic/graphic links.")
    }
}
