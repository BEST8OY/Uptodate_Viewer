package com.clinref.app.domain.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared JSON unwrapping, regex compilation, and string sanitization utilities
 * for the AI domain layer (TurnContextAccumulator, StreamingManager).
 */
object AiJsonUtils {

    val json = Json { ignoreUnknownKeys = true }

    val GRAPHIC_PREFIX_REGEX = Regex("(?i)^graphic-")
    val GRAPHIC_TABLE_REGEX = Regex("""### Graphic Table:\s*(.+)""")
    val WHITESPACE_REGEX = Regex("""\s+""")
    private val DASH_PREFIX_CHARS = charArrayOf('-', '–', '—', ' ')

    /**
     * Unwraps a string if it was serialized as an escaped JSON string primitive
     * (e.g., "\"{\\\"key\\\":...}\"" from Koog string tool results).
     */
    fun extractJsonString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length >= 2) {
            try {
                val parsed = json.parseToJsonElement(trimmed)
                if (parsed is JsonPrimitive && parsed.isString) {
                    return parsed.content
                }
            } catch (_: Exception) {}
        }
        return trimmed
    }

    /**
     * Parses a string into a [JsonObject], unboxing any layers of string primitive escaping.
     */
    fun parseAsJsonObject(raw: String): JsonObject? {
        val cleaned = extractJsonString(raw)
        if (cleaned.isEmpty()) return null
        return try {
            var element = json.parseToJsonElement(cleaned)
            while (element is JsonPrimitive && element.isString) {
                val inner = element.content.trim()
                if (inner.startsWith("{")) {
                    element = json.parseToJsonElement(inner)
                } else break
            }
            element as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Normalizes a graphic ID by stripping any leading 'graphic-' prefix.
     */
    fun cleanGraphicId(id: String): String = id.trim().replace(GRAPHIC_PREFIX_REGEX, "")

    /**
     * Cleans an outline section title by removing leading dashes, hyphens, and whitespace.
     */
    fun cleanSectionTitle(title: String): String = title.trimStart(*DASH_PREFIX_CHARS).trim()

    /**
     * Checks if a string consists entirely of digits (e.g., bare topic ID used as title).
     */
    fun isNumericOnly(text: String): Boolean = text.isNotBlank() && text.all { it.isDigit() }

    /**
     * Converts tool names in any naming convention (snake_case or camelCase)
     * to canonical camelCase.
     */
    fun normalizeToolName(rawName: String): String = when (rawName) {
        "search_topics", "searchTopics" -> "searchTopics"
        "get_topic_outline", "getTopicOutline" -> "getTopicOutline"
        "get_related_topics", "getRelatedTopics" -> "getRelatedTopics"
        "get_topic_sections_text", "getTopicSectionsText" -> "getTopicSectionsText"
        "get_graphic_content", "getGraphicContent" -> "getGraphicContent"
        else -> rawName
    }

    /**
     * Normalizes tool argument keys to canonical camelCase.
     */
    fun normalizeArgs(rawArgs: Map<String, String>): Map<String, String> {
        if (rawArgs.isEmpty()) return emptyMap()
        val normalized = mutableMapOf<String, String>()
        for ((key, value) in rawArgs) {
            val canonicalKey = when (key) {
                "topic_id" -> "topicId"
                "section_id" -> "sectionId"
                "section_ids" -> "sectionIds"
                "graphic_id" -> "graphicId"
                "answer_text" -> "answerText"
                "no_data_found" -> "noDataFound"
                "section_title" -> "sectionTitle"
                else -> key
            }
            normalized[canonicalKey] = value
        }
        return normalized
    }
}
