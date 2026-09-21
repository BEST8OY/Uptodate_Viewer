package com.clinref.app.repository

import com.clinref.app.data.local.dao.FavoriteDao
import com.clinref.app.data.local.entity.FavoriteEntity
import com.clinref.app.domain.FavoriteEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteRepository @Inject constructor(
    private val favoriteDao: FavoriteDao
) {
    val favorites: Flow<List<FavoriteEntry>> = favoriteDao.getAll().map { entities ->
        entities.map { entity ->
            FavoriteEntry(
                topicId = entity.topicId,
                title = entity.title,
                timestamp = entity.timestamp
            )
        }
    }

    suspend fun add(topicId: String, title: String, timestamp: Long = System.currentTimeMillis()) {
        val normalizedId = topicId.removePrefix("topic-")
        favoriteDao.insert(
            FavoriteEntity(
                topicId = normalizedId,
                title = title,
                timestamp = timestamp
            )
        )
    }

    suspend fun remove(topicId: String) {
        val normalizedId = topicId.removePrefix("topic-")
        favoriteDao.delete(normalizedId)
    }

    suspend fun clearAll() {
        favoriteDao.deleteAll()
    }

    suspend fun isFavorite(topicId: String): Boolean {
        val normalizedId = topicId.removePrefix("topic-")
        return favoriteDao.isFavorite(normalizedId).first()
    }
}
