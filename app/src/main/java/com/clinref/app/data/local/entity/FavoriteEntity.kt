package com.clinref.app.data.local.entity

import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val topicId: String,
    val title: String,
    val timestamp: Long = System.currentTimeMillis()
)
