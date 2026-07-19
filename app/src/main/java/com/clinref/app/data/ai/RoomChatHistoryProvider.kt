package com.clinref.app.data.ai

import ai.koog.agents.features.memory.ChatHistoryProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.clinref.app.data.local.dao.MessageDao
import com.clinref.app.data.local.entity.MessageEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomChatHistoryProvider @Inject constructor(
    private val messageDao: MessageDao
) : ChatHistoryProvider {

    override suspend fun store(conversationId: String, messages: List<Message>) {
        val existingIds = messageDao.getMessagesList(conversationId).map { it.id }.toSet()
        for (msg in messages) {
            val id = msg.id ?: UUID.randomUUID().toString()
            if (id in existingIds) continue
            messageDao.insert(
                MessageEntity(
                    id = id,
                    conversationId = conversationId,
                    role = msg.role.name.lowercase(),
                    content = msg.textContent(),
                    timestamp = msg.metaInfo.timestamp.toEpochMilliseconds()
                )
            )
        }
    }

    override suspend fun load(conversationId: String): List<Message> {
        return messageDao.getMessagesList(conversationId).map { entity ->
            val timestamp = ai.koog.utils.time.KoogClock.System.now()
                .minus((System.currentTimeMillis() - entity.timestamp) * 1_000_000)
            when (entity.role) {
                "user" -> Message.User(
                    content = entity.content,
                    metaInfo = RequestMetaInfo(timestamp),
                    id = entity.id
                )
                "assistant" -> Message.Assistant(
                    content = entity.content,
                    metaInfo = ResponseMetaInfo(timestamp),
                    id = entity.id
                )
                else -> Message.User(
                    content = entity.content,
                    metaInfo = RequestMetaInfo(timestamp),
                    id = entity.id
                )
            }
        }
    }
}
