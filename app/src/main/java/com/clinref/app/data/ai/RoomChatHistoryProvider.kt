package com.clinref.app.data.ai

import com.clinref.app.data.local.dao.MessageDao
import com.clinref.app.data.local.entity.MessageEntity
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges Room [MessageDao] to Koog's ChatMemory feature.
 *
 * The exact Koog ChatHistoryProvider interface must be verified against
 * ai.koog:koog-agents:1.0.0 at compile time. This class provides the
 * data layer; the adapter to Koog's interface will be finalized once
 * the library is resolved.
 */
@Singleton
class RoomChatHistoryProvider @Inject constructor(
    private val messageDao: MessageDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadHistory(sessionId: String): List<ChatMessage> {
        return messageDao.getMessagesList(sessionId).map { entity ->
            ChatMessage(
                id = entity.id,
                role = entity.role,
                content = entity.content,
                timestamp = entity.timestamp
            )
        }
    }

    suspend fun saveMessage(sessionId: String, message: ChatMessage) {
        messageDao.insert(
            MessageEntity(
                id = message.id.ifEmpty { UUID.randomUUID().toString() },
                conversationId = sessionId,
                role = message.role,
                content = message.content,
                timestamp = message.timestamp
            )
        )
    }

    suspend fun clearHistory(sessionId: String) {
        messageDao.deleteByConversation(sessionId)
    }

    suspend fun getRecentMessages(sessionId: String, count: Int): List<ChatMessage> {
        return loadHistory(sessionId).takeLast(count)
    }

    data class ChatMessage(
        val id: String = "",
        val role: String,
        val content: String,
        val timestamp: Long = System.currentTimeMillis()
    )
}
