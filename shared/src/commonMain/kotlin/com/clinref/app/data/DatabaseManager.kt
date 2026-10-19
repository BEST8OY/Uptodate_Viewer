package com.clinref.app.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.clinref.shared.platform.PlatformFileSystem
import com.clinref.shared.platform.getPlatformFileSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cross-platform DatabaseManager providing thread-safe [SQLiteConnection] instances
 * for the 5 root SQLite databases using [BundledSQLiteDriver].
 *
 * Runs identically on Android, JVM Desktop (Linux/macOS/Windows), and iOS.
 */
class DatabaseManager(
    private val driver: SQLiteDriver = BundledSQLiteDriver(),
    private val fileSystem: PlatformFileSystem = getPlatformFileSystem()
) {
    private val connections = mutableMapOf<String, SQLiteConnection>()
    private var dbDirectory: String? = null

    private val _isConfigured = MutableStateFlow(false)
    val isConfiguredFlow: StateFlow<Boolean> = _isConfigured.asStateFlow()

    companion object {
        val DB_FILES = listOf(
            "unidex.en.sqlite",
            "utdtoc.db",
            "fsearch.db",
            "utdasset.sqlite",
            "utdqf.sqlite"
        )
    }

    fun validateDirectory(dirPath: String?): Boolean {
        if (dirPath == null || !fileSystem.exists(dirPath) || !fileSystem.isDirectory(dirPath)) return false
        return DB_FILES.all { fileSystem.exists(fileSystem.joinPath(dirPath, it)) }
    }

    fun setDatabaseDirectory(path: String): Boolean {
        if (!validateDirectory(path)) {
            _isConfigured.value = false
            return false
        }

        closeAll()
        dbDirectory = path
        _isConfigured.value = true
        return true
    }

    fun getDatabaseDirectory(): String? = dbDirectory

    fun isConfigured(): Boolean = validateDirectory(dbDirectory)

    fun getAvailableDatabases(): List<String> {
        val dir = dbDirectory ?: return emptyList()
        return DB_FILES.filter { fileSystem.exists(fileSystem.joinPath(dir, it)) }
    }

    fun getMissingDatabases(): List<String> {
        val dir = dbDirectory ?: return DB_FILES
        return DB_FILES.filter { !fileSystem.exists(fileSystem.joinPath(dir, it)) }
    }

    fun getConnection(dbName: String): SQLiteConnection {
        val dir = dbDirectory ?: throw IllegalStateException("Database directory not configured")
        return connections.getOrPut(dbName) {
            val dbPath = fileSystem.joinPath(dir, dbName)
            if (!fileSystem.exists(dbPath)) {
                throw IllegalStateException("Database file not found: $dbName at $dbPath")
            }
            driver.open(dbPath)
        }
    }

    fun getUnidexDb(): SQLiteConnection = getConnection("unidex.en.sqlite")
    fun getTocDb(): SQLiteConnection = getConnection("utdtoc.db")
    fun getFsearchDb(): SQLiteConnection = getConnection("fsearch.db")
    fun getAssetsDb(): SQLiteConnection = getConnection("utdasset.sqlite")
    fun getQfDb(): SQLiteConnection = getConnection("utdqf.sqlite")

    fun closeAll() {
        connections.values.forEach {
            try { it.close() } catch (_: Exception) {}
        }
        connections.clear()
    }
}
