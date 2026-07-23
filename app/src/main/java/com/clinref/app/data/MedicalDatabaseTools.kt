package com.clinref.app.data

import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MedTools"

@Singleton
class MedicalDatabaseTools @Inject constructor(
    private val searchRepository: SearchRepository,
    private val contentRepository: ContentRepository,
    private val assetRepository: AssetRepository
) : ToolSet {

    companion object {
        private val A_TAG_REGEX = Regex(
            """<a\s+[^>]*href=['"]([^'"]*)['"][^>]*>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val SECTION_REGEX = Regex(
            """section(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val STRIP_TAGS_REGEX = Regex("<[^>]*>")
        private val GRAPHIC_TYPE_REGEX = Regex(
            """type(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val GRAPHIC_SUBTYPE_REGEX = Regex(
            """subtype(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val GRAPHIC_ID_REGEX = Regex(
            """(?<![a-zA-Z])(?:id|graphicId)(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""",
            setOf(RegexOption.IGNORE_CASE)
        )
        private val GRAPHIC_ACTION_REGEX = Regex(
            """appAction\(([^)]*)\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Tool
    @LLMDescription(
        "Search medical topics by focused core keywords (e.g., 'apixaban', 'asthma', 'gout'). " +
        "Returns matching topics and 'refine_with' suggestions for follow-up query refining. " +
        "For multi-concept questions, execute separate searches per concept. " +
        "Avoid searching full patient sentences or lab measurements."
    )
    fun searchTopics(
        @LLMDescription("Single medical term or core clinical concept (e.g., 'asthma', 'metformin').") query: String
    ): String {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return json.encodeToString(mapOf("results" to emptyList<Map<String, String>>(), "refine_with" to emptyList<String>()))
        }

        val searchResults = searchRepository.searchTopics(cleanQuery)
        val results = searchResults.map { result ->
            val id = when (result) {
                is com.clinref.app.domain.SearchResult.Topic -> result.topicId
                is com.clinref.app.domain.SearchResult.Graphic -> result.graphicId
            }
            mapOf("id" to id, "title" to result.title)
        }

        val suggestions = searchRepository.getSuggestions(cleanQuery).distinct().take(20)

        val response = mapOf(
            "query" to cleanQuery,
            "results" to results,
            "refine_with" to suggestions,
            "message" to if (results.isEmpty()) "No direct topic match found. Consider refining query with 'refine_with' suggestions." else "Success"
        )

        return json.encodeToString(response)
    }

    @Tool
    @LLMDescription(
        "Retrieve the topic outline containing section IDs, titles, graphic metadata, and related topics. " +
        "ALWAYS call this after searchTopics to obtain exact sectionId values for getTopicSectionText."
    )
    fun getTopicOutline(
        @LLMDescription("The topic ID returned by searchTopics (e.g., '12345')") topicId: String
    ): String {
        val cleanTopicId = topicId.trim()
        val content = contentRepository.getTopicContent(cleanTopicId) ?: return "Topic not found: $cleanTopicId"
        val title = contentRepository.getTopicTitle(cleanTopicId) ?: cleanTopicId
        val outline = parseOutline(content.outlineHtml)

        return json.encodeToString(mapOf(
            "topicId" to cleanTopicId,
            "title" to title,
            "sections" to outline.sections,
            "graphics" to outline.graphics,
            "related_topics" to outline.relatedTopics
        ))
    }

    @Tool
    @LLMDescription(
        "Retrieve full markdown text content of a specific section. " +
        "Call this with sectionId retrieved from getTopicOutline."
    )
    fun getTopicSectionText(
        @LLMDescription("The topic ID") topicId: String,
        @LLMDescription("Exact section ID from getTopicOutline (e.g., 'H3', 'summary-and-recommendations')") sectionId: String,
        @LLMDescription("Fallback section title if ID lookup fails") sectionTitle: String = ""
    ): String {
        val cleanTopicId = topicId.trim()
        val cleanSectionId = sectionId.trim()
        val content = contentRepository.getTopicContent(cleanTopicId) ?: return "Topic not found"

        var targetId = cleanSectionId
        var sectionHtml = extractSectionHtml(content.bodyHtml, content.outlineHtml, targetId)

        if (sectionHtml == null && sectionTitle.isNotBlank()) {
            val outline = parseOutline(content.outlineHtml)
            val matched = outline.sections.firstOrNull {
                it["title"]?.contains(sectionTitle, ignoreCase = true) == true ||
                sectionTitle.contains(it["title"] ?: "_", ignoreCase = true)
            }
            if (matched != null) {
                val fallbackId = matched["id"]
                if (fallbackId != null) {
                    targetId = fallbackId
                    sectionHtml = extractSectionHtml(content.bodyHtml, content.outlineHtml, targetId)
                }
            }
        }

        if (sectionHtml == null) {
            return "Section not found."
        }

        val topicTitles = mutableMapOf<String, String>()
        val currentTitle = contentRepository.getTopicTitle(cleanTopicId)
        if (currentTitle != null) topicTitles[cleanTopicId] = currentTitle

        val markdown = htmlToMarkdown(sectionHtml) { tid ->
            topicTitles.getOrPut(tid) { contentRepository.getTopicTitle(tid) ?: tid }
        }

        return markdown
    }

    @Tool
    @LLMDescription(
        "Follow a related topic ID to inspect its outline and section structure."
    )
    fun followRelatedTopic(
        @LLMDescription("The topic ID from related_topics") topicId: String
    ): String {
        return getTopicOutline(topicId)
    }

    @Tool
    @LLMDescription(
        "Retrieve graphic content for a graphic of type 'graphic_table' as formatted Markdown. " +
        "Do NOT call for non-table graphics (figures, images, algorithms) as visual details cannot be analyzed."
    )
    fun getGraphicContent(
        @LLMDescription("The graphic ID from getTopicOutline (e.g., 'Graphic-12345' or '12345')") graphicId: String
    ): String {
        val cleanId = graphicId.trim()
        val rawGraphicId = cleanId.removePrefix("Graphic-").removePrefix("graphic-")

        val graphicData = assetRepository.getGraphic(rawGraphicId)
            ?: return json.encodeToString(mapOf("error" to "Graphic $cleanId not found"))

        if (graphicData.subtype.isNotEmpty() && !graphicData.isTable) {
            return json.encodeToString(mapOf("error" to "Graphic $cleanId is of type '${graphicData.subtype}', not a table. Only table content is retrievable."))
        }

        val imageHtml = graphicData.imageHtml
        if (imageHtml.isBlank()) {
            return json.encodeToString(mapOf("error" to "Graphic $cleanId has empty content"))
        }

        val markdown = htmlToMarkdown(imageHtml)
        val title = graphicData.title.ifEmpty { rawGraphicId }
        return "### Graphic Table: $title\n\n$markdown"
    }

    private data class OutlineResult(
        val sections: List<Map<String, String>>,
        val graphics: List<Map<String, Any>>,
        val relatedTopics: List<Map<String, String>>
    )

    private fun parseOutline(outlineHtml: String): OutlineResult {
        val sections = mutableListOf<Map<String, String>>()
        val graphics = mutableListOf<Map<String, Any>>()
        val related = mutableListOf<Map<String, String>>()

        for (match in A_TAG_REGEX.findAll(outlineHtml)) {
            val href = match.groupValues[1]
            val innerHtml = match.groupValues[2]
            val text = innerHtml.replace(STRIP_TAGS_REGEX, "").trim()

            val sectionMatch = SECTION_REGEX.find(href)
            val typeMatch = GRAPHIC_TYPE_REGEX.find(href)

            when {
                sectionMatch != null -> {
                    if (text.isNotEmpty()) {
                        sections.add(mapOf("id" to sectionMatch.groupValues[1], "title" to text))
                    }
                }
                typeMatch != null && typeMatch.groupValues[1] == "graphic" -> {
                    val id = GRAPHIC_ID_REGEX.find(href)?.groupValues?.get(1) ?: ""
                    val subtype = GRAPHIC_SUBTYPE_REGEX.find(href)?.groupValues?.get(1) ?: ""
                    if (id.isNotEmpty()) {
                        graphics.add(mapOf(
                            "id" to id,
                            "type" to subtype,
                            "title" to text,
                            "isTable" to (subtype == "graphic_table").toString()
                        ))
                    }
                }
                else -> {
                    val id = GRAPHIC_ID_REGEX.find(href)?.groupValues?.get(1) ?: ""
                    if (text.isNotEmpty() && id.isNotEmpty()) {
                        related.add(mapOf("topicId" to id, "title" to text))
                    }
                }
            }
        }

        return OutlineResult(sections, graphics, related)
    }

    private fun extractSectionHtml(bodyHtml: String, outlineHtml: String, sectionId: String): String? {
        val escapedId = Regex.escape(sectionId)
        val startTagRegex = Regex("""<\w+\s+[^>]*id=["']${escapedId}["'][^>]*>""", setOf(RegexOption.IGNORE_CASE))
        val startMatch = startTagRegex.find(bodyHtml) ?: return null
        val startIdx = startMatch.range.first

        val outline = parseOutline(outlineHtml).sections
        val currentIndex = outline.indexOfFirst { it["id"] == sectionId }

        var nextMatchIdx = -1
        if (currentIndex != -1 && currentIndex < outline.size - 1) {
            for (nextIdx in (currentIndex + 1) until outline.size) {
                val nextSectionId = outline[nextIdx]["id"] ?: continue
                val nextEscapedId = Regex.escape(nextSectionId)
                val nextStartTagRegex = Regex("""<\w+\s+[^>]*id=["']${nextEscapedId}["'][^>]*>""", setOf(RegexOption.IGNORE_CASE))
                val nextMatch = nextStartTagRegex.find(bodyHtml, startIndex = startMatch.range.last + 1)
                if (nextMatch != null) {
                    nextMatchIdx = nextMatch.range.first
                    break
                }
            }
        }

        return if (nextMatchIdx != -1) {
            bodyHtml.substring(startIdx, nextMatchIdx)
        } else {
            val referenceHeaderRegex = Regex(
                """<\w+\s+[^>]*(?:id=["']references["'])[^>]*>""",
                setOf(RegexOption.IGNORE_CASE)
            )
            val referencesMatch = referenceHeaderRegex.find(bodyHtml, startIndex = startMatch.range.last + 1)
            if (referencesMatch != null) {
                bodyHtml.substring(startIdx, referencesMatch.range.first)
            } else {
                bodyHtml.substring(startIdx)
            }
        }
    }

    private fun htmlToMarkdown(html: String, titleLookup: (String) -> String = { it }): String {
        var s = html

        s = A_TAG_REGEX.replace(s) { match ->
            val href = match.groupValues[1]
            val rawText = match.groupValues[2].replace(STRIP_TAGS_REGEX, "").trim()
            if (rawText.isEmpty()) return@replace ""

            val actionMatch = GRAPHIC_ACTION_REGEX.find(href)
            if (actionMatch != null) {
                val jsonStr = actionMatch.groupValues[1]
                    .replace("&quot;", "\"").replace("&#39;", "'")
                when {
                    jsonStr.contains("\"graphic\"") && jsonStr.contains("\"type\":\"graphic\"") -> {
                        val idMatch = GRAPHIC_ID_REGEX.find(jsonStr)
                        val graphicId = idMatch?.groupValues?.get(1)
                        if (graphicId != null) {
                            return@replace "[$rawText](Graphic-$graphicId)"
                        }
                    }
                    jsonStr.contains("\"type\":\"medical\"") || jsonStr.contains("\"type\":\"drug\"") -> {
                        val idMatch = GRAPHIC_ID_REGEX.find(jsonStr)
                        val topicId = idMatch?.groupValues?.get(1)
                        if (topicId != null) {
                            val sectionMatch = SECTION_REGEX.find(jsonStr)
                            val sectionId = sectionMatch?.groupValues?.get(1)
                            val ref = if (sectionId != null) "$topicId#$sectionId" else topicId
                            val displayTitle = titleLookup(topicId)
                            return@replace "[$displayTitle](Topic-$ref)"
                        }
                    }
                }
            }
            rawText
        }

        s = convertHtmlTablesToMarkdown(s)

        s = s.replace(Regex("</?h[1-6]\\b[^>]*>", RegexOption.IGNORE_CASE), "\n\n### ")
        s = s.replace(Regex("<[uo]l\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</[uo]l>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
        s = s.replace(Regex("</li>", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("</?p\\b[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</?div\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")

        s = s.replace(Regex("</?(?:strong|b)\\b[^>]*>", RegexOption.IGNORE_CASE), "**")
        s = s.replace(Regex("</?(?:em|i)\\b[^>]*>", RegexOption.IGNORE_CASE), "*")

        s = s.replace(STRIP_TAGS_REGEX, "")

        s = s.replace("&#160;", " ").replace("&nbsp;", " ")
        s = s.replace("&#8212;", "\u2014").replace("&mdash;", "\u2014")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

        val lines = s.split("\n").map { it.trim() }
        val cleanedLines = mutableListOf<String>()
        for (line in lines) {
            if (line.isNotEmpty()) {
                cleanedLines.add(line)
            } else if (cleanedLines.isNotEmpty() && cleanedLines.last().isNotEmpty()) {
                cleanedLines.add("")
            }
        }
        return cleanedLines.joinToString("\n").trim()
    }

    private fun convertHtmlTablesToMarkdown(html: String): String {
        val tableRegex = Regex("""<table\b[^>]*>(.*?)</table>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val trRegex = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val tdThRegex = Regex("""<(?:td|th)\b[^>]*>(.*?)</(?:td|th)>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

        return tableRegex.replace(html) { match ->
            val tableBody = match.groupValues[1]
            val rows = trRegex.findAll(tableBody).map { trMatch ->
                tdThRegex.findAll(trMatch.groupValues[1]).map { cellMatch ->
                    cellMatch.groupValues[1].replace(STRIP_TAGS_REGEX, "").trim()
                }.toList()
            }.filter { it.isNotEmpty() }.toList()

            if (rows.isEmpty()) return@replace ""

            val sb = java.lang.StringBuilder("\n\n")
            val maxCols = rows.maxOf { it.size }
            
            val header = rows.first()
            sb.append("| ").append(header.padTo(maxCols, "").joinToString(" | ")).append(" |\n")
            
            val sep = List(maxCols) { "---" }
            sb.append("| ").append(sep.joinToString(" | ")).append(" |\n")

            for (row in rows.drop(1)) {
                sb.append("| ").append(row.padTo(maxCols, "").joinToString(" | ")).append(" |\n")
            }
            sb.append("\n")
            sb.toString()
        }
    }

    private fun <T> List<T>.padTo(size: Int, default: T): List<T> {
        if (this.size >= size) return this
        return this + List(size - this.size) { default }
    }
}
