package com.clinref.app.domain.ai

import kotlinx.serialization.Serializable

/**
 * Record of an executed database tool call.
 */
@Serializable
data class ToolCallRecord(
    val toolName: String,
    val arguments: Map<String, String>,
    val result: String,
    val success: Boolean
)

/**
 * A retrieved section body snippet with topic and section attribution.
 */
@Serializable
data class FetchedSection(
    val topicId: String,
    val topicTitle: String,
    val sectionId: String,
    val sectionTitle: String,
    val contentSnippet: String = ""
)

/**
 * A retrieved table graphic with its markdown representation.
 */
data class FetchedGraphic(
    val graphicId: String,
    val title: String = "",
    val markdown: String = "",
    val topicId: String = "",
    val topicTitle: String = ""
)

/**
 * Reference pointer to a topic or topic subsection for citation and navigation.
 */
@Serializable
data class TopicRef(
    val topicId: String,
    val sectionId: String = "",
    val label: String,
    val topicTitle: String = "",
    val isGraphic: Boolean = false
)

/**
 * Reference pointer to a graphic table for citation and modal preview.
 */
@Serializable
data class GraphicRef(
    val graphicId: String,
    val label: String,
    val topicId: String? = null
)

/**
 * Ephemeral context accumulated across Stage 1 tool execution and Stage 2 synthesis.
 */
data class TurnContext(
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val answer: String = "",
    val toolResults: List<String> = emptyList(),
    val fetchedSections: List<FetchedSection> = emptyList(),
    val fetchedGraphics: List<FetchedGraphic> = emptyList(),
    val graphicIds: Set<String> = emptySet(),
    val userQuestion: String = "",
    val topicRefs: List<TopicRef> = emptyList(),
    val graphicRefs: List<GraphicRef> = emptyList(),
    val patientProfile: PatientProfile? = null
)
