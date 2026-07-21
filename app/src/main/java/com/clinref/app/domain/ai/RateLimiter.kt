package com.clinref.app.domain.ai

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RateLimiter @Inject constructor() {

    private val timestamps = mutableListOf<Long>()
    private var rpmLimit = 0

    fun configure(requestsPerMinute: Int) {
        rpmLimit = requestsPerMinute
    }

    suspend fun acquire() {
        if (rpmLimit <= 0) return

        val waitMs = synchronized(timestamps) {
            val now = System.currentTimeMillis()
            val windowStart = now - 60_000L
            timestamps.removeAll { it < windowStart }

            if (timestamps.size >= rpmLimit) {
                val oldestInWindow = timestamps.first()
                val wait = 60_000L - (now - oldestInWindow) + 100
                if (wait > 0) wait else 0L
            } else {
                timestamps.add(now)
                0L
            }
        }

        if (waitMs > 0) {
            delay(waitMs)
            synchronized(timestamps) {
                val now = System.currentTimeMillis()
                // Drop only entries falling outside the active sliding window
                val windowStart = now - 60_000L
                timestamps.removeAll { it < windowStart }
                timestamps.add(now)
            }
        }
    }
}
