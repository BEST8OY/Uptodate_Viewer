package com.uptodate.viewer.data.repository

import android.database.sqlite.SQLiteDatabase
import com.uptodate.viewer.data.database.content.ContentRepository
import com.uptodate.viewer.data.database.content.models.TopicContent
import com.uptodate.viewer.data.database.content.models.TopicPayload
import com.uptodate.viewer.data.database.ext.blob
import com.uptodate.viewer.data.database.ext.string
import com.uptodate.viewer.data.decompress.ZstdDecompressor
import com.uptodate.viewer.data.database.DatabaseManager
import com.uptodate.viewer.util.TopicIdNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class ContentRepositoryImpl(
    private val dbManager: DatabaseManager
) : ContentRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getTopicContent(topicId: String): TopicContent? = withContext(Dispatchers.IO) {
        val tid = TopicIdNormalizer.extractNumeric(topicId) ?: return@withContext null

        // Primary: utdasset.sqlite topic_asset
        val assetsDb = dbManager.getAssetsDbOrNull()
        val fromAssets = assetsDb?.let { queryTopicAsset(it, tid) }
        if (fromAssets != null) return@withContext fromAssets

        // Fallback: fcontentsearch.db
        val fcsDb = try { dbManager.getFcontentsearchDb() } catch (_: Exception) { null }
        if (fcsDb != null) {
            val fb = queryFcontentSearch(fcsDb, topicId)
            if (fb != null) return@withContext fb
        }

        null
    }

    private fun queryTopicAsset(db: SQLiteDatabase, numericId: Int): TopicContent? {
        val cursor = db.rawQuery("SELECT payload FROM topic_asset WHERE id = ?", arrayOf(numericId.toString()))
        cursor.use {
            if (!it.moveToFirst()) return null
            val payload = it.blob("payload") ?: return null
            val jsonStr = if (ZstdDecompressor.isCompressed(payload)) {
                ZstdDecompressor.decompress(payload) ?: return null
            } else {
                String(payload, Charsets.UTF_8)
            }
            return try {
                val parsed = json.decodeFromString<TopicPayload>(jsonStr)
                val body = parsed.bodyHtml ?: return null
                TopicContent(
                    bodyHtml = body,
                    outlineHtml = parsed.outlineHtml,
                    relatedGraphics = parsed.relatedGraphics,
                    contributors = parsed.contributors
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun queryFcontentSearch(db: SQLiteDatabase, topicId: String): TopicContent? {
        val cursor = db.rawQuery(
            "SELECT Text FROM search WHERE URL = ?",
            arrayOf("topic-$topicId")
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            val text = it.string("Text") ?: return null
            return TopicContent(bodyHtml = text, outlineHtml = null, relatedGraphics = null, contributors = null)
        }
    }
}
