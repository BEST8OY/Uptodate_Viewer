@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.clinref.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Single candidate topic returned by searchTopics.
 */
@Serializable
data class SearchResultTopicItem(
    val id: String,
    val title: String,
    val type: String = "topic"
)

/**
 * Strongly-typed result for searchTopics tool.
 */
@Serializable
data class SearchTopicsResponse(
    val query: String = "",
    val results: List<SearchResultTopicItem> = emptyList(),
    val refine_with: List<String> = emptyList(),
    val message: String = "",
    @JsonNames("auto_refined", "autoRefined")
    val autoRefined: Boolean = false,
    @JsonNames("refined_query", "refinedQuery")
    val refinedQuery: String? = null,
    val error: String? = null
)

/**
 * Outline section structural item in getTopicOverview.
 */
@Serializable
data class OutlineSectionItem(
    val id: String,
    val title: String,
    val level: Int = 1,
    val parentId: String? = null
)

/**
 * Graphic table item in getTopicOverview.
 */
@Serializable
data class OutlineGraphicItem(
    val id: String,
    val title: String,
    val isTable: Boolean = true
)

/**
 * Related topic item in getTopicOverview.
 */
@Serializable
data class RelatedTopicItem(
    val id: String,
    val title: String
)

/**
 * Strongly-typed compound bundle for getTopicOverview.
 *
 * [topicType] is the payload's declared `topicInfo.type` — `drug`, `medical`, `calc`, or
 * `narrative` — so the model can pick section names that exist for that content class.
 */
@Serializable
data class TopicOverviewResponse(
    @JsonNames("topic_id", "topicId")
    val topicId: String = "",
    @JsonNames("topic_title", "title")
    val title: String = "",
    @JsonNames("topic_type", "topicType")
    val topicType: String = "",
    @JsonNames("outline", "sections")
    val sections: List<OutlineSectionItem> = emptyList(),
    val graphics: List<OutlineGraphicItem> = emptyList(),
    @JsonNames("related_topics", "relatedTopics")
    val relatedTopics: List<RelatedTopicItem> = emptyList(),
    val markdown: String = "",
    val error: String? = null
)

/**
 * Strongly-typed result for getTopicSectionsText.
 */
@Serializable
data class TopicSectionsResponse(
    @JsonNames("topic_id", "topicId")
    val topicId: String = "",
    @JsonNames("topic_title", "topicTitle")
    val topicTitle: String = "",
    @JsonNames("section_titles", "sectionTitles")
    val sectionTitles: Map<String, String> = emptyMap(),
    val markdown: String = "",
    @JsonNames("invalid_sections", "invalidSections")
    val invalidSections: List<String>? = null,
    @JsonNames("omitted_sections", "omittedSections")
    val omittedSections: List<String>? = null,
    val truncated: Boolean? = null,
    val error: String? = null
)

/**
 * Strongly-typed result for getGraphicContent.
 */
@Serializable
data class GraphicContentResponse(
    @JsonNames("graphic_id", "graphicId")
    val graphicId: String = "",
    val title: String = "",
    val markdown: String = "",
    val error: String? = null
)


/**
 * Single section match in searchTopicSections.
 */
@Serializable
data class SectionSearchResult(
    @JsonNames("section_id", "sectionId")
    val sectionId: String,
    @JsonNames("section_title", "sectionTitle")
    val sectionTitle: String,
    val level: Int = 1,
    val snippet: String
)

/**
 * Strongly-typed result for searchTopicSections.
 */
@Serializable
data class SearchTopicSectionsResponse(
    @JsonNames("topic_id", "topicId")
    val topicId: String = "",
    val query: String = "",
    val matches: List<SectionSearchResult> = emptyList(),
    val message: String = "",
    val error: String? = null
)
