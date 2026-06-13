package com.uptodate.viewer.data.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.uptodate.viewer.util.DbFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

class DatabaseManager @Inject constructor(
    @param:ApplicationContext val context: Context
) {

    private val connections = mutableMapOf<String, SQLiteDatabase>()
    private var databaseDir: File? = null
    private var databaseUri: Uri? = null

    fun configureFromUri(uri: Uri): Boolean {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return false
        }
        val filePath = resolveFilePath(docId)
        if (filePath != null && filePath.isDirectory) {
            closeAll()
            databaseDir = filePath
            databaseUri = uri
            return validateDatabases()
        }
        return false
    }

    fun configureFromFile(dir: File): Boolean {
        if (!dir.isDirectory) return false
        closeAll()
        databaseDir = dir
        databaseUri = null
        return validateDatabases()
    }

    private fun resolveFilePath(docId: String): File? {
        val parts = docId.split(':', limit = 2)
        if (parts.size != 2) return null
        val volume = parts[0]
        val relativePath = parts[1]

        val basePath = when (volume) {
            "primary", "external" -> {
                @Suppress("DEPRECATION")
                Environment.getExternalStorageDirectory()
            }
            else -> {
                val storageRoot = File("/storage")
                if (storageRoot.exists()) {
                    val volumeDir = File(storageRoot, volume)
                    if (volumeDir.exists()) volumeDir else null
                } else null
            }
        } ?: return null

        val candidate = File(basePath, relativePath)
        return if (candidate.exists() && candidate.isDirectory) candidate else null
    }

    private fun validateDatabases(): Boolean {
        val dir = databaseDir ?: return false
        return DbFiles.ALL.all { name -> File(dir, name).exists() }
    }

    fun getDatabaseDir(): File? = databaseDir
    fun getDatabaseUri(): Uri? = databaseUri

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
