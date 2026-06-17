package com.uptodate.viewer.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uptodate.viewer.domain.HistoryEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.historyDataStore: DataStore<Preferences> by preferencesDataStore(name = "history")

@Singleton
class HistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val historyKey = stringPreferencesKey("history_json")

    val history: Flow<List<HistoryEntry>> = context.historyDataStore.data.map { prefs ->
        val jsonStr = prefs[historyKey] ?: "[]"
        try {
            json.decodeFromString<List<HistoryEntry>>(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addOrPromote(topicId: String, title: String) {
        context.historyDataStore.edit { prefs ->
            val current = getHistory(prefs)
            val normalizedId = topicId.removePrefix("topic-")
            val newEntry = HistoryEntry(normalizedId, title)
            val updated = listOf(newEntry) + current.filter { it.topicId != normalizedId }
            prefs[historyKey] = json.encodeToString(updated.take(100))
        }
    }

    suspend fun updateScrollPosition(topicId: String, scrollPosition: Int) {
        context.historyDataStore.edit { prefs ->
            val current = getHistory(prefs)
            val normalizedId = topicId.removePrefix("topic-")
            val updated = current.map { entry ->
                if (entry.topicId == normalizedId) entry.copy(scrollPosition = scrollPosition) else entry
            }
            prefs[historyKey] = json.encodeToString(updated)
        }
    }

    suspend fun remove(topicId: String) {
        context.historyDataStore.edit { prefs ->
            val current = getHistory(prefs)
            val normalizedId = topicId.removePrefix("topic-")
            val updated = current.filter { it.topicId != normalizedId }
            prefs[historyKey] = json.encodeToString(updated)
        }
    }

    suspend fun clear() {
        context.historyDataStore.edit { prefs ->
            prefs[historyKey] = "[]"
        }
    }

    private fun getHistory(prefs: Preferences): List<HistoryEntry> {
        val jsonStr = prefs[historyKey] ?: "[]"
        return try {
            json.decodeFromString(jsonStr)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
