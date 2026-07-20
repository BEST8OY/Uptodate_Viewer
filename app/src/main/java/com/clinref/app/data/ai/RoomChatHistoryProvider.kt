package com.clinref.app.data.ai

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.prompt.message.Message
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
        // No-op: ChatViewModel handles all message persistence with richer metadata
        // (citations, warnings, isError). ChatMemory store() would create duplicates.
    }

    override suspend fun load(conversationId: String): List<Message> {
        return messageDao.getMessagesList(conversationId)
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { !it.isError }
            .map { entity ->
                val elapsedMs = System.currentTimeMillis() - entity.timestamp
                val timestamp = ai.koog.utils.time.KoogClock.System.now()
                    .minus(kotlin.time.Duration.parse("${elapsedMs}ms"))
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
