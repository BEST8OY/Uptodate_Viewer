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

        synchronized(timestamps) {
            val now = System.currentTimeMillis()
            val windowStart = now - 60_000L
            timestamps.removeAll { it < windowStart }

            if (timestamps.size >= rpmLimit) {
                val oldestInWindow = timestamps.first()
                val waitMs = 60_000L - (now - oldestInWindow) + 100
                if (waitMs > 0) {
                    synchronized(timestamps) { timestamps.add(oldestInWindow + waitMs) }
                    delay(waitMs)
                    synchronized(timestamps) {
                        timestamps.removeAll { it <= System.currentTimeMillis() }
                        timestamps.add(System.currentTimeMillis())
                    }
                    return
                }
            }
            timestamps.add(now)
        }
    }
}
