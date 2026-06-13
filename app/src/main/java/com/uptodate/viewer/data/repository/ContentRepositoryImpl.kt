package com.uptodate.viewer.data.repository

import android.database.sqlite.SQLiteDatabase
import android.util.Log
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
        Log.i("Content", "getTopicContent: topicId=$topicId")
        val tid = TopicIdNormalizer.extractNumeric(topicId)
        Log.i("Content", "extractNumeric: tid=$tid")
        if (tid == null) return@withContext null

        val assetsDb = dbManager.getAssetsDbOrNull()
        Log.i("Content", "assetsDb=${assetsDb != null}")
        val fromAssets = assetsDb?.let { queryTopicAsset(it, tid) }
        if (fromAssets != null) {
            Log.i("Content", "found from topic_asset")
            return@withContext fromAssets
        }

        val fcsDb = try { dbManager.getFcontentsearchDb() } catch (_: Exception) { null }
        Log.i("Content", "fcsDb=${fcsDb != null}")
        if (fcsDb != null) {
            val fb = queryFcontentSearch(fcsDb, topicId)
            if (fb != null) {
                Log.i("Content", "found from fcontentsearch")
                return@withContext fb
            }
        }

        Log.w("Content", "NOT FOUND for topicId=$topicId, tid=$tid")
        null
    }

    private fun queryTopicAsset(db: SQLiteDatabase, numericId: Int): TopicContent? {
        val cursor = db.rawQuery("SELECT payload FROM topic_asset WHERE id = ?", arrayOf(numericId.toString()))
        cursor.use {
            if (!it.moveToFirst()) {
                Log.w("Content", "queryTopicAsset: no row for id=$numericId")
                return null
            }
            val payload = it.blob("payload")
            if (payload == null) {
                Log.w("Content", "queryTopicAsset: payload is null")
                return null
            }
            Log.i("Content", "queryTopicAsset: payload=${payload.size} bytes, compressed=${ZstdDecompressor.isCompressed(payload)}")
            val jsonStr = if (ZstdDecompressor.isCompressed(payload)) {
                ZstdDecompressor.decompress(payload)
            } else {
                String(payload, Charsets.UTF_8)
            }
            if (jsonStr == null) {
                Log.e("Content", "queryTopicAsset: decompression failed")
                return null
            }
            Log.i("Content", "queryTopicAsset: jsonLen=${jsonStr.length}, first200=${jsonStr.take(200)}")
            return try {
                val parsed = json.decodeFromString<TopicPayload>(jsonStr)
                val body = parsed.bodyHtml
                if (body == null) {
                    Log.w("Content", "queryTopicAsset: bodyHtml is null")
                    return null
                }
                TopicContent(
                    bodyHtml = body,
                    outlineHtml = parsed.outlineHtml,
                    relatedGraphics = parsed.relatedGraphics,
                    contributors = parsed.contributors
                )
            } catch (e: Exception) {
                Log.e("Content", "queryTopicAsset: JSON parse failed", e)
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
            if (!it.moveToFirst()) {
                Log.w("Content", "queryFcontentSearch: no row for URL=topic-$topicId")
                return null
            }
            val text = it.string("Text") ?: return null
            return TopicContent(bodyHtml = text, outlineHtml = null, relatedGraphics = null, contributors = null)
        }
    }
}
