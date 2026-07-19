package com.clinref.app.repository

import com.clinref.app.data.local.dao.ConversationDao
import com.clinref.app.data.local.dao.MessageDao
import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.domain.ai.PatientProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getAllConversations(): Flow<List<ConversationEntity>> = conversationDao.getAll()

    suspend fun getConversation(id: String): ConversationEntity? = conversationDao.getById(id)

    suspend fun createConversation(title: String, patientProfile: PatientProfile): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        conversationDao.insert(
            ConversationEntity(
                id = id,
                title = title,
                patientProfile = json.encodeToString(patientProfile),
                createdAt = now,
                updatedAt = now
            )
        )
        return id
    }

    suspend fun renameConversation(id: String, newTitle: String) {
        conversationDao.rename(id, newTitle)
    }

    suspend fun deleteConversation(id: String) {
        val conversation = conversationDao.getById(id) ?: return
        conversationDao.delete(conversation)
    }

    suspend fun addMessage(message: MessageEntity) {
        messageDao.insert(message)
    }

    fun getMessages(conversationId: String): Flow<List<MessageEntity>> =
        messageDao.getMessages(conversationId)

    suspend fun getMessagesList(conversationId: String): List<MessageEntity> =
        messageDao.getMessagesList(conversationId)

    suspend fun updateTokenCounts(id: String, promptDelta: Int, completionDelta: Int, toolDelta: Int) {
        conversationDao.updateTokenCounts(id, promptDelta, completionDelta, toolDelta)
    }

    suspend fun isOverTokenLimit(id: String): Boolean = conversationDao.isOverTokenLimit(id)

    suspend fun getPatientProfile(conversationId: String): PatientProfile {
        val conversation = conversationDao.getById(conversationId) ?: return PatientProfile()
        return try {
            json.decodeFromString<PatientProfile>(conversation.patientProfile)
        } catch (_: Exception) {
            PatientProfile()
        }
    }

    suspend fun markAsRead(id: String) {
        conversationDao.markAsRead(id)
    }

    fun getUnreadCount(): Flow<Int> = conversationDao.getUnreadCount()

    suspend fun getLatestMessage(conversationId: String): MessageEntity? =
        messageDao.getLatestMessage(conversationId)

    suspend fun getMessagesPage(conversationId: String, limit: Int, offset: Int): List<MessageEntity> =
        messageDao.getMessagesPage(conversationId, limit, offset)

    suspend fun updateLastPreview(id: String, preview: String) {
        conversationDao.updatePreview(id, preview)
    }
}
