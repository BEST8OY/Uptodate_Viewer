package com.uptodate.viewer.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

object DbFiles {
    const val UNIDEX = "unidex.en.sqlite"
    const val ASSETS = "utdasset.sqlite"
    const val TOC = "utdtoc.db"
    const val FSEARCH = "fsearch.db"
    const val FCONTENTSEARCH = "fcontentsearch.db"
    const val QF = "utdqf.sqlite"
    val ALL = listOf(UNIDEX, ASSETS, TOC, FSEARCH, FCONTENTSEARCH, QF)
}

object AppAction {
    const val SCHEME = "appaction"
}

object Zstd {
    val MAGIC_BYTES = byteArrayOf(0x28, 0xB5.toByte(), 0x2F.toByte(), 0xFD.toByte())
}

object TopicId {
    val REGEX = Regex("^(?:topic-)?(\\d+)$", RegexOption.IGNORE_CASE)
}

object SearchPref {
    const val ALL = "X"
    const val ADULT = "A"
    const val PEDIATRIC = "P"
    const val PATIENT = "I"
}

object SearchColumns {
    const val D1 = "d1"
    const val D2 = "d2"
    const val D3 = "d3"
}

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

object DatabasePrefs {
    private val DB_DIR_URI = stringPreferencesKey("database_directory_uri")

    suspend fun saveDatabaseUri(context: android.net.Uri, appContext: Context) {
        appContext.appDataStore.edit { prefs ->
            prefs[DB_DIR_URI] = context.toString()
        }
    }

    suspend fun getSavedDatabaseUri(appContext: Context): android.net.Uri? {
        val raw = appContext.appDataStore.data.first()[DB_DIR_URI] ?: return null
        return try { android.net.Uri.parse(raw) } catch (_: Exception) { null }
    }
}
