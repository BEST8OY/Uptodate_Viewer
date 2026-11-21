package com.clinref.app.repository

import com.clinref.app.data.local.dao.ReadingPositionDao
import com.clinref.app.data.local.entity.ReadingPositionEntity
import com.clinref.app.domain.ReadingPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent store of per-article [ReadingPosition]s.
 *
 * [save] is non-blocking and write-behind: the latest position per topic is held in memory (so
 * [get] always sees it immediately) and flushed to Room on a trailing delay, so continuous
 * scrolling does not turn into one SQLite write per scroll event. Positions of finished articles
 * ([ReadingPosition.isFinished]) are treated as "no position", so those articles reopen at the top.
 */
class ReadingPositionRepository(
    private val dao: ReadingPositionDao,
    private val scope: CoroutineScope,
    private val flushDelayMs: Long = DEFAULT_FLUSH_DELAY_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** Latest unflushed state per topic; a null [Pending.position] is a pending delete. */
    private class Pending(val position: ReadingPosition?, val at: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val flushLock = Mutex()
    private val scheduleLock = Any()
    private var flushJob: Job? = null
    private var flushCount = 0

    suspend fun get(topicId: String): ReadingPosition? {
        val key = normalize(topicId)
        pending[key]?.let { return it.position }
        return dao.get(key)?.toDomain()
    }

    fun save(topicId: String, position: ReadingPosition) {
        val key = normalize(topicId)
        if (key.isEmpty()) return
        pending[key] = Pending(position.takeUnless { it.isFinished }, clock())
        scheduleFlush()
    }

    fun clear(topicId: String) {
        val key = normalize(topicId)
        if (key.isEmpty()) return
        pending[key] = Pending(null, clock())
        scheduleFlush()
    }

    /** Writes every pending change to Room immediately. */
    suspend fun flush() {
        flushLock.withLock {
            val batch = pending.entries.toList()
            if (batch.isEmpty()) return
            val upserts = mutableListOf<ReadingPositionEntity>()
            val deletes = mutableListOf<String>()
            for ((key, value) in batch) {
                val position = value.position
                if (position == null) {
                    deletes += key
                } else {
                    upserts += position.toEntity(key, value.at)
                }
            }
            if (deletes.isNotEmpty()) deletes.forEach { dao.delete(it) }
            if (upserts.isNotEmpty()) dao.upsertAll(upserts)
            // Remove only entries that were not superseded while we were writing.
            for ((key, value) in batch) pending.remove(key, value)
            if (++flushCount % PRUNE_EVERY_N_FLUSHES == 0) dao.prune(maxEntries)
        }
    }

    suspend fun prune() {
        flush()
        dao.prune(maxEntries)
    }

    private fun scheduleFlush() {
        synchronized(scheduleLock) {
            if (flushJob?.isActive == true) return
            flushJob = scope.launch {
                delay(flushDelayMs)
                synchronized(scheduleLock) { flushJob = null }
                flush()
            }
        }
    }

    private fun normalize(topicId: String): String = topicId.trim().removePrefix("topic-")

    private fun ReadingPositionEntity.toDomain() = ReadingPosition(
        anchor = anchor,
        progress = progress,
        sectionId = sectionId,
        contentRev = contentRev
    )

    private fun ReadingPosition.toEntity(topicId: String, at: Long) = ReadingPositionEntity(
        topicId = topicId,
        anchor = anchor,
        progress = progress,
        sectionId = sectionId,
        contentRev = contentRev,
        updatedAt = at
    )

    companion object {
        const val DEFAULT_FLUSH_DELAY_MS = 1_000L
        const val DEFAULT_MAX_ENTRIES = 500
        private const val PRUNE_EVERY_N_FLUSHES = 20
    }
}
