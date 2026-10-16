package com.clinref.app.data

import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TAG = "MedTools"

class MedicalDatabaseTools(
    private val searchRepository: SearchRepository,
    private val contentRepository: ContentRepository,
    private val assetRepository: AssetRepository
) {

    val searchTopicsTool by lazy { com.clinref.app.data.tools.SearchTopicsTool(this) }
    val getTopicOutlineTool by lazy { com.clinref.app.data.tools.GetTopicOutlineTool(this) }
    val searchTopicSectionsTool by lazy { com.clinref.app.data.tools.SearchTopicSectionsTool(this) }
    val getRelatedTopicsTool by lazy { com.clinref.app.data.tools.GetRelatedTopicsTool(this) }
    val getTopicSectionsTextTool by lazy { com.clinref.app.data.tools.GetTopicSectionsTextTool(this) }
    val getGraphicContentTool by lazy { com.clinref.app.data.tools.GetGraphicContentTool(this) }

    fun asToolList(): List<ai.koog.agents.core.tools.Tool<*, *>> = listOf(
        searchTopicsTool,
        getTopicOutlineTool,
        searchTopicSectionsTool,
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
        private val SDI_LOOKUP_TABLES_REGEX = Regex(
            """<(?:td|div|p)\b[^>]*(?:id=["']sdi\d+["']|class=["'][^"']*sdiTable[^"']*["'])[^>]*>.*?</(?:td|div|p)>""",
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
        private val LEADING_BULLET_OR_DASH_REGEX = Regex(
            """^[-–—•●\s]+"""
        )
        private val CITATION_BRACKETS_REGEX = Regex(
            """\[\s*\d+(?:\s*[-,–—]\s*\d+)*\s*\]"""
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
        val content = contentRepository.getTopicContent(cleanTopicId)
            ?: return json.encodeToString(ToolErrorResponse("Topic not found: $cleanTopicId"))
        val title = contentRepository.getTopicTitle(cleanTopicId) ?: cleanTopicId
        val outline = parseOutline(content.outlineHtml)
        val isCalc = content.outlineHtml.isBlank()

        val sectionsList = if (isCalc) {
            listOf(OutlineSectionItem(id = "FULL", title = "Calculator Tool Content", level = 1))
        } else {
            outline.sections.map {
                OutlineSectionItem(
                    id = it.id,
                    title = it.title,
                    level = it.level,
                    parentId = it.parentId
                )
            }
        }

        val graphicsList = outline.graphics.filter { it.isTable }.map { g ->
            OutlineGraphicItem(id = g.id, title = g.title, isTable = true)
        }

        val relatedList = outline.relatedTopics.map { rt ->
            RelatedTopicItem(id = rt.id, title = rt.title)
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
        val content = contentRepository.getTopicContent(cleanTopicId)
            ?: return json.encodeToString(ToolErrorResponse("Topic not found: $cleanTopicId"))
        if (content.outlineHtml.isBlank()) {
            return json.encodeToString(
                RelatedTopicsResponse(
                    topicId = cleanTopicId,
                    relatedTopics = emptyList()
                )
            )
        }
        val outline = parseOutline(content.outlineHtml)
        val relatedList = outline.relatedTopics.map { rt ->
            RelatedTopicItem(id = rt.id, title = rt.title)
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
        val content = contentRepository.getTopicContent(cleanTopicId)
            ?: return json.encodeToString(ToolErrorResponse("Topic not found"))

        val topicTitle = contentRepository.getTopicTitle(cleanTopicId) ?: cleanTopicId
        val topicTitles = mutableMapOf<String, String>()
        topicTitles[cleanTopicId] = topicTitle

        // Build section ID -> title map from parsed outline
        val outline = parseOutline(content.outlineHtml)
        val idToTitle = outline.sections.associate { it.id to it.title }

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

        val targetRequests = if (sectionIds.isEmpty()) {
            idToTitle.keys.take(3).toList()
        } else {
            sectionIds
        }

        // Validate & smart-resolve section IDs (supports exact ID, case-insensitive ID, or matching section title)
        val validIds = mutableListOf<String>()
        val invalidIds = mutableListOf<String>()
        for (req in targetRequests) {
            val clean = req.trim()
            if (clean.isEmpty()) continue

            when {
                clean in idToTitle -> {
                    if (clean !in validIds) validIds.add(clean)
                }
                else -> {
                    // Try case-insensitive ID match
                    val matchedById = idToTitle.keys.firstOrNull { it.equals(clean, ignoreCase = true) }
                    // Try title match
                    val matchedByTitle = idToTitle.entries.firstOrNull { (id, title) ->
                        title.equals(clean, ignoreCase = true) ||
                        title.trimStart('-', '–', '—', ' ').equals(clean.trimStart('-', '–', '—', ' '), ignoreCase = true)
                    }?.key

                    val resolvedId = matchedById ?: matchedByTitle
                    if (resolvedId != null) {
                        if (resolvedId !in validIds) validIds.add(resolvedId)
                    } else {
                        invalidIds.add(clean)
                    }
                }
            }
        }

        if (validIds.isEmpty() && invalidIds.isNotEmpty()) {
            return json.encodeToString(
                TopicSectionsResponse(
                    topicId = cleanTopicId,
                    topicTitle = topicTitle,
                    sectionTitles = emptyMap(),
                    markdown = "",
                    invalidSections = invalidIds
                )
            )
        }

        val sectionsMd = mutableListOf<String>()
        val sectionTitles = mutableMapOf<String, String>()
        for (sectionId in validIds) {
            val title = idToTitle[sectionId] ?: ""
            sectionTitles[sectionId] = title
            val sectionHtml = extractSectionHtml(content.bodyHtml, outline.sections, sectionId)
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
        val rawGraphicId = cleanId.replace(Regex("(?i)^(?:graphic[-_]?(?:table[-_]?)?|table[-_]?)"), "")

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

    /**
     * Search within a topic's sections for clinical keywords, returning matching section IDs,
     * titles, hierarchy level, and contextual snippets.
     */
    fun searchTopicSections(topicId: String, query: String): String {
        val cleanTopicId = topicId.trim()
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return json.encodeToString(SearchTopicSectionsResponse(topicId = cleanTopicId, query = ""))
        }

        val content = contentRepository.getTopicContent(cleanTopicId)
            ?: return json.encodeToString(ToolErrorResponse("Topic not found: $cleanTopicId"))

        val outline = parseOutline(content.outlineHtml)
        val matches = mutableListOf<SectionSearchResult>()

        for (section in outline.sections) {
            val sectionHtml = extractSectionHtml(content.bodyHtml, outline.sections, section.id) ?: continue
            val sectionMd = htmlToMarkdown(sectionHtml)
            val queryIdx = sectionMd.indexOf(cleanQuery, ignoreCase = true)
            val titleMatches = section.title.contains(cleanQuery, ignoreCase = true)

            if (queryIdx >= 0 || titleMatches) {
                val snippet = if (queryIdx >= 0) {
                    val start = maxOf(0, queryIdx - 80)
                    val end = minOf(sectionMd.length, queryIdx + cleanQuery.length + 120)
                    (if (start > 0) "..." else "") + sectionMd.substring(start, end).replace("\n", " ").trim() + (if (end < sectionMd.length) "..." else "")
                } else {
                    sectionMd.take(200).replace("\n", " ").trim() + (if (sectionMd.length > 200) "..." else "")
                }

                matches.add(
                    SectionSearchResult(
                        sectionId = section.id,
                        sectionTitle = section.title,
                        level = section.level,
                        snippet = snippet
                    )
                )
            }
        }

        return json.encodeToString(
            SearchTopicSectionsResponse(
                topicId = cleanTopicId,
                query = cleanQuery,
                matches = matches,
                message = "Found ${matches.size} matching sections"
            )
        )
    }

    private data class ParsedSection(
        val id: String,
        val title: String,
        val level: Int = 1,
        val parentId: String? = null
    )

    private data class ParsedGraphic(
        val id: String,
        val title: String,
        val isTable: Boolean
    )

    private data class ParsedRelatedTopic(
        val id: String,
        val title: String
    )

    private data class OutlineResult(
        val sections: List<ParsedSection>,
        val graphics: List<ParsedGraphic>,
        val relatedTopics: List<ParsedRelatedTopic>
    )

    private fun parseOutline(outlineHtml: String): OutlineResult {
        if (outlineHtml.isBlank()) {
            return OutlineResult(emptyList(), emptyList(), emptyList())
        }

        val tagEventRegex = Regex(
            """(<ul\b[^>]*>|</ul\b>|<li\b[^>]*>|<a\s+[^>]*href=['"][^'"]*['"][^>]*>.*?</a>)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        var depth = 0
        var inSrButton = false
        val parents = mutableMapOf<Int, String>()

        val rawSections = mutableListOf<ParsedSection>()
        val rawSectionIsSr = mutableListOf<Boolean>()
        val graphics = mutableListOf<ParsedGraphic>()
        val related = mutableListOf<ParsedRelatedTopic>()

        val seenGraphicIds = mutableSetOf<String>()
        val seenRelatedIds = mutableSetOf<String>()

        for (m in tagEventRegex.findAll(outlineHtml)) {
            val token = m.groupValues[1]
            val lower = token.lowercase()
            when {
                lower.startsWith("<ul") -> {
                    depth++
                }
                lower.startsWith("</ul") -> {
                    depth = maxOf(0, depth - 1)
                }
                lower.startsWith("<li") -> {
                    inSrButton = token.contains("sr-button", ignoreCase = true)
                }
                lower.startsWith("<a") -> {
                    val aMatch = A_TAG_REGEX.matchEntire(token) ?: continue
                    val href = aMatch.groupValues[1]
                    val rawText = aMatch.groupValues[2]
                    val cleanTitle = cleanOutlineTitle(rawText)
                    if (cleanTitle.isEmpty()) continue

                    val sectionMatch = SECTION_REGEX.find(href)
                    val typeMatch = GRAPHIC_TYPE_REGEX.find(href)
                    val isGraphic = typeMatch != null && typeMatch.groupValues[1].equals("graphic", ignoreCase = true)

                    when {
                        isGraphic -> {
                            val id = GRAPHIC_ID_REGEX.find(href)?.groupValues?.get(1) ?: ""
                            val subtype = GRAPHIC_SUBTYPE_REGEX.find(href)?.groupValues?.get(1) ?: ""
                            if (id.isNotEmpty() && seenGraphicIds.add(id)) {
                                val isTable = subtype.equals("graphic_table", ignoreCase = true) || subtype.equals("table", ignoreCase = true)
                                graphics.add(ParsedGraphic(id = id, title = cleanTitle, isTable = isTable))
                            }
                        }
                        sectionMatch != null -> {
                            val sid = sectionMatch.groupValues[1]
                            val level = maxOf(1, depth)
                            val parentId = if (level > 1) parents[level - 1] else null
                            parents[level] = sid
                            rawSections.add(ParsedSection(id = sid, title = cleanTitle, level = level, parentId = parentId))
                            rawSectionIsSr.add(inSrButton)
                        }
                        else -> {
                            val id = GRAPHIC_ID_REGEX.find(href)?.groupValues?.get(1) ?: ""
                            if (id.isNotEmpty() && seenRelatedIds.add(id)) {
                                related.add(ParsedRelatedTopic(id = id, title = cleanTitle))
                            }
                        }
                    }
                }
            }
        }

        // Deduplicate sections: if an ID is present in non-sr, skip the sr-button duplicate in favor of in-flow section
        val nonSrIds = rawSections.filterIndexed { idx, _ -> !rawSectionIsSr[idx] }.map { it.id }.toSet()
        val sections = mutableListOf<ParsedSection>()
        val seenSectionIds = mutableSetOf<String>()

        for (i in rawSections.indices) {
            val sec = rawSections[i]
            val isSr = rawSectionIsSr[i]
            if (isSr && sec.id in nonSrIds) {
                continue
            }
            if (seenSectionIds.add(sec.id)) {
                sections.add(sec)
            }
        }

        return OutlineResult(sections, graphics, related)
    }

    private fun cleanOutlineTitle(html: String): String {
        return html
            .replace(LEGACY_HYPHEN_REGEX, "")
            .replace(STRIP_TAGS_REGEX, "")
            .replace(LEADING_BULLET_OR_DASH_REGEX, "")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .trim()
    }

    private fun extractSectionHtml(
        bodyHtml: String,
        outlineSections: List<ParsedSection>,
        sectionId: String
    ): String? {
        val escapedId = Regex.escape(sectionId)
        val startTagRegex = Regex("""<\w+\s+[^>]*id=["']${escapedId}["'][^>]*>""", setOf(RegexOption.IGNORE_CASE))
        val startMatch = startTagRegex.find(bodyHtml) ?: return null
        val startIdx = startMatch.range.first

        val currentIndex = outlineSections.indexOfFirst { it.id.equals(sectionId, ignoreCase = true) }
        val targetLevel = if (currentIndex != -1) outlineSections[currentIndex].level else {
            // Determine level from heading tag in bodyHtml: <span class="hX" role="heading">
            val snippet = bodyHtml.substring(startIdx, minOf(startIdx + 300, bodyHtml.length))
            val hlMatch = Regex("""class=["']h([1-6])["']|aria-level=["'](\d+)["']""", RegexOption.IGNORE_CASE).find(snippet)
            if (hlMatch?.groupValues?.get(1)?.isNotEmpty() == true) {
                hlMatch.groupValues[1].toIntOrNull() ?: 1
            } else if (hlMatch?.groupValues?.get(2)?.isNotEmpty() == true) {
                maxOf(1, (hlMatch.groupValues[2].toIntOrNull() ?: 2) - 1)
            } else 1
        }

        // Slicing logic: Scan subsequent sections until the next section of level <= targetLevel
        var nextMatchIdx = -1
        if (currentIndex != -1 && currentIndex < outlineSections.size - 1) {
            for (nextIdx in (currentIndex + 1) until outlineSections.size) {
                val nextSec = outlineSections[nextIdx]
                if (nextSec.level <= targetLevel) {
                    val nextEscapedId = Regex.escape(nextSec.id)
                    val nextStartTagRegex = Regex("""<\w+\s+[^>]*id=["']${nextEscapedId}["'][^>]*>""", setOf(RegexOption.IGNORE_CASE))
                    val nextMatch = nextStartTagRegex.find(bodyHtml, startIndex = startMatch.range.last + 1)
                    if (nextMatch != null) {
                        nextMatchIdx = nextMatch.range.first
                        break
                    }
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

        // Strip non-text scripts and stylesheets
        s = NON_TEXT_BLOCKS_REGEX.replace(s, "")

        // Strip huge client-side lookup tables in calculators (e.g., zip code arrays)
        s = SDI_LOOKUP_TABLES_REGEX.replace(s, "")

        // Convert UpToDate heading anchors to markdown headers
        s = s.replace(Regex("""<span\s+[^>]*class=["']h1["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n\n## $1\n\n")
        s = s.replace(Regex("""<span\s+[^>]*class=["']h2["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n\n### $1\n\n")
        s = s.replace(Regex("""<span\s+[^>]*class=["']h3["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n\n#### $1\n\n")
        s = s.replace(Regex("""<span\s+[^>]*class=["']h[4-6]["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n\n##### $1\n\n")
        s = s.replace(Regex("""<span\s+[^>]*class=["']headingEndMark["'][^>]*>.*?</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")

        // Standard headings fallback
        s = s.replace(Regex("""</?h1\b[^>]*>""", RegexOption.IGNORE_CASE), "\n\n# ")
        s = s.replace(Regex("""</?h2\b[^>]*>""", RegexOption.IGNORE_CASE), "\n\n## ")
        s = s.replace(Regex("""</?h[3-6]\b[^>]*>""", RegexOption.IGNORE_CASE), "\n\n### ")

        // Definition lists (<dt>, <dd>)
        s = s.replace(Regex("""<dt\b[^>]*>(.*?)</dt>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "\n- **$1**: ")
        s = s.replace(Regex("""<dd\b[^>]*>(.*?)</dd>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "$1\n")

        // Bullet indentations
        s = s.replace(Regex("""<p\b[^>]*class=["']bulletIndent\d*["'][^>]*>""", RegexOption.IGNORE_CASE), "\n- ")
        s = s.replace(Regex("""<span\s+[^>]*class=["']glyph["'][^>]*>.*?</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")

        // Convert links
        s = A_TAG_REGEX.replace(s) { match ->
            val href = match.groupValues[1]
            val rawText = match.groupValues[2].replace(STRIP_TAGS_REGEX, "").trim()
            if (rawText.isEmpty()) return@replace ""

            // Strip abstract citation links like [1], [2]
            if (href.contains("abstract", ignoreCase = true)) {
                return@replace rawText
            }

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

        // Tables
        s = convertHtmlTablesToMarkdown(s)

        // Block elements
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
        s = s.replace(CITATION_BRACKETS_REGEX, "")

        // Decode HTML entities
        s = decodeHtmlEntities(s)

        // Normalize blank lines
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
        val tableRegex = Regex("""<table\b[^>]*>(.*?)</table>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val trRegex = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val cellRegex = Regex("""<(?:th|td)\b[^>]*>(.*?)</(?:th|td)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val tagStripRegex = Regex("""<[^>]+>""")

        return tableRegex.replace(html) { matchResult ->
            val tableContent = matchResult.groupValues[1]
            val rows = trRegex.findAll(tableContent).map { trMatch ->
                cellRegex.findAll(trMatch.groupValues[1]).map { cellMatch ->
                    var cellText = tagStripRegex.replace(cellMatch.groupValues[1], "")
                    cellText = decodeHtmlEntities(cellText)
                    // Keep each table row on a single line: replace newlines with space
                    cellText = cellText.replace(Regex("""[\r\n]+"""), " ")
                    cellText = cellText.replace(Regex("""\s+"""), " ")
                    cellText = cellText.replace("|", "\\|").trim()
                    cellText
                }.toList()
            }.filter { it.isNotEmpty() }.toList()

            if (rows.isEmpty()) return@replace matchResult.value

            val maxCols = rows.maxOf { it.size }
            val sb = StringBuilder("\n\n")

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

    private fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text

        var s = text
        // Named entities
        s = s.replace("&nbsp;", " ").replace("&#160;", " ")
        s = s.replace("&amp;", "&")
        s = s.replace("&lt;", "<").replace("&gt;", ">")
        s = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&#39;", "'")
        s = s.replace("&mdash;", "—").replace("&#8212;", "—")
        s = s.replace("&ndash;", "–").replace("&#8211;", "–")
        s = s.replace("&ge;", "≥").replace("&#8805;", "≥")
        s = s.replace("&le;", "≤").replace("&#8804;", "≤")
        s = s.replace("&plusmn;", "±").replace("&#177;", "±")
        s = s.replace("&micro;", "µ").replace("&#181;", "µ")
        s = s.replace("&deg;", "°").replace("&#176;", "°")
        s = s.replace("&bull;", "•").replace("&#8226;", "•")
        s = s.replace("&#9679;", "●")
        s = s.replace("&lsquo;", "‘").replace("&#8216;", "‘")
        s = s.replace("&rsquo;", "’").replace("&#8217;", "’")
        s = s.replace("&ldquo;", "“").replace("&#8220;", "“")
        s = s.replace("&rdquo;", "”").replace("&#8221;", "”")
        s = s.replace("&para;", "¶").replace("&#182;", "¶")
        s = s.replace("&sect;", "§").replace("&#167;", "§")
        s = s.replace("&trade;", "™").replace("&#8482;", "™")
        s = s.replace("&copy;", "©").replace("&#169;", "©")
        s = s.replace("&reg;", "®").replace("&#174;", "®")
        s = s.replace("&times;", "×").replace("&#215;", "×")
        s = s.replace("&divide;", "÷").replace("&#247;", "÷")
        s = s.replace("&Delta;", "Δ").replace("&delta;", "δ")
        s = s.replace("&alpha;", "α").replace("&beta;", "β").replace("&gamma;", "γ")

        // Hex numeric entities: &#x...;
        s = s.replace(Regex("""&#x([0-9a-fA-F]+);""")) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { code ->
                if (code in 32..65535) code.toChar().toString() else ""
            } ?: m.value
        }

        // Decimal numeric entities: &#...;
        s = s.replace(Regex("""&#(\d+);""")) { m ->
            m.groupValues[1].toIntOrNull()?.let { code ->
                if (code in 32..65535) code.toChar().toString() else ""
            } ?: m.value
        }

        return s
    }

    private fun <T> List<T>.padTo(size: Int, default: T): List<T> {
        if (this.size >= size) return this
        return this + List(size - this.size) { default }
    }
}
