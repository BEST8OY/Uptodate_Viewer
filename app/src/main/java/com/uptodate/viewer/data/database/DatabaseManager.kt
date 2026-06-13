package com.uptodate.viewer.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import android.util.Log
import com.uptodate.viewer.util.DbFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

private const val TAG = "DatabaseManager"

class DatabaseManager @Inject constructor(
    @param:ApplicationContext val context: Context
) {

    private val connections = mutableMapOf<String, SQLiteDatabase>()
    private var databaseDir: File? = null

    fun configureFromDefaultPath(): Boolean {
        if (databaseDir != null) return hasDatabases()
        val dir = File(Environment.getExternalStorageDirectory(), "UptodateDB")
        Log.i(TAG, "configureFromDefaultPath: ${dir.absolutePath}, exists=${dir.exists()}, isDir=${dir.isDirectory}")
        if (dir.exists()) {
            dir.listFiles()?.forEach { Log.i(TAG, "  file: ${it.name} (${it.length()} bytes)") }
        }
        return configureFromFile(dir)
    }

    fun configureFromFile(dir: File): Boolean {
        if (!dir.isDirectory) {
            Log.w(TAG, "configureFromFile: ${dir.absolutePath} is not a directory")
            return false
        }
        closeAll()
        databaseDir = dir
        val valid = validateDatabases()
        Log.i(TAG, "configureFromFile: valid=$valid, dir=${dir.absolutePath}")
        return valid
    }

    private fun validateDatabases(): Boolean {
        val dir = databaseDir ?: return false
        val allPresent = DbFiles.ALL.all { name ->
            val exists = File(dir, name).exists()
            if (!exists) Log.w(TAG, "validateDatabases: MISSING $name")
            exists
        }
        return allPresent
    }

    fun getDatabaseDir(): File? = databaseDir

    fun getAssetsDb(): SQLiteDatabase = getOrOpen(DbFiles.ASSETS)
    fun getUnidexDb(): SQLiteDatabase = getOrOpen(DbFiles.UNIDEX)
    fun getFsearchDb(): SQLiteDatabase = getOrOpen(DbFiles.FSEARCH)
    fun getFcontentsearchDb(): SQLiteDatabase = getOrOpen(DbFiles.FCONTENTSEARCH)
    fun getQfDb(): SQLiteDatabase = getOrOpen(DbFiles.QF)
    fun getTocDb(): SQLiteDatabase = getOrOpen(DbFiles.TOC)

    fun getAssetsDbOrNull(): SQLiteDatabase? = getOrNull(DbFiles.ASSETS)
    fun getUnidexDbOrNull(): SQLiteDatabase? = getOrNull(DbFiles.UNIDEX)
    fun getFsearchDbOrNull(): SQLiteDatabase? = getOrNull(DbFiles.FSEARCH)
    fun getFcontentsearchDbOrNull(): SQLiteDatabase? = getOrNull(DbFiles.FCONTENTSEARCH)

    private fun getOrOpen(dbName: String): SQLiteDatabase {
        return getOrNull(dbName) ?: error("Database not found: $dbName")
    }

    private fun getOrNull(dbName: String): SQLiteDatabase? {
        connections[dbName]?.let { return it }
        val dir = databaseDir ?: return null
        val file = File(dir, dbName)
        if (!file.exists()) {
            Log.w(TAG, "getOrNull: $dbName does not exist at ${file.absolutePath}")
            return null
        }
        return try {
            Log.i(TAG, "getOrNull: opening $dbName (${file.length()} bytes)")
            val db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
            connections[dbName] = db
            db
        } catch (e: Exception) {
            Log.e(TAG, "getOrNull: FAILED to open $dbName", e)
            null
        }
    }

    fun hasDatabases(): Boolean = databaseDir != null && validateDatabases()

    fun closeAll() {
        connections.values.forEach { it.close() }
        connections.clear()
    }
}
