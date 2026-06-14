package com.uptodate.viewer.domain

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteEntry(
    val topicId: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)
