package com.clinref.app.domain

sealed interface SearchResult {
    val title: String
    data class Topic(override val title: String, val topicId: String) : SearchResult
    data class Graphic(override val title: String, val graphicId: String) : SearchResult
}
