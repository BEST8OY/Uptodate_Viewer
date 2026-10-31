package com.clinref.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.clinref.app.MainActivity
import com.clinref.app.domain.ai.ChatExecutionManager
import org.koin.android.ext.android.inject

/**
 * Foreground Service that keeps CPU and network interfaces active during background
 * clinical AI synthesis sessions, preventing Android 11–15 Cached App Freezer pauses.
 */
class ChatGenerationService : Service() {

    private val chatExecutionManager: ChatExecutionManager by inject()
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentConversationId: String? = null

    companion object {
        const val CHANNEL_ID = "clinref_ai_generation"
        const val NOTIFICATION_ID = 2001
        const val COMPLETION_NOTIFICATION_ID = 2002

        const val ACTION_START = "com.clinref.app.action.START_GENERATION"
        const val ACTION_UPDATE_PROGRESS = "com.clinref.app.action.UPDATE_PROGRESS"
        const val ACTION_STOP = "com.clinref.app.action.STOP_GENERATION"
        const val ACTION_CANCEL = "com.clinref.app.action.CANCEL_GENERATION"

        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_PROMPT = "extra_prompt"
        const val EXTRA_STATUS_TEXT = "extra_status_text"
        const val EXTRA_SUMMARY = "extra_summary"
        const val EXTRA_IS_ERROR = "extra_is_error"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClinRef:ChatGenerationWakeLock")
        wakeLock?.acquire(3 * 60 * 1000L) // 3-minute safety timeout
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: "Synthesizing clinical evidence..."
                currentConversationId = convId
                startForegroundNotification(prompt)
            }
            ACTION_UPDATE_PROGRESS -> {
                val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                if (convId != null) {
                    currentConversationId = convId
                }
                val statusText = intent.getStringExtra(EXTRA_STATUS_TEXT)
                if (!statusText.isNullOrBlank()) {
                    updateNotification(statusText)
                }
            }
            ACTION_CANCEL -> {
                val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: currentConversationId
                if (convId != null) {
                    chatExecutionManager.cancelGeneration(convId)
                }
                stopForegroundAndSelf()
            }
            ACTION_STOP -> {
                val convId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
                val summary = intent.getStringExtra(EXTRA_SUMMARY)
                val isError = intent.getBooleanExtra(EXTRA_IS_ERROR, false)
                stopForegroundAndSelf()
                if (!isError && !summary.isNullOrBlank()) {
                    postCompletionNotification(convId, summary)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(initialText: String) {
        val notification = buildNotification(initialText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    private fun buildNotification(contentText: String): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversation_id", currentConversationId)
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ChatGenerationService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_CONVERSATION_ID, currentConversationId)
        }
        val pendingCancel = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClinRef Clinical Core")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", pendingCancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun postCompletionNotification(conversationId: String?, summary: String) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("conversation_id", conversationId)
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            2,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clinical Response Ready")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(pendingOpen)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        try {
            manager?.notify(COMPLETION_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        wakeLock = null
    }

    private fun stopForegroundAndSelf() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clinical Core Reasoning",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing clinical AI evidence synthesis"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

