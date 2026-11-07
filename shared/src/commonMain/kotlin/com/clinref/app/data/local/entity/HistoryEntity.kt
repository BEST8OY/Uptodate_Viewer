package com.clinref.app.data.local.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val topicId: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)
