package com.uptodate.viewer.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Environment
import com.uptodate.viewer.util.DbFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class DatabaseManager @Inject constructor(
    @param:ApplicationContext val context: Context
) {

    private val connections = mutableMapOf<String, SQLiteDatabase>()
    private var databaseDir: File? = null

    fun configureFromDefaultPath(): Boolean {
        val dir = File(Environment.getExternalStorageDirectory(), "UptodateDB")
        return configureFromFile(dir)
    }

    fun configureFromFile(dir: File): Boolean {
        if (!dir.isDirectory) return false
        closeAll()
        databaseDir = dir
        return validateDatabases()
    }

    private fun validateDatabases(): Boolean {
        val dir = databaseDir ?: return false
        return DbFiles.ALL.all { name -> File(dir, name).exists() }
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
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).also { connections[dbName] = it }
        } catch (_: Exception) {
            null
        }
    }

    fun hasDatabases(): Boolean = databaseDir != null && validateDatabases()

    fun closeAll() {
        connections.values.forEach { it.close() }
        connections.clear()
    }
}
