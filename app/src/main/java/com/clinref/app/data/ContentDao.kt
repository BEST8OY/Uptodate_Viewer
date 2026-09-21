package com.clinref.app.data

import com.clinref.app.util.ZstdUtil
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
            ?: TopicContent(bodyHtml = "<h1>Content not found</h1><p>Could not retrieve content for this topic.</p>")
    }

    fun getTopicTitle(topicId: String): String? {
        val numericId = extractNumericId(topicId) ?: return null
        return try {
            val db = dbManager.getAssetsDb()
            db.rawQuery("SELECT payload FROM topic_asset WHERE id = ? LIMIT 1", arrayOf(numericId.toString())).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val payloadStr = ZstdUtil.decodePayload(cursor.getBlob(0))
                val jsonObj = json.parseToJsonElement(payloadStr).jsonObject
                val topicInfo = jsonObj["topicInfo"]?.let {
                    try { it.jsonObject } catch (_: Exception) { null }
                }
                var title = topicInfo?.get("title")?.jsonPrimitive?.content
                    ?: jsonObj["title"]?.jsonPrimitive?.content
                if (title.isNullOrBlank()) {
                    val translated = topicInfo?.get("translatedTopicInfos") as? kotlinx.serialization.json.JsonArray
                    val enInfo = translated?.firstOrNull { item ->
                        (item as? kotlinx.serialization.json.JsonObject)?.get("languageCode")?.jsonPrimitive?.content == "en-US"
                    } as? kotlinx.serialization.json.JsonObject
                    title = enInfo?.get("title")?.jsonPrimitive?.content
                }
                title?.removeSurrounding("\"")?.trim()?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractNumericId(topicId: String): Int? {
        return Regex("""^(?:topic-)?(\d+)$""", RegexOption.IGNORE_CASE)
            .find(topicId.trim())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    private fun loadFromAssets(topicId: String): TopicContent? {
        val numericId = extractNumericId(topicId) ?: return null
        val db = dbManager.getAssetsDb()

        return db.rawQuery("SELECT payload FROM topic_asset WHERE id = ?", arrayOf(numericId.toString())).use { cursor ->
            if (!cursor.moveToFirst()) return null

            val payloadStr = ZstdUtil.decodePayload(cursor.getBlob(0))
            val jsonObj = json.parseToJsonElement(payloadStr).jsonObject

            TopicContent(
                bodyHtml = jsonObj.string("bodyHtml"),
                outlineHtml = jsonObj.string("outlineHtml"),
                contributors = jsonObj.contributors("contributors")
            )
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content?.removeSurrounding("\"") ?: ""

    private fun JsonObject.contributors(key: String): List<ContributorGroup>? =
        this[key]?.let { json.decodeFromString(it.toString()) }
}
