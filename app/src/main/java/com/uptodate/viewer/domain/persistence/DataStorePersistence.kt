package com.uptodate.viewer.domain.persistence

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.uptodate.viewer.domain.model.FavoritesEntry
import com.uptodate.viewer.domain.model.HistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")
private val json = Json { ignoreUnknownKeys = true }

private object PrefsKeys {
    val FAVORITES = stringPreferencesKey("favorites_json")
    val HISTORY = stringPreferencesKey("history_json")
}

class DataStoreFavoritesRepository(
    private val context: Context,
    private val scope: CoroutineScope
) : FavoritesRepository {

    private val _favorites = MutableStateFlow<List<FavoritesEntry>>(emptyList())
    override val favorites: StateFlow<List<FavoritesEntry>> = _favorites

    override fun load() {
        scope.launch {
            val data = context.dataStore.data.first()
            val raw = data[PrefsKeys.FAVORITES] ?: return@launch
            try {
                val entries = json.decodeFromString<List<FavoritesEntry>>(raw)
                _favorites.value = entries
            } catch (_: Exception) {}
        }
    }

    override fun add(topicId: String, title: String) {
        val current = _favorites.value.toMutableList()
        current.removeAll { it.topicId == topicId }
        current.add(0, FavoritesEntry(topicId, title))
        _favorites.value = current
        persist(current)
    }

    override fun remove(topicId: String) {
        val current = _favorites.value.filter { it.topicId != topicId }
        _favorites.value = current
        persist(current)
    }

    override fun isFavorite(topicId: String): Boolean {
        return _favorites.value.any { it.topicId == topicId }
    }

    override fun toggle(topicId: String, title: String): Boolean {
        return if (isFavorite(topicId)) {
            remove(topicId)
            false
        } else {
            add(topicId, title)
            true
        }
    }

    private fun persist(entries: List<FavoritesEntry>) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PrefsKeys.FAVORITES] = json.encodeToString(entries)
            }
        }
    }
}

class DataStoreHistoryRepository(
    private val context: Context,
    private val scope: CoroutineScope
) : HistoryRepository {

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    override val history: StateFlow<List<HistoryEntry>> = _history

    override fun load() {
        scope.launch {
            val data = context.dataStore.data.first()
            val raw = data[PrefsKeys.HISTORY] ?: return@launch
            try {
                val entries = json.decodeFromString<List<HistoryEntry>>(raw)
                _history.value = entries
            } catch (_: Exception) {}
        }
    }

    override fun addOrPromote(topicId: String, title: String) {
        val current = _history.value.toMutableList()
        current.removeAll { it.topicId == topicId }
        current.add(0, HistoryEntry(topicId, title))
        _history.value = current
        persist(current)
    }

    override fun remove(topicId: String) {
        val current = _history.value.filter { it.topicId != topicId }
        _history.value = current
        persist(current)
    }

    override fun clear() {
        _history.value = emptyList()
        persist(emptyList())
    }

    private fun persist(entries: List<HistoryEntry>) {
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[PrefsKeys.HISTORY] = json.encodeToString(entries)
            }
        }
    }
}
