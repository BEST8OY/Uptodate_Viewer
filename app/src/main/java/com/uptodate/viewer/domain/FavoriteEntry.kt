package com.uptodate.viewer.domain

data class FavoriteEntry(
    val topicId: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)
