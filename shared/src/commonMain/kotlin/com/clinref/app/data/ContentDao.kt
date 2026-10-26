package com.clinref.app.data

import com.clinref.app.util.ZstdUtil
import com.clinref.shared.db.useQueryFirstOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ContentDao(
    private val dbManager: DatabaseManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** One retrievable graphic asset referenced by a topic (`subtype` is `graphic_table`, `graphic_picture`, ...). */
    data class GraphicMeta(
        val id: String,
        val title: String,
        val subtype: String
    ) {
        val isTable: Boolean get() = subtype == "graphic_table"
    }

    /** Cross-referenced topic linked from a topic payload. */
    data class TopicMeta(
        val id: String,
        val title: String
    )

    data class TopicContent(
        val bodyHtml: String,
        val outlineHtml: String = "",
        val title: String = "",
        /** Payload `topicInfo.type`: `drug`, `medical`, `calc`, or `narrative`. */
        val topicType: String = "",
        /** Structured `relatedGraphics` inventory — the only place a graphic's subtype is recorded. */
        val graphics: List<GraphicMeta> = emptyList(),
        /** Structured `relatedTopics` inventory. */
        val relatedTopics: List<TopicMeta> = emptyList(),
        val contributors: List<ContributorGroup>? = null
    )

    fun getTopicContent(topicId: String): TopicContent? = loadFromAssets(topicId)

    fun getTopicTitle(topicId: String): String? {
        val numericId = extractNumericId(topicId) ?: return null
        return try {
            val db = dbManager.getAssetsDb()
            val payloadBlob = db.useQueryFirstOrNull(
                "SELECT payload FROM topic_asset WHERE id = ? LIMIT 1",
                bind = { it.bindText(1, numericId.toString()) }
            ) { stmt -> stmt.getBlob(0) } ?: return null

            val payloadStr = ZstdUtil.decodePayload(payloadBlob)
            extractTitleFromJson(json.parseToJsonElement(payloadStr).jsonObject)
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTitleFromJson(jsonObj: JsonObject): String? {
        val topicInfo = jsonObj["topicInfo"]?.let {
            try { it.jsonObject } catch (_: Exception) { null }
        }
        var title = topicInfo?.get("title")?.jsonPrimitive?.content
            ?: jsonObj["title"]?.jsonPrimitive?.content
        if (title.isNullOrBlank()) {
            val translated = topicInfo?.get("translatedTopicInfos") as? JsonArray
            val enInfo = translated?.firstOrNull { item ->
                (item as? JsonObject)?.get("languageCode")?.jsonPrimitive?.content == "en-US"
            } as? JsonObject
            title = enInfo?.get("title")?.jsonPrimitive?.content
        }
        return title?.removeSurrounding("\"")?.trim()?.takeIf { it.isNotBlank() }
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
        return try {
            val db = dbManager.getAssetsDb()
            val payloadBlob = db.useQueryFirstOrNull(
                "SELECT payload FROM topic_asset WHERE id = ? LIMIT 1",
                bind = { it.bindText(1, numericId.toString()) }
            ) { stmt -> stmt.getBlob(0) } ?: return null

            val payloadStr = ZstdUtil.decodePayload(payloadBlob)
            val jsonObj = json.parseToJsonElement(payloadStr).jsonObject
            val topicInfo = jsonObj["topicInfo"]?.let {
                runCatching { it.jsonObject }.getOrNull()
            }

            TopicContent(
                bodyHtml = jsonObj.string("bodyHtml"),
                outlineHtml = jsonObj.string("outlineHtml"),
                title = extractTitleFromJson(jsonObj) ?: "",
                topicType = topicInfo?.string("type") ?: "",
                graphics = parseGraphics(jsonObj["relatedGraphics"]),
                relatedTopics = parseRelatedTopics(jsonObj["relatedTopics"]),
                contributors = jsonObj.contributors("contributors")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Flattens `relatedGraphics: [{headingTitle, graphics: [{graphicInfo: {...}}]}]` into a
     * single inventory, dropping assets hidden from results and the two Lexidrug appendix
     * tables that ship with almost every drug topic ("clinical abbreviations", "country
     * abbreviations") — they are reference boilerplate, never dosing content.
     */
    private fun parseGraphics(element: kotlinx.serialization.json.JsonElement?): List<GraphicMeta> {
        val groups = element as? JsonArray ?: return emptyList()
        val out = LinkedHashMap<String, GraphicMeta>()
        for (group in groups) {
            val graphics = (group as? JsonObject)?.get("graphics") as? JsonArray ?: continue
            for (entry in graphics) {
                val info = (entry as? JsonObject)?.get("graphicInfo") as? JsonObject ?: continue
                if (info.boolean("hideInResults")) continue
                val id = info.string("id")
                if (id.isEmpty() || id in BOILERPLATE_GRAPHIC_IDS) continue
                val title = info.string("displayName").ifEmpty {
                    entry.string("label").orEmpty()
                }
                out.putIfAbsent(id, GraphicMeta(id = id, title = title.ifEmpty { id }, subtype = info.string("subtype")))
            }
        }
        return out.values.toList()
    }

    private fun parseRelatedTopics(element: kotlinx.serialization.json.JsonElement?): List<TopicMeta> {
        val topics = (element as? JsonObject)?.get("topics") as? JsonArray ?: return emptyList()
        val out = LinkedHashMap<String, TopicMeta>()
        for (entry in topics) {
            val obj = entry as? JsonObject ?: continue
            if (obj.boolean("hideInResults")) continue
            val id = obj.string("id")
            if (id.isEmpty()) continue
            out.putIfAbsent(id, TopicMeta(id = id, title = obj.string("title").ifEmpty { id }))
        }
        return out.values.toList()
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content?.removeSurrounding("\"") ?: ""

    private fun JsonObject.boolean(key: String): Boolean =
        this[key]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true

    private fun JsonObject.contributors(key: String): List<ContributorGroup>? =
        this[key]?.let { json.decodeFromString(it.toString()) }

    private companion object {
        /** Lexidrug appendix tables attached to nearly every drug topic. */
        val BOILERPLATE_GRAPHIC_IDS = setOf("57469", "116421")
    }
}
