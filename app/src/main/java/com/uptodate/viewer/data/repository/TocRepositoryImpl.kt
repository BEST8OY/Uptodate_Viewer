package com.uptodate.viewer.data.repository

import android.database.sqlite.SQLiteDatabase
import android.util.Log
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

private const val TAG = "TocRepository"

class TocRepositoryImpl(
    private val dbManager: DatabaseManager
) : TocRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getRootItems(): List<TocNode> = withContext(Dispatchers.IO) {
        val db = dbManager.getAssetsDbOrNull()
        if (db == null) {
            Log.e(TAG, "getRootItems: assetsDb is null")
            return@withContext emptyList()
        }
        Log.i(TAG, "getRootItems: querying other_asset for RESOURCE/table_of_contents.json")
        loadChildren(db, "RESOURCE/table_of_contents.json")
    }

    override suspend fun getChildItems(parentId: String): List<TocNode> = withContext(Dispatchers.IO) {
        val db = dbManager.getAssetsDbOrNull() ?: return@withContext emptyList()
        val resourceId = if (parentId.startsWith("RESOURCE/")) parentId
        else "RESOURCE/${parentId}.json"
        loadChildren(db, resourceId)
    }

    private fun loadChildren(db: SQLiteDatabase, resourceId: String): List<TocNode> {
        Log.i(TAG, "loadChildren: resourceId=$resourceId")
        val cursor = db.rawQuery("SELECT payload FROM other_asset WHERE id = ?", arrayOf(resourceId))
        cursor.use {
            if (!it.moveToFirst()) {
                Log.w(TAG, "loadChildren: no row found for $resourceId")
                return emptyList()
            }
            val payload = it.blob("payload")
            if (payload == null) {
                Log.w(TAG, "loadChildren: payload is null for $resourceId")
                return emptyList()
            }
            Log.i(TAG, "loadChildren: payload size=${payload.size}, isCompressed=${ZstdDecompressor.isCompressed(payload)}")
            val jsonStr = if (ZstdDecompressor.isCompressed(payload)) {
                val decompressed = ZstdDecompressor.decompress(payload)
                if (decompressed == null) {
                    Log.e(TAG, "loadChildren: ZSTD decompression FAILED for $resourceId")
                    return emptyList()
                }
                decompressed
            } else {
                String(payload, Charsets.UTF_8)
            }
            Log.i(TAG, "loadChildren: jsonStr length=${jsonStr.length}, first200=${jsonStr.take(200)}")
            return try {
                val parsed = json.decodeFromString<TocPayload>(jsonStr)
                val nodes = parsed.childrenInfo.map { child ->
                    TocNode(
                        id = child.id,
                        title = child.title,
                        isLeaf = child.type == "TOPIC",
                        type = child.type,
                        childrenInfo = child.childrenInfo
                    )
                }
                Log.i(TAG, "loadChildren: parsed ${nodes.size} nodes")
                nodes
            } catch (e: Exception) {
                Log.e(TAG, "loadChildren: JSON parse FAILED for $resourceId", e)
                emptyList()
            }
        }
    }
}
