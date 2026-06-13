package com.uptodate.viewer.data.repository

import android.database.sqlite.SQLiteDatabase
import com.uptodate.viewer.data.database.ext.blob
import com.uptodate.viewer.data.database.ext.mapEach
import com.uptodate.viewer.data.database.ext.string
import com.uptodate.viewer.data.database.toc.TocRepository
import com.uptodate.viewer.data.database.toc.models.TocChildJson
import com.uptodate.viewer.data.database.toc.models.TocNode
import com.uptodate.viewer.data.database.toc.models.TocPayload
import com.uptodate.viewer.data.decompress.ZstdDecompressor
import com.uptodate.viewer.data.database.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class TocRepositoryImpl(
    private val dbManager: DatabaseManager
) : TocRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getRootItems(): List<TocNode> = withContext(Dispatchers.IO) {
        val db = dbManager.getAssetsDb()
        loadChildren(db, "RESOURCE/table_of_contents.json")
    }

    override suspend fun getChildItems(parentId: String): List<TocNode> = withContext(Dispatchers.IO) {
        val db = dbManager.getAssetsDb()
        val resourceId = if (parentId.startsWith("RESOURCE/")) parentId
        else "RESOURCE/${parentId}.json"
        loadChildren(db, resourceId)
    }

    private fun loadChildren(db: SQLiteDatabase, resourceId: String): List<TocNode> {
        val cursor = db.rawQuery("SELECT payload FROM other_asset WHERE id = ?", arrayOf(resourceId))
        cursor.use {
            if (!it.moveToFirst()) return emptyList()
            val payload = it.blob("payload") ?: return emptyList()
            val jsonStr = if (ZstdDecompressor.isCompressed(payload)) {
                ZstdDecompressor.decompress(payload) ?: return emptyList()
            } else {
                String(payload, Charsets.UTF_8)
            }
            return try {
                val parsed = json.decodeFromString<TocPayload>(jsonStr)
                parsed.childrenInfo.map { child ->
                    TocNode(
                        id = child.id,
                        title = child.title,
                        isLeaf = child.type == "TOPIC",
                        type = child.type,
                        childrenInfo = child.childrenInfo
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
