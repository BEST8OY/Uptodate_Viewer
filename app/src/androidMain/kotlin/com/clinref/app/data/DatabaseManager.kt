package com.clinref.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseManager @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val connections = mutableMapOf<String, SQLiteDatabase>()
    private var dbDirectory: File? = null

    companion object {
        private const val PREFS_NAME = "db_prefs"
        private const val KEY_DB_PATH = "db_path"
        private val DB_FILES = listOf(
            "unidex.en.sqlite",
            "utdtoc.db",
            "fsearch.db",
            "fcontentsearch.db",
            "utdasset.sqlite",
            "utdqf.sqlite"
        )
    }

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_DB_PATH, null)
        if (savedPath != null) {
            val dir = File(savedPath)
            if (dir.exists() && dir.isDirectory) {
                dbDirectory = dir
            }
        }
    }

    fun setDatabaseDirectory(path: String): Boolean {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return false

        val missingFiles = DB_FILES.filter { !File(dir, it).exists() }
        if (missingFiles.isNotEmpty()) return false

        closeAll()
        dbDirectory = dir

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DB_PATH, path)
            .apply()

        return true
    }

    fun setDatabaseDirectory(uri: Uri): Boolean {
        val docFile = File(uri.path ?: return false)
        val dir = if (docFile.exists() && docFile.isDirectory) {
            docFile
        } else {
            // Try to get parent directory
            val parentDir = docFile.parentFile ?: return false
            if (parentDir.exists() && parentDir.isDirectory) parentDir else return false
        }

        return setDatabaseDirectory(dir.absolutePath)
    }

    fun getDatabaseDirectory(): File? = dbDirectory

    fun isConfigured(): Boolean = dbDirectory != null && dbDirectory!!.exists()

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
    fun getFcontentsearchDb(): SQLiteDatabase = getConnection("fcontentsearch.db")
    fun getAssetsDb(): SQLiteDatabase = getConnection("utdasset.sqlite")
    fun getQfDb(): SQLiteDatabase = getConnection("utdqf.sqlite")

    fun closeAll() {
        connections.values.forEach { 
            try { it.close() } catch (_: Exception) {}
        }
        connections.clear()
    }
}
