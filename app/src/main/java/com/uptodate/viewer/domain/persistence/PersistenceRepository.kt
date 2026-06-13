package com.uptodate.viewer.domain.persistence

import com.uptodate.viewer.domain.model.FavoritesEntry
import com.uptodate.viewer.domain.model.HistoryEntry
import kotlinx.coroutines.flow.StateFlow

interface FavoritesRepository {
    val favorites: StateFlow<List<FavoritesEntry>>
    fun add(topicId: String, title: String)
    fun remove(topicId: String)
    fun isFavorite(topicId: String): Boolean
    fun toggle(topicId: String, title: String): Boolean
    suspend fun load()
}

interface HistoryRepository {
    val history: StateFlow<List<HistoryEntry>>
    fun addOrPromote(topicId: String, title: String)
    fun remove(topicId: String)
    fun clear()
    suspend fun load()
}
