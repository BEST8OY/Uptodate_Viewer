@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.clinref.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

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
data class TopicOverviewResponse(
    @JsonNames("topic_id", "topicId")
    val topicId: String,
    @JsonNames("topic_title", "title")
    val title: String,
    @JsonNames("topic_type", "topicType")
    val topicType: String = "article",
    @JsonNames("outline", "sections")
    val sections: List<OutlineSectionItem> = emptyList(),
    val graphics: List<OutlineGraphicItem> = emptyList(),
    @JsonNames("related_topics", "relatedTopics")
    val relatedTopics: List<RelatedTopicItem> = emptyList(),
    val markdown: String = ""
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
    val invalidSections: List<String>? = null,
    val omittedSections: List<String>? = null,
    val truncated: Boolean? = null
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
