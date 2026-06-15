package com.uptodate.viewer.data

import com.uptodate.viewer.util.GzipUtil
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentDao @Inject constructor(
    private val dbManager: DatabaseManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class TopicContent(
        val bodyHtml: String,
        val outlineHtml: String = "",
        val relatedGraphics: List<Map<String, Any?>> = emptyList(),
        val contributors: List<ContributorGroup>? = null
    )

    fun getTopicContent(topicId: String): TopicContent? {
        return loadFromAssets(topicId)
            ?: loadFromFcontentsearch(topicId)
            ?: TopicContent(bodyHtml = "<h1>Content not found</h1><p>Could not retrieve content for this topic.</p>")
    }

    private fun extractNumericId(topicId: String): Int? {
        return Regex("""^(?:topic-)?(\d+)$""", RegexOption.IGNORE_CASE)
            .find(topicId)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    private fun loadFromAssets(topicId: String): TopicContent? {
        val numericId = extractNumericId(topicId) ?: return null
        val db = dbManager.getAssetsDb()

        return db.rawQuery("SELECT payload FROM topic_asset WHERE id = ?", arrayOf(numericId.toString())).use { cursor ->
            if (!cursor.moveToFirst()) return null

            val payloadStr = GzipUtil.decodePayload(cursor.getBlob(0))
            val jsonObj = json.parseToJsonElement(payloadStr).jsonObject

            TopicContent(
                bodyHtml = jsonObj.string("bodyHtml"),
                outlineHtml = jsonObj.string("outlineHtml"),
                contributors = jsonObj.contributors("contributors")
            )
        }
    }

    private fun loadFromFcontentsearch(topicId: String): TopicContent? {
        val db = dbManager.getFcontentsearchDb()

        val result = queryFcontentsearch(db, "topic-$topicId")
            ?: queryFcontentsearch(db, topicId)
            ?: return null

        return TopicContent(bodyHtml = result)
    }

    private fun queryFcontentsearch(db: android.database.sqlite.SQLiteDatabase, url: String): String? {
        return db.rawQuery("SELECT Text FROM search WHERE URL = ?", arrayOf(url)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content?.removeSurrounding("\"") ?: ""

    private fun JsonObject.contributors(key: String): List<ContributorGroup>? =
        this[key]?.let { json.decodeFromString(it.toString()) }
}
