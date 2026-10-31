package com.clinref.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.clinref.app.domain.ai.BackgroundExecutionBridge

/**
 * Android implementation of [BackgroundExecutionBridge] that delegates synthesis
 * lifecycle events to [ChatGenerationService].
 */
class AndroidBackgroundExecutionBridge(
    private val context: Context
) : BackgroundExecutionBridge {

    override fun onExecutionStarted(conversationId: String, prompt: String) {
        val intent = Intent(context, ChatGenerationService::class.java).apply {
            action = ChatGenerationService.ACTION_START
            putExtra(ChatGenerationService.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(ChatGenerationService.EXTRA_PROMPT, prompt)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (_: Exception) {
            // Gracefully handles rare background start restrictions on Android 14+
        }
    }

    override fun onExecutionProgress(conversationId: String, stepText: String) {
        val intent = Intent(context, ChatGenerationService::class.java).apply {
            action = ChatGenerationService.ACTION_UPDATE_PROGRESS
            putExtra(ChatGenerationService.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(ChatGenerationService.EXTRA_STATUS_TEXT, stepText)
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}
    }

    override fun onExecutionCompleted(conversationId: String, summary: String?, isError: Boolean) {
        val intent = Intent(context, ChatGenerationService::class.java).apply {
            action = ChatGenerationService.ACTION_STOP
            putExtra(ChatGenerationService.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(ChatGenerationService.EXTRA_SUMMARY, summary)
            putExtra(ChatGenerationService.EXTRA_IS_ERROR, isError)
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}
    }

    override fun onExecutionCancelled(conversationId: String) {
        val intent = Intent(context, ChatGenerationService::class.java).apply {
            action = ChatGenerationService.ACTION_CANCEL
            putExtra(ChatGenerationService.EXTRA_CONVERSATION_ID, conversationId)
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {}
    }
}

