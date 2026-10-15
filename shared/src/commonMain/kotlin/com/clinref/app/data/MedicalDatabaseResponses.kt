package com.clinref.app.data

import kotlinx.serialization.Serializable

@Serializable
data class SearchResultTopicItem(
    val id: String,
    val title: String,
    val type: String = "topic"
)

@Serializable
data class SearchTopicsResponse(
    val query: String = "",
    val results: List<SearchResultTopicItem> = emptyList(),
    val refine_with: List<String> = emptyList(),
    val message: String = ""
)

@Serializable
data class OutlineSectionItem(
    val id: String,
    val title: String,
    val level: Int = 1,
    val parentId: String? = null
)

@Serializable
data class OutlineGraphicItem(
    val id: String,
    val title: String,
    val isTable: Boolean = true
)

@Serializable
data class RelatedTopicItem(
    val id: String,
    val title: String
)

@Serializable
data class TopicOutlineResponse(
    val topicId: String,
    val title: String,
    val topicType: String,
    val sections: List<OutlineSectionItem> = emptyList(),
    val graphics: List<OutlineGraphicItem> = emptyList(),
    val relatedTopics: List<RelatedTopicItem> = emptyList()
)

@Serializable
data class RelatedTopicsResponse(
    val topicId: String,
    val relatedTopics: List<RelatedTopicItem> = emptyList()
)

@Serializable
data class TopicSectionsResponse(
    val topicId: String = "",
    val topicTitle: String,
    val sectionTitles: Map<String, String> = emptyMap(),
    val markdown: String,
    val invalidSections: List<String>? = null
)

@Serializable
data class ToolErrorResponse(
    val error: String
)

@Serializable
data class SectionSearchResult(
    val sectionId: String,
    val sectionTitle: String,
    val level: Int = 1,
    val snippet: String
)

@Serializable
data class SearchTopicSectionsResponse(
    val topicId: String,
    val query: String,
    val matches: List<SectionSearchResult> = emptyList(),
    val message: String = ""
)
