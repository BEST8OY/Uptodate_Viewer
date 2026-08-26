package com.clinref.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val connections = mutableMapOf<String, SQLiteDatabase>()
    private var dbDirectory: File? = null

    private val _isConfigured = MutableStateFlow(false)
    val isConfiguredFlow: StateFlow<Boolean> = _isConfigured.asStateFlow()

    companion object {
        private const val PREFS_NAME = "db_prefs"
        private const val KEY_DB_PATH = "db_path"
        private val DB_FILES = listOf(
            "unidex.en.sqlite",
            "utdtoc.db",
            "fsearch.db",
            "utdasset.sqlite",
            "utdqf.sqlite"
        )
    }

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_DB_PATH, null)
        if (savedPath != null) {
            val dir = File(savedPath)
            if (validateDirectory(dir)) {
                dbDirectory = dir
                _isConfigured.value = true
            }
        }
    }

    fun validateDirectory(dir: File?): Boolean {
        if (dir == null || !dir.exists() || !dir.isDirectory) return false
        return DB_FILES.all { File(dir, it).exists() }
    }

    fun setDatabaseDirectory(path: String): Boolean {
        val dir = File(path)
        if (!validateDirectory(dir)) {
            _isConfigured.value = false
            return false
        }

        closeAll()
        dbDirectory = dir

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DB_PATH, path)
            .apply()

        _isConfigured.value = true
        return true
    }

    fun setDatabaseDirectory(uri: Uri): Boolean {
        uri.path?.let { path ->
            if (setDatabaseDirectory(path)) return true
        }

        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            uri.lastPathSegment
        }

        if (docId != null && docId.contains(":")) {
            val split = docId.split(":")
            val type = split[0]
            val relativePath = if (split.size > 1) split[1] else ""
            if (type.equals("primary", ignoreCase = true)) {
                val externalStorage = Environment.getExternalStorageDirectory().absolutePath
                val resolvedPath = if (relativePath.isNotEmpty()) "$externalStorage/$relativePath" else externalStorage
                if (setDatabaseDirectory(resolvedPath)) return true
            } else {
                val resolvedPath = "/storage/$type/$relativePath"
                if (setDatabaseDirectory(resolvedPath)) return true
            }
        }

        return false
    }

    fun getDatabaseDirectory(): File? = dbDirectory

    fun isConfigured(): Boolean = validateDirectory(dbDirectory)

    fun getAvailableDatabases(): List<String> {
        val dir = dbDirectory ?: return emptyList()
        return DB_FILES.filter { File(dir, it).exists() }
    }

    fun getMissingDatabases(): List<String> {
        val dir = dbDirectory ?: return DB_FILES
        return DB_FILES.filter { !File(dir, it).exists() }
    }

    private fun getConnection(dbName: String): SQLiteDatabase {
        val dir = dbDirectory ?: throw IllegalStateException("Database directory not configured")
        return connections.getOrPut(dbName) {
            val dbFile = File(dir, dbName)
            if (!dbFile.exists()) {
                throw IllegalStateException("Database file not found: $dbName")
            }
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            )
        }
    }

    fun getUnidexDb(): SQLiteDatabase = getConnection("unidex.en.sqlite")
    fun getTocDb(): SQLiteDatabase = getConnection("utdtoc.db")
    fun getFsearchDb(): SQLiteDatabase = getConnection("fsearch.db")
    fun getAssetsDb(): SQLiteDatabase = getConnection("utdasset.sqlite")
    fun getQfDb(): SQLiteDatabase = getConnection("utdqf.sqlite")

    fun closeAll() {
        connections.values.forEach { 
            try { it.close() } catch (_: Exception) {}
        }
        connections.clear()
    }
}

