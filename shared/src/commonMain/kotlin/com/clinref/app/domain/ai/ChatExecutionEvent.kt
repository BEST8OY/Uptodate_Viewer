package com.clinref.app.domain.ai

import com.clinref.app.data.local.entity.MessageEntity

/**
 * Real-time lifecycle and persistence events emitted by [ChatExecutionManager]
 * to notify active UI screens of background synthesis progress.
 */
sealed interface ChatExecutionEvent {
    val conversationId: String

    data class UserMessageAdded(
        override val conversationId: String,
        val message: MessageEntity
    ) : ChatExecutionEvent

    data class GenerationStarted(
        override val conversationId: String,
        val messageId: String
    ) : ChatExecutionEvent

    data class AssistantMessageCompleted(
        override val conversationId: String,
        val message: MessageEntity,
        val warnings: List<String> = emptyList()
    ) : ChatExecutionEvent

    data class GenerationError(
        override val conversationId: String,
        val message: MessageEntity
    ) : ChatExecutionEvent

    data class GenerationCancelled(
        override val conversationId: String,
        val message: MessageEntity
    ) : ChatExecutionEvent
}

