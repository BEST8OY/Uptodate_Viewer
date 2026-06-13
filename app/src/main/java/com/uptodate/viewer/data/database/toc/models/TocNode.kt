package com.uptodate.viewer.data.database.toc.models

import kotlinx.serialization.Serializable

@Serializable
data class TocPayload(
    val childrenInfo: List<TocChildJson> = emptyList()
)

@Serializable
data class TocChildJson(
    val id: String,
    val title: String,
    val type: String? = null,
    val childrenInfo: List<TocChildJson>? = null
)

data class TocNode(
    val id: String,
    val title: String,
    val isLeaf: Boolean,
    val type: String?,
    val childrenInfo: List<TocChildJson>?
)
