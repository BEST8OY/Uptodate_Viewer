package com.clinref.shared.tools

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.Serializable

/**
 * Pure Kotlin Multiplatform (KMP) tool contracts for Koog.
 *
 * Implements class-based [Tool] interfaces that require zero JVM reflection,
 * enabling full code sharing across Android, iOS, Desktop, and Web.
 */

@Serializable
data class SearchTopicsInput(
    val query: String
)

@Serializable
data class SearchTopicsOutput(
    val query: String,
    val results: List<TopicMatchItem>,
    val suggestions: List<String> = emptyList(),
    val message: String = ""
)

@Serializable
data class TopicMatchItem(
    val id: String,
    val title: String
)

@Serializable
data class TopicOutlineInput(
    val topicId: String
)

@Serializable
data class TopicOutlineOutput(
    val topicId: String,
    val title: String,
    val topicType: String,
    val sections: List<SectionDescriptorItem>,
    val tableGraphics: List<GraphicDescriptorItem>
)

@Serializable
data class SectionDescriptorItem(
    val id: String,
    val title: String
)

@Serializable
data class GraphicDescriptorItem(
    val id: String,
    val title: String
)

@Serializable
data class BatchSectionTextInput(
    val topicId: String,
    val sectionIds: List<String>
)

@Serializable
data class BatchSectionTextOutput(
    val topicTitle: String,
    val markdown: String,
    val invalidSections: List<String> = emptyList()
)
