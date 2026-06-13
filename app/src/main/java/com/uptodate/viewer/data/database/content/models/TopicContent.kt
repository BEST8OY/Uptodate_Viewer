package com.uptodate.viewer.data.database.content.models

import kotlinx.serialization.Serializable

@Serializable
data class TopicPayload(
    val bodyHtml: String? = null,
    val outlineHtml: String? = null,
    val relatedGraphics: List<GraphicGroup>? = null,
    val contributors: List<ContributorGroup>? = null
)

@Serializable
data class GraphicGroup(
    val headingTitle: String? = null,
    val graphics: List<GraphicEntry>? = null
)

@Serializable
data class GraphicEntry(
    val label: String? = null,
    val graphicInfo: GraphicInfo? = null
)

@Serializable
data class GraphicInfo(
    val id: String? = null,
    val displayName: String? = null,
    val type: String? = null
)

@Serializable
data class ContributorGroup(
    val headingTitle: String? = null,
    val contributorList: List<ContributorPerson>? = null
)

@Serializable
data class ContributorPerson(
    val name: String? = null,
    val associations: List<String>? = null,
    val disclosure: String? = null
)

data class TopicContent(
    val bodyHtml: String,
    val outlineHtml: String?,
    val relatedGraphics: List<GraphicGroup>?,
    val contributors: List<ContributorGroup>?
)
