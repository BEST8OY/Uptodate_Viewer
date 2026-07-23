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
        private val HTML_P_TAG = Regex("</?p\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_LI_TAG = Regex("<li\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_LI_CLOSE = Regex("</li>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_BR_TAG = Regex("<br\\s*/?>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_TR_TAG = Regex("</?tr\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_TD_TH_TAG = Regex("</?t[dh]\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_STRONG_TAG = Regex("</?strong\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_B_TAG = Regex("</?b\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_EM_TAG = Regex("</?em\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_I_TAG = Regex("</?i\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_HEADING_TAG = Regex("</?h[1-6]\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_DIV_OPEN = Regex("<div\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_BLOCK_CLOSE = Regex("</(?:div|table|thead|tbody|ul|ol)>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_TABLE_OPEN = Regex("<table\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val HTML_LIST_OPEN = Regex("<[uo]l\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
        private val GRAPHIC_ACTION_REGEX = Regex(
            """appAction\(([^)]*)\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
    }

    @Tool
    @LLMDescription(
        "Search medical topics by focused keywords. " +
        "USE: Start with core terms (e.g., 'apixaban', 'asthma', 'gout'). " +
        "The response includes 'refine_with' suggestions — pick the most relevant and search again. " +
        "For multi-concept questions (drug + condition), search each concept separately. " +
        "NEVER use full sentences, lab values, or patient demographics in search."
    )
    fun searchTopics(
        @LLMDescription("Single core medical term (e.g., 'asthma', 'metformin', 'chest pain').") query: String
    ): String {
        val results = searchRepository.searchTopics(query).map { result ->
            val id = when (result) {
                is com.clinref.app.domain.SearchResult.Topic -> result.topicId
                is com.clinref.app.domain.SearchResult.Graphic -> result.graphicId
            }
            mapOf("id" to id, "title" to result.title)
        }

        val suggestions = searchRepository.getSuggestions(query).distinct().take(30)

        // Tool-level validation: if no results, check if query is valid
        if (results.isEmpty()) {
            val queryLower = query.lowercase().trim()
            val suggestionTexts = suggestions.map { it.lowercase() }

            val isValid = suggestionTexts.any { s ->
                queryLower == s || s.startsWith(queryLower) || queryLower.startsWith(s)
            }

            if (!isValid && suggestions.isNotEmpty()) {
                // Query is invented — reject and show suggestions
                val errorResponse = mapOf(
                    "error" to "'$query' is not a valid search. Use one of these suggested queries:",
                    "refine_with" to suggestions.take(10)
                )
                return Json.encodeToString(errorResponse)
            }
        }

        val response = mapOf(
            "results" to results,
            "refine_with" to suggestions
        )

        return Json.encodeToString(response)
    }

    @Tool
    @LLMDescription(
        "Retrieve the outline for a topic. Returns sections, graphics, and related topics. " +
        "USE: Call after searchTopics to get section IDs for getTopicSectionText. " +
        "Also returns related_topics for further exploration with followRelatedTopic."
    )
    fun getTopicOutline(
        @LLMDescription("The unique topic ID returned by searchTopics") topicId: String
    ): String {
        val t0 = System.currentTimeMillis()
        val content = contentRepository.getTopicContent(topicId) ?: return "Topic not found"
        val t1 = System.currentTimeMillis()
        val title = contentRepository.getTopicTitle(topicId) ?: topicId
        val sections = parseOutlineList(content.outlineHtml)
        val graphics = parseGraphicsFromOutline(content.outlineHtml)
        val related = parseRelatedTopics(content.outlineHtml)
        val t2 = System.currentTimeMillis()
        Log.d(TAG, "getTopicOutline($topicId): db=${t1 - t0}ms parse=${t2 - t1}ms sections=${sections.size} graphics=${graphics.size} related=${related.size}")
        return Json.encodeToString(mapOf("title" to title, "sections" to sections, "graphics" to graphics, "related_topics" to related))
    }

    @Tool
    @LLMDescription(
        "Retrieve full text content of a section. Returns Markdown. " +
        "USE: Call after getTopicOutline with the sectionId from the outline. " +
        "Fetch all relevant sections — there is no limit on how many you can read."
    )
    fun getTopicSectionText(
        @LLMDescription("The unique topic ID") topicId: String,
        @LLMDescription("Exact section ID from getTopicOutline (e.g., 'H3', 'summary-and-recommendations')") sectionId: String,
        @LLMDescription("Fallback section title if ID lookup fails — only used as last resort") sectionTitle: String
    ): String {
        val t0 = System.currentTimeMillis()
        val content = contentRepository.getTopicContent(topicId) ?: return "Topic not found"
        val bodyHtml = content.bodyHtml
        val outlineHtml = content.outlineHtml

        var targetId = sectionId
        var sectionHtml = extractSectionHtml(bodyHtml, outlineHtml, targetId)

        if (sectionHtml == null && sectionTitle.isNotEmpty()) {
            val outline = parseOutlineList(outlineHtml)
            val matched = outline.firstOrNull {
                it["title"]?.equals(sectionTitle, ignoreCase = true) == true ||
                it["title"]?.contains(sectionTitle, ignoreCase = true) == true ||
                sectionTitle.contains(it["title"] ?: "_", ignoreCase = true)
            }
            if (matched != null) {
                val newId = matched["id"]
                if (newId != null) {
                    targetId = newId
                    sectionHtml = extractSectionHtml(bodyHtml, outlineHtml, targetId)
                }
            }
        }

        if (sectionHtml == null) {
            Log.d(TAG, "getTopicSectionText($topicId, $sectionId): NOT FOUND (${System.currentTimeMillis() - t0}ms)")
            return "Section not found."
        }

        // Build a topic title cache for link text in htmlToMarkdown
        val topicTitles = mutableMapOf<String, String>()
        // Pre-populate with the current topic's title if known
        val currentTitle = contentRepository.getTopicTitle(topicId)
        if (currentTitle != null) topicTitles[topicId] = currentTitle

        val result = htmlToMarkdown(sectionHtml) { tid ->
            topicTitles.getOrPut(tid) { contentRepository.getTopicTitle(tid) ?: tid }
        }
        Log.d(TAG, "getTopicSectionText($topicId, $targetId): ${result.length} chars (${System.currentTimeMillis() - t0}ms)")
        return result
    }

    @Tool
    @LLMDescription(
        "Follow a related topic by its ID. Returns outline with sections and graphics. " +
        "USE: When getTopicOutline shows related_topics that are relevant to the question. " +
        "Returns the same structure as getTopicOutline — sections, graphics, related_topics."
    )
    fun followRelatedTopic(
        @LLMDescription("The topic ID from the related_topics list") topicId: String
    ): String {
        return getTopicOutline(topicId)
    }

    @Tool
    @LLMDescription(
        "Retrieve metadata about a graphic (figure, algorithm, picture). Returns type and title. " +
        "Retrieve metadata for a graphic (FIGURE, IMAGE, etc.). Returns type and title only. " +
        "USE: For non-table graphics. Does NOT return image data. " +
        "For TABLE graphics, use getGraphicContent instead."
    )
    fun getGraphicInfo(
        @LLMDescription("The graphic ID from the outline") graphicId: String
    ): String {
        val content = contentRepository.getTopicContent("Graphic-$graphicId")
            ?: contentRepository.getTopicContent(graphicId)
            ?: return """{"error": "Graphic $graphicId not found"}"""
        val outlineHtml = content.outlineHtml
        val graphics = parseGraphicsFromOutline(outlineHtml)
        val graphic = graphics.firstOrNull { it["id"] == graphicId }
            ?: return """{"error": "Graphic $graphicId not found in outline"}"""
        return Json.encodeToString(graphic)
    }

    @Tool
    @LLMDescription(
        "Retrieve table data from a TABLE-type graphic as Markdown. " +
        "USE: Only for graphics with type='TABLE'. Returns table data as Markdown. " +
        "For FIGURE/IMAGE graphics, use getGraphicInfo — images cannot be interpreted."
    )
    fun getGraphicContent(
        @LLMDescription("The graphic ID from the outline") graphicId: String
    ): String {
        val content = contentRepository.getTopicContent("Graphic-$graphicId")
            ?: contentRepository.getTopicContent(graphicId)
            ?: return """{"error": "Graphic $graphicId not found"}"""
        val graphics = parseGraphicsFromOutline(content.outlineHtml)
        val graphic = graphics.firstOrNull { it["id"] == graphicId }
            ?: return """{"error": "Graphic $graphicId not found in outline"}"""
        val subtype = graphic["type"] as? String ?: ""
        if (subtype != "graphic_table") {
            return """{"error": "Graphic $graphicId is type '$subtype', not a table. Use getGraphicInfo for non-table graphics."}"""
        }

        // Strip Graphic- prefix to match internal asset repository storage model
        val rawGraphicId = graphicId.removePrefix("Graphic-").removePrefix("graphic-")
        val graphicData = assetRepository.getGraphic(rawGraphicId)
            ?: return """{"error": "Graphic content not found in database"}"""
        val imageHtml = graphicData.imageHtml
        if (imageHtml.isBlank()) {
            return """{"error": "Graphic $graphicId has no content"}"""
        }
        val markdown = htmlToMarkdown(imageHtml)
        return "Title: ${graphic["title"]}\n\n$markdown"
    }

    private fun parseOutlineList(outlineHtml: String): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        val matches = A_TAG_REGEX.findAll(outlineHtml)
        for (match in matches) {
            val href = match.groupValues[1]
            val innerHtml = match.groupValues[2]

            val sectionMatch = SECTION_REGEX.find(href)
            if (sectionMatch != null) {
                val secId = sectionMatch.groupValues[1]
                val text = innerHtml.replace(STRIP_TAGS_REGEX, "").trim()
                if (text.isNotEmpty()) {
                    results.add(mapOf("id" to secId, "title" to text))
                }
            }
        }
        return results
    }

    private fun parseRelatedTopics(outlineHtml: String): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        val matches = A_TAG_REGEX.findAll(outlineHtml)
        for (match in matches) {
            val href = match.groupValues[1]
            val innerHtml = match.groupValues[2]

            val sectionMatch = SECTION_REGEX.find(href)
            val typeMatch = GRAPHIC_TYPE_REGEX.find(href)

            if (sectionMatch == null && typeMatch == null) {
                val idMatch = GRAPHIC_ID_REGEX.find(href)
                val id = idMatch?.groupValues?.get(1) ?: ""
                val text = innerHtml.replace(STRIP_TAGS_REGEX, "").trim()
                if (text.isNotEmpty() && id.isNotEmpty()) {
                    results.add(mapOf("topicId" to id, "title" to text))
                }
            }
        }
        return results
    }

    private fun parseGraphicsFromOutline(outlineHtml: String): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        val matches = A_TAG_REGEX.findAll(outlineHtml)
        for (match in matches) {
            val href = match.groupValues[1]
            val innerHtml = match.groupValues[2]

            val typeMatch = GRAPHIC_TYPE_REGEX.find(href)
            if (typeMatch != null && typeMatch.groupValues[1] == "graphic") {
                val idMatch = GRAPHIC_ID_REGEX.find(href)
                val subtypeMatch = GRAPHIC_SUBTYPE_REGEX.find(href)
                val id = idMatch?.groupValues?.get(1) ?: ""
                val subtype = subtypeMatch?.groupValues?.get(1) ?: ""
                val title = innerHtml.replace(STRIP_TAGS_REGEX, "").trim()

                if (id.isNotEmpty()) {
                    val hasImage = subtype in listOf(
                        "graphic_table", "graphic_figure", "graphic_algorithm",
                        "graphic_picture", "graphic_diagnosticimage", "graphic_waveform"
                    )
                    val hasMovie = subtype == "graphic_movie"
                    results.add(mapOf(
                        "id" to id,
                        "type" to subtype,
                        "title" to title,
                        "hasImage" to hasImage,
                        "hasMovie" to hasMovie
                    ))
                }
            }
        }
        return results
    }

    /**
     * Slices the section text without cutting off early on inner subsection titles.
     * Uses the outline sequence to find the exact next sibling heading boundary.
     */
    private fun extractSectionHtml(bodyHtml: String, outlineHtml: String, sectionId: String): String? {
        val escapedId = Regex.escape(sectionId)
        val startTagRegex = Regex("""<\w+\s+[^>]*id=["']${escapedId}["'][^>]*>""", setOf(RegexOption.IGNORE_CASE))
        val startMatch = startTagRegex.find(bodyHtml) ?: return null
        val startIdx = startMatch.range.first

        val outline = parseOutlineList(outlineHtml)
        val currentIndex = outline.indexOfFirst { it["id"] == sectionId }

        var nextMatchIdx = -1
        if (currentIndex != -1 && currentIndex < outline.size - 1) {
            // Find the starting boundary of the next sibling heading in sequence
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
            // Fallback terminal boundaries
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
                    // Only convert medical/drug topic links — skip contributors, abstracts/footnotes
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
        // Block-level boundaries — must run BEFORE the catch-all tag strip, or heading/div/
        // table/list text can concatenate directly into adjacent content with no separator.
        s = s.replace(HTML_HEADING_TAG, "\n\n")
        s = s.replace(HTML_TABLE_OPEN, "\n\n")
        s = s.replace(HTML_LIST_OPEN, "\n")
        s = s.replace(HTML_DIV_OPEN, "\n")
        s = s.replace(HTML_BLOCK_CLOSE, "\n")

        s = s.replace(HTML_P_TAG, "\n\n")
        s = s.replace(HTML_LI_TAG, "\n- ")
        s = s.replace(HTML_LI_CLOSE, "\n")
        s = s.replace(HTML_BR_TAG, "\n")
        s = s.replace(HTML_TR_TAG, "\n")
        s = s.replace(HTML_TD_TH_TAG, " | ")
        s = s.replace(HTML_STRONG_TAG, "**")
        s = s.replace(HTML_B_TAG, "**")
        s = s.replace(HTML_EM_TAG, "*")
        s = s.replace(HTML_I_TAG, "*")

        s = s.replace(STRIP_TAGS_REGEX, "")

        s = s.replace("&#160;", " ").replace("&nbsp;", " ")
        s = s.replace("&#8212;", "\u2014").replace("&mdash;", "\u2014")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

        val lines = s.split("\n").map { it.trim() }
        val nonBg = mutableListOf<String>()
        for (line in lines) {
            if (line.isNotEmpty()) {
                nonBg.add(line)
            } else if (nonBg.isNotEmpty() && nonBg.last().isNotEmpty()) {
                nonBg.add("")
            }
        }
        return nonBg.joinToString("\n").trim()
    }
}
