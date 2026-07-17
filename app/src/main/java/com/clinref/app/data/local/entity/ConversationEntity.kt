package com.clinref.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val patientProfile: String,
    val createdAt: Long,
    val updatedAt: Long,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val toolTokens: Int = 0,
    val tokenLimit: Int = 100_000
) {
    val totalTokens: Int get() = promptTokens + completionTokens + toolTokens
}
