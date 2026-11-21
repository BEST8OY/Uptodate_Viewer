package com.clinref.app.domain

import kotlinx.serialization.Serializable

@Serializable
data class HistoryEntry(
    val topicId: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)
