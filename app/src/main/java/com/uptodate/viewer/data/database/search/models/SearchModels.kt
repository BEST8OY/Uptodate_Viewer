package com.uptodate.viewer.data.database.search.models

data class Suggestion(
    val word: String,
    val weight: Int
)

data class SearchResultRow(
    val topicId: String,
    val title: String,
    val hits: String? = null
)

data class QueryMatch(
    val qbtype: String?,
    val topicHitsBlob: ByteArray?
)
