package com.clinref.app.data

import com.clinref.app.domain.ai.AiJsonUtils
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
        return title?.removeSurrounding("\"")?.let { AiJsonUtils.cleanHtmlText(it) }?.takeIf { it.isNotBlank() }
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

            val rawBodyHtml = jsonObj.string("bodyHtml")
            val finalBodyHtml = injectWhatsNewHtml(rawBodyHtml, jsonObj["whatsNewInfo"])

            TopicContent(
                bodyHtml = finalBodyHtml,
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
     * Injects What's New and Practice Changing Update (PCU) HTML cards into `#topicWhatsNewContainer`
     * in [bodyHtml]. This ensures:
     * 1. Readers see active clinical updates at the top of the article.
     * 2. TOC outline anchors (`PCU0`, `WhatsNew0`) scroll directly to their respective cards.
     * 3. [MedicalDatabaseTools.getTopicSectionsText] can extract update text using standard outline boundaries.
     */
    internal fun injectWhatsNewHtml(
        bodyHtml: String,
        whatsNewElement: kotlinx.serialization.json.JsonElement?
    ): String {
        val items = whatsNewElement as? JsonArray ?: return bodyHtml
        if (items.isEmpty()) return bodyHtml

        val pcus = mutableListOf<String>()
        val whatsNews = mutableListOf<String>()

        for (item in items) {
            val obj = item as? JsonObject ?: continue
            val html = obj.string("html")
            if (html.isBlank()) continue
            val isPcu = obj.boolean("pcu")
            if (isPcu) {
                pcus.add(html)
            } else {
                whatsNews.add(html)
            }
        }

        if (pcus.isEmpty() && whatsNews.isEmpty()) return bodyHtml

        val parts = StringBuilder()
        if (pcus.isNotEmpty()) {
            parts.append("""<div id="PCU0" class="utdCallout utdPCU"><div class="calloutBadge">PRACTICE CHANGING UPDATE</div>""")
            for (h in pcus) {
                parts.append(h)
            }
            parts.append("</div>")
        }
        if (whatsNews.isNotEmpty()) {
            parts.append("""<div id="WhatsNew0" class="utdCallout utdWhatsNew"><div class="calloutBadge">WHAT'S NEW</div>""")
            for (h in whatsNews) {
                parts.append(h)
            }
            parts.append("</div>")
        }

        val injected = parts.toString()
        return if (bodyHtml.contains("""<div id="topicWhatsNewContainer"></div>""")) {
            bodyHtml.replace(
                """<div id="topicWhatsNewContainer"></div>""",
                """<div id="topicWhatsNewContainer">$injected</div>"""
            )
        } else if (bodyHtml.contains("""id="topicWhatsNewContainer"""")) {
            Regex("""<div\s+[^>]*id=["']topicWhatsNewContainer["'][^>]*>(\s*</div>)?""", RegexOption.IGNORE_CASE)
                .replaceFirst(bodyHtml, """<div id="topicWhatsNewContainer">$injected</div>""")
        } else {
            """<div id="topicWhatsNewContainer">$injected</div>$bodyHtml"""
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
                val rawTitle = info.string("displayName").ifEmpty {
                    entry.string("label").orEmpty()
                }
                val title = AiJsonUtils.cleanHtmlText(rawTitle)
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
            val rawTitle = obj.string("title").ifEmpty { id }
            out.putIfAbsent(id, TopicMeta(id = id, title = AiJsonUtils.cleanHtmlText(rawTitle).ifEmpty { id }))
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
