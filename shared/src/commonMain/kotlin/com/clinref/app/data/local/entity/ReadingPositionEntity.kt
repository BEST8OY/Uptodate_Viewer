package com.clinref.app.data.local.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "reading_position")
data class ReadingPositionEntity(
    @PrimaryKey val topicId: String,
    val anchor: String,
    val progress: Float,
    val sectionId: String? = null,
    val contentRev: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
