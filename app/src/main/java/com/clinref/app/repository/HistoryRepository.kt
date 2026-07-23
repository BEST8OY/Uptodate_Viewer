package com.clinref.app.repository

import com.clinref.app.data.local.dao.HistoryDao
import com.clinref.app.data.local.entity.HistoryEntity
import com.clinref.app.domain.HistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoryRepository @Inject constructor(
    private val historyDao: HistoryDao
) {
    val history: Flow<List<HistoryEntry>> = historyDao.getAll().map { entities ->
        entities.map { entity ->
            HistoryEntry(
                topicId = entity.topicId,
                title = entity.title,
                timestamp = entity.timestamp
            )
        }
    }

    suspend fun addOrPromote(topicId: String, title: String, timestamp: Long = System.currentTimeMillis()) {
        val normalizedId = topicId.removePrefix("topic-")
        historyDao.insert(
            HistoryEntity(
                topicId = normalizedId,
                title = title,
                timestamp = timestamp
            )
        )
    }

    suspend fun remove(topicId: String) {
        val normalizedId = topicId.removePrefix("topic-")
        historyDao.delete(normalizedId)
    }

    suspend fun clear() {
        historyDao.deleteAll()
    }
}
