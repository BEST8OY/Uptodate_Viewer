package com.clinref.app.data

import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import org.jsoup.Jsoup
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
) {

    val searchTopicsTool by lazy { com.clinref.app.data.tools.SearchTopicsTool(this) }
    val getTopicOutlineTool by lazy { com.clinref.app.data.tools.GetTopicOutlineTool(this) }
    val getRelatedTopicsTool by lazy { com.clinref.app.data.tools.GetRelatedTopicsTool(this) }
    val getTopicSectionsTextTool by lazy { com.clinref.app.data.tools.GetTopicSectionsTextTool(this) }
    val getGraphicContentTool by lazy { com.clinref.app.data.tools.GetGraphicContentTool(this) }

    fun asToolList(): List<ai.koog.agents.core.tools.Tool<*, *>> = listOf(
        searchTopicsTool,
        getTopicOutlineTool,
        getRelatedTopicsTool,
        getTopicSectionsTextTool,
        getGraphicContentTool
    )

    fun getTopicTitle(topicId: String): String? = contentRepository.getTopicTitle(topicId)
    fun getSectionTitle(topicId: String, sectionId: String): String? = contentRepository.getSectionTitle(topicId, sectionId)

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

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
        private val NON_TEXT_BLOCKS_REGEX = Regex(
            "<(script|style|noscript|iframe)\\b[^>]*>.*?</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
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
        private val LEGACY_HYPHEN_REGEX = Regex(
            """<span[^>]*class="legacyTopicViewHyphen"[^>]*>.*?</span>""",
            setOf(RegexOption.IGNORE_CASE)
        )
    }

    fun searchTopics(query: String): String {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return json.encodeToString(SearchTopicsResponse())
        }

        val searchResults = searchRepository.searchTopics(cleanQuery)
        val results = formatSearchResults(searchResults)

        // Auto-retry: if primary query returns no results, try available suggestions internally
        if (results.isEmpty()) {
            val suggestions = searchRepository.getSuggestions(cleanQuery).distinct().take(20)
            for (suggestion in suggestions.take(5)) {
                val retryResults = searchRepository.searchTopics(suggestion)
                val retryMapped = formatSearchResults(retryResults)
                if (retryMapped.isNotEmpty()) {
                    return json.encodeToString(
                        SearchTopicsResponse(
                            query = suggestion,
                            results = retryMapped,
                            refine_with = suggestions.filter { it != suggestion },
                            message = "Auto-refined from '$cleanQuery' to '$suggestion'"
                        )
                    )
                }
            }
            // Still no results after auto-retry
            val allSuggestions = searchRepository.getSuggestions(cleanQuery).distinct().take(20)
            val msg = if (allSuggestions.isEmpty()) {
                "No topic match and no suggestions available for '$cleanQuery'."
            } else {
                "No results for '$cleanQuery'. Try one of the 'refine_with' suggestions."
            }
            return json.encodeToString(
                SearchTopicsResponse(
                    query = cleanQuery,
                    results = emptyList(),
                    refine_with = allSuggestions,
                    message = msg
                )
            )
        }

        val allSuggestions = searchRepository.getSuggestions(cleanQuery).distinct().take(20)
        return json.encodeToString(
            SearchTopicsResponse(
                query = cleanQuery,
                results = results,
                refine_with = allSuggestions,
                message = "Success"
            )
        )
    }

    private fun formatSearchResults(searchResults: List<com.clinref.app.domain.SearchResult>): List<SearchResultTopicItem> {
        return searchResults.map { result ->
            val id = when (result) {
                is com.clinref.app.domain.SearchResult.Topic -> result.topicId
                is com.clinref.app.domain.SearchResult.Graphic -> result.graphicId
            }
            SearchResultTopicItem(id = id, title = result.title)
        }
    }

    fun getTopicOutline(topicId: String): String {
        val cleanTopicId = topicId.trim()
        val content = contentRepository.getTopicContent(cleanTopicId) ?: return "Topic not found: $cleanTopicId"
        val title = contentRepository.getTopicTitle(cleanTopicId) ?: cleanTopicId
        val outline = parseOutline(content.outlineHtml)
        val isCalc = content.outlineHtml.isBlank()

        val sectionsList = if (isCalc) {
            listOf(OutlineSectionItem(id = "FULL", title = "Calculator Tool Content"))
        } else {
            outline.sections.map { OutlineSectionItem(id = it["id"] ?: "", title = it["title"] ?: "") }
        }

        val graphicsList = outline.graphics.filter { it["isTable"] == "true" }.map { g ->
            OutlineGraphicItem(id = g["id"] ?: "", title = g["title"] ?: "", isTable = true)
        }

        val relatedList = outline.relatedTopics.map { rt ->
            RelatedTopicItem(id = rt["topicId"] ?: rt["id"] ?: "", title = rt["title"] ?: "")
        }

        return json.encodeToString(
            TopicOutlineResponse(
                topicId = cleanTopicId,
                title = title,
                topicType = if (isCalc) "calc" else "article",
                sections = sectionsList,
                graphics = graphicsList,
                relatedTopics = relatedList
            )
        )
    }

    fun getRelatedTopics(topicId: String): String {
        val cleanTopicId = topicId.trim()
        val content = contentRepository.getTopicContent(cleanTopicId) ?: return "Topic not found: $cleanTopicId"
        val outline = parseOutline(content.outlineHtml)
        val relatedList = outline.relatedTopics.map { rt ->
            RelatedTopicItem(id = rt["topicId"] ?: rt["id"] ?: "", title = rt["title"] ?: "")
        }
        return json.encodeToString(
            RelatedTopicsResponse(
                topicId = cleanTopicId,
                relatedTopics = relatedList
            )
        )
    }

    fun getTopicSectionsText(topicId: String, sectionIds: List<String>): String {
        val cleanTopicId = topicId.trim()
        val content = contentRepository.getTopicContent(cleanTopicId) ?: return "Topic not found"

        val topicTitle = contentRepository.getTopicTitle(cleanTopicId) ?: cleanTopicId
        val topicTitles = mutableMapOf<String, String>()
        topicTitles[cleanTopicId] = topicTitle

        // Build section ID -> title map from outline
        val outlineSections = parseOutline(content.outlineHtml).sections
        val idToTitle = outlineSections.associate { it["id"]!! to (it["title"] ?: "") }

        val cleanUpper = sectionIds.map { it.trim().uppercase() }
        val isCalc = content.outlineHtml.isBlank()
        if (idToTitle.isEmpty() || isCalc || cleanUpper.contains("FULL") || cleanUpper.contains("ALL")) {
            val markdown = htmlToMarkdown(content.bodyHtml) { tid ->
                topicTitles.getOrPut(tid) { contentRepository.getTopicTitle(tid) ?: tid }
            }
            val header = if (isCalc) "=== Calculator: $topicTitle ===" else "=== Section: FULL ==="
            return json.encodeToString(
                TopicSectionsResponse(
                    topicId = cleanTopicId,
                    topicTitle = topicTitle,
                    sectionTitles = mapOf("FULL" to topicTitle),
                    markdown = "$header\n$markdown"
                )
            )
        }

        // Validate: separate valid from invalid section IDs
        val validIds = mutableListOf<String>()
        val invalidIds = mutableListOf<String>()
        for (sectionId in sectionIds) {
            val clean = sectionId.trim()
            if (clean in idToTitle) {
                validIds.add(clean)
            } else {
                invalidIds.add(clean)
            }
        }

        val sectionsMd = mutableListOf<String>()
        val sectionTitles = mutableMapOf<String, String>()
        for (sectionId in validIds) {
            val title = idToTitle[sectionId] ?: ""
            sectionTitles[sectionId] = title
            val sectionHtml = extractSectionHtml(content.bodyHtml, content.outlineHtml, sectionId)
            if (sectionHtml != null) {
                val markdown = htmlToMarkdown(sectionHtml) { tid ->
                    topicTitles.getOrPut(tid) { contentRepository.getTopicTitle(tid) ?: tid }
                }
                sectionsMd.add("=== Section: $sectionId ===\n$markdown")
            } else {
                sectionsMd.add("=== Section: $sectionId ===\nSection not found.")
            }
        }

        return json.encodeToString(
            TopicSectionsResponse(
                topicId = cleanTopicId,
                topicTitle = topicTitle,
                sectionTitles = sectionTitles,
                markdown = sectionsMd.joinToString("\n\n"),
                invalidSections = invalidIds.ifEmpty { null }
            )
        )
    }

    fun getGraphicContent(graphicId: String): String {
        val cleanId = graphicId.trim()
        val rawGraphicId = cleanId.replace(Regex("(?i)^graphic-"), "")

        val graphicData = assetRepository.getGraphic(rawGraphicId)
            ?: return json.encodeToString(ToolErrorResponse("Graphic $cleanId not found"))

        if (graphicData.subtype.isNotEmpty() && !graphicData.isTable) {
            return json.encodeToString(ToolErrorResponse("Graphic $cleanId is of type '${graphicData.subtype}', not a table. Only table content is retrievable."))
        }

        val imageHtml = graphicData.imageHtml
        if (imageHtml.isBlank()) {
            return json.encodeToString(ToolErrorResponse("Graphic $cleanId has empty content"))
        }

        val markdown = htmlToMarkdown(imageHtml)
        val title = graphicData.title.ifEmpty { rawGraphicId }
        return "### Graphic Table: $title\n\n$markdown"
    }

    private data class OutlineResult(
        val sections: List<Map<String, String>>,
        val graphics: List<Map<String, String>>,
        val relatedTopics: List<Map<String, String>>
    )

    private fun parseOutline(outlineHtml: String): OutlineResult {
        val sections = mutableListOf<Map<String, String>>()
        val graphics = mutableListOf<Map<String, String>>()
        val related = mutableListOf<Map<String, String>>()

        for (match in A_TAG_REGEX.findAll(outlineHtml)) {
            val href = match.groupValues[1]
            val innerHtml = match.groupValues[2]
            val text = innerHtml
                .replace(LEGACY_HYPHEN_REGEX, "")
                .replace(STRIP_TAGS_REGEX, "")
                .trim()

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

        s = NON_TEXT_BLOCKS_REGEX.replace(s, "")

        s = A_TAG_REGEX.replace(s) { match ->
            val href = match.groupValues[1]
            val rawText = match.groupValues[2].replace(STRIP_TAGS_REGEX, "").trim()
            if (rawText.isEmpty()) return@replace ""

            val actionMatch = GRAPHIC_ACTION_REGEX.find(href)
            if (actionMatch != null) {
                val jsonStr = actionMatch.groupValues[1]
                    .replace("&quot;", "\"").replace("&#39;", "'")
                when {
                    jsonStr.contains("graphic", ignoreCase = true) || jsonStr.contains("graphicId", ignoreCase = true) -> {
                        val idMatch = GRAPHIC_ID_REGEX.find(jsonStr)
                        val graphicId = idMatch?.groupValues?.get(1)
                        if (graphicId != null) {
                            return@replace "[$rawText](Graphic-$graphicId)"
                        }
                    }
                    jsonStr.contains("topic", ignoreCase = true) || jsonStr.contains("topicId", ignoreCase = true) ||
                    jsonStr.contains("medical", ignoreCase = true) || jsonStr.contains("drug", ignoreCase = true) -> {
                        val idMatch = GRAPHIC_ID_REGEX.find(jsonStr)
                        val topicId = idMatch?.groupValues?.get(1)
                        if (topicId != null) {
                            val sectionMatch = SECTION_REGEX.find(jsonStr)
                            val sectionId = sectionMatch?.groupValues?.get(1)
                            val ref = if (sectionId != null) "$topicId#$sectionId" else topicId
                            val textToUse = rawText.ifEmpty { titleLookup(topicId) }
                            return@replace "[$textToUse](Topic-$ref)"
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

        // Strip footnote citation references like [1], [1,2], [1-3]
        s = s.replace(Regex("\\[\\d+(?:\\s*[-,\\u2013\\u2014]\\s*\\d+)*\\]"), "")

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
        val doc = Jsoup.parseBodyFragment(html)
        val tables = doc.select("table")

        for (table in tables) {
            val markdownTable = StringBuilder("\n\n")
            val rows = table.select("tr")
            if (rows.isEmpty()) continue

            val tableMatrix = mutableListOf<List<String>>()
            for (row in rows) {
                val cells = row.select("th, td").map { cell ->
                    cell.text().replace("|", "\\|").trim()
                }
                if (cells.isNotEmpty()) tableMatrix.add(cells)
            }

            if (tableMatrix.isEmpty()) continue
            val maxCols = tableMatrix.maxOf { it.size }

            val header = tableMatrix.first()
            markdownTable.append("| ").append(header.padTo(maxCols, "").joinToString(" | ")).append(" |\n")

            val sep = List(maxCols) { "---" }
            markdownTable.append("| ").append(sep.joinToString(" | ")).append(" |\n")

            for (row in tableMatrix.drop(1)) {
                markdownTable.append("| ").append(row.padTo(maxCols, "").joinToString(" | ")).append(" |\n")
            }
            markdownTable.append("\n")

            table.replaceWith(doc.createElement("p").text(markdownTable.toString()))
        }

        return doc.body().html()
    }

    private fun <T> List<T>.padTo(size: Int, default: T): List<T> {
        if (this.size >= size) return this
        return this + List(size - this.size) { default }
    }
}
