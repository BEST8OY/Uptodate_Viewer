package com.uptodate.viewer.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uptodate.viewer.domain.FavoriteEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites")

@Singleton
class FavoriteRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val favoritesKey = stringPreferencesKey("favorites_json")

    val favorites: Flow<List<FavoriteEntry>> = context.favoritesDataStore.data.map { prefs ->
        val jsonStr = prefs[favoritesKey] ?: "[]"
        try {
            json.decodeFromString<List<FavoriteEntry>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun add(topicId: String, title: String) {
        context.favoritesDataStore.edit { prefs ->
            val current = getFavorites(prefs)
            val normalizedId = topicId.removePrefix("topic-")
            val newEntry = FavoriteEntry(normalizedId, title)
            val updated = listOf(newEntry) + current.filter { it.topicId != normalizedId }
            prefs[favoritesKey] = json.encodeToString(updated)
        }
    }

    suspend fun remove(topicId: String) {
        context.favoritesDataStore.edit { prefs ->
            val current = getFavorites(prefs)
            val normalizedId = topicId.removePrefix("topic-")
            val updated = current.filter { it.topicId != normalizedId }
            prefs[favoritesKey] = json.encodeToString(updated)
        }
    }

    suspend fun isFavorite(topicId: String): Boolean {
        val normalizedId = topicId.removePrefix("topic-")
        return favorites.first().any { it.topicId == normalizedId }
    }

    private fun getFavorites(prefs: Preferences): List<FavoriteEntry> {
        val jsonStr = prefs[favoritesKey] ?: "[]"
        return try {
            json.decodeFromString(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
