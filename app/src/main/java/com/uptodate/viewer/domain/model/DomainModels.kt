package com.uptodate.viewer.domain.model

import kotlinx.serialization.Serializable

data class TocItem(
    val id: String,
    val title: String,
    val isLeaf: Boolean,
    val type: String?,
    val hasChildren: Boolean
)

data class TopicDetail(
    val topicId: String,
    val title: String,
    val bodyHtml: String,
    val outlineHtml: String?,
    val relatedGraphics: List<GraphicGroup>?,
    val contributors: List<ContributorGroup>?
)

data class GraphicGroup(
    val headingTitle: String?,
    val graphics: List<GraphicEntry>
)

data class GraphicEntry(
    val label: String?,
    val graphicId: String?,
    val displayName: String?
)

data class ContributorGroup(
    val headingTitle: String?,
    val contributors: List<ContributorPerson>
)

data class ContributorPerson(
    val name: String?,
    val associations: List<String>?,
    val disclosure: String?
)

data class SearchResultItem(
    val topicId: String,
    val title: String
)

@Serializable
data class FavoritesEntry(
    val topicId: String,
    val title: String
)

@Serializable
data class HistoryEntry(
    val topicId: String,
    val title: String
)
