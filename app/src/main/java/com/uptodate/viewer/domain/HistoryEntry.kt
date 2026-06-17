package com.uptodate.viewer.domain

import kotlinx.serialization.Serializable

@Serializable
data class HistoryEntry(
    val topicId: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val scrollPosition: Int = 0
)
