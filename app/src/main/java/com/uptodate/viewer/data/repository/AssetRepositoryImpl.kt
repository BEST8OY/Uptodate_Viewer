package com.uptodate.viewer.data.repository

import com.uptodate.viewer.data.database.assets.AssetRepository
import com.uptodate.viewer.data.database.assets.models.GraphicPayload
import com.uptodate.viewer.data.database.ext.blob
import com.uptodate.viewer.data.decompress.ZstdDecompressor
import com.uptodate.viewer.data.database.DatabaseManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class AssetRepositoryImpl(
    private val dbManager: DatabaseManager
) : AssetRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getGraphic(graphicId: String): GraphicPayload? = withContext(Dispatchers.IO) {
        val db = dbManager.getAssetsDbOrNull() ?: return@withContext null
        val cursor = db.rawQuery("SELECT payload FROM graphic_asset WHERE id = ?", arrayOf(graphicId))
        cursor.use {
            if (!it.moveToFirst()) return@withContext null
            val payload = it.blob("payload") ?: return@withContext null
            val jsonStr = if (ZstdDecompressor.isCompressed(payload)) {
                ZstdDecompressor.decompress(payload) ?: return@withContext null
            } else {
                String(payload, Charsets.UTF_8)
            }
            try {
                json.decodeFromString<GraphicPayload>(jsonStr)
            } catch (e: Exception) {
                null
            }
        }
    }
}
