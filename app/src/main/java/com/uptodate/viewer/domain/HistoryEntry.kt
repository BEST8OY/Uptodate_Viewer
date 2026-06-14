package com.uptodate.viewer.domain

data class HistoryEntry(
    val topicId: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)
