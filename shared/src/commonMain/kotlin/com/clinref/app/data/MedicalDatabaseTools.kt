package com.clinref.app.data

import com.clinref.app.domain.ai.AiJsonUtils
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
// Maximum sections returned per getTopicSectionsText call; extras land in omittedSections
internal const val MAX_SECTIONS_PER_CALL = 20

// SearchTopics result budget: hits returned, refine terms offered, and auto-refine retries.
private const val MAX_SEARCH_RESULTS = 8
private const val MAX_REFINE_SUGGESTIONS = 20
private const val MAX_REFINE_RETRIES = 5

class MedicalDatabaseTools(
    private val searchRepository: SearchRepository,
    private val contentRepository: ContentRepository,
    private val assetRepository: AssetRepository
) {

    val getTopicOverviewTool by lazy { com.clinref.app.data.tools.GetTopicOverviewTool(this) }
    val searchTopicsTool by lazy { com.clinref.app.data.tools.SearchTopicsTool(this) }
    val getTopicSectionsTextTool by lazy { com.clinref.app.data.tools.GetTopicSectionsTextTool(this) }
    val getGraphicContentTool by lazy { com.clinref.app.data.tools.GetGraphicContentTool(this) }

    fun asToolList(): List<ai.koog.agents.core.tools.Tool<*, *>> = listOf(
        getTopicOverviewTool,
        searchTopicsTool,
        getTopicSectionsTextTool,
        getGraphicContentTool
    )

    fun getTopicTitle(topicId: String): String? = contentRepository.getTopicTitle(topicId)
    fun getSectionTitle(topicId: String, sectionId: String): String? = contentRepository.getSectionTitle(topicId, sectionId)


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
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val NON_TEXT_BLOCKS_REGEX = Regex(
            "<(script|style|noscript|iframe)\\b[^>]*>.*?</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val SDI_LOOKUP_TABLES_REGEX = Regex(
            """<(?:td|div|p)\b[^>]*(?:id=["']sdi\d+["']|class=["'][^"']*sdiTable[^"']*["'])[^>]*>.*?</(?:td|div|p)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
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

        // Precompiled heading replacement regexes
        private val SPAN_H1_REGEX = Regex("""<span\s+[^>]*class=["']h1["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SPAN_H2_REGEX = Regex("""<span\s+[^>]*class=["']h2["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SPAN_H3_REGEX = Regex("""<span\s+[^>]*class=["']h3["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val SPAN_H4_6_REGEX = Regex("""<span\s+[^>]*class=["']h[4-6]["'][^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val HEADING_END_MARK_REGEX = Regex("""<span\s+[^>]*class=["']headingEndMark["'][^>]*>.*?</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val FALLBACK_H1_REGEX = Regex("""</?h1\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val FALLBACK_H2_REGEX = Regex("""</?h2\b[^>]*>""", RegexOption.IGNORE_CASE)
        private val FALLBACK_H3_6_REGEX = Regex("""</?h[3-6]\b[^>]*>""", RegexOption.IGNORE_CASE)

        // Precompiled block element regexes
        private val DT_REGEX = Regex("""<dt\b[^>]*>(.*?)</dt>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val DD_REGEX = Regex("""<dd\b[^>]*>(.*?)</dd>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val BULLET_INDENT_REGEX = Regex("""<p\b[^>]*class=["']bulletIndent\d*["'][^>]*>""", RegexOption.IGNORE_CASE)
        private val GLYPH_REGEX = Regex("""<span\s+[^>]*class=["']glyph["'][^>]*>.*?</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val UL_OL_START_REGEX = Regex("<[uo]l\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val UL_OL_END_REGEX = Regex("</[uo]l>", RegexOption.IGNORE_CASE)
        private val LI_START_REGEX = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val LI_END_REGEX = Regex("</li>", RegexOption.IGNORE_CASE)
        private val P_REGEX = Regex("</?p\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val DIV_REGEX = Regex("</?div\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val STRONG_B_REGEX = Regex("</?(?:strong|b)\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val EM_I_REGEX = Regex("</?(?:em|i)\\b[^>]*>", RegexOption.IGNORE_CASE)

        // Precompiled table conversion regexes
        private val TABLE_REGEX = Regex("""<table\b[^>]*>(.*?)</table>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val TR_REGEX = Regex("""<tr\b[^>]*>(.*?)</tr>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val CELL_REGEX = Regex("""<(?:th|td)\b[^>]*>(.*?)</(?:th|td)>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val NEWLINE_REGEX = Regex("""[\r\n]+""")
        private val RAW_GRAPHIC_PREFIX_REGEX = Regex("(?i)^(?:graphic[-_]?(?:table[-_]?)?|table[-_]?)")

        private val TAG_EVENT_REGEX = Regex(
            """(<ul\b[^>]*>|</ul\b>|<li\b[^>]*>|<a\s+[^>]*href=['"][^'"]*['"][^>]*>.*?</a>)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        private val HEADING_LEVEL_SNIPPET_REGEX = Regex("""class=["']h([1-6])["']|aria-level=["'](\d+)["']""", RegexOption.IGNORE_CASE)
        private val REFERENCE_HEADER_REGEX = Regex("""<\w+\s+[^>]*(?:id=["']references["'])[^>]*>""", setOf(RegexOption.IGNORE_CASE))
        private val HEX_ENTITY_REGEX = Regex("""&#[xX]([0-9a-fA-F]+);""")
        private val DECIMAL_ENTITY_REGEX = Regex("""&#(\d+);""")
        private val NAMED_ENTITY_REGEX = Regex("&[a-zA-Z0-9#]+;")

        private val ENTITY_LOOKUP = mapOf(
            "&nbsp;" to " ", "&#160;" to " ",
            "&amp;" to "&", "&#38;" to "&",
            "&lt;" to "<", "&#60;" to "<",
            "&gt;" to ">", "&#62;" to ">",
            "&quot;" to "\"", "&#34;" to "\"",
            "&#39;" to "'", "&apos;" to "'",
            "&mdash;" to "—", "&#8212;" to "—",
            "&ndash;" to "–", "&#8211;" to "–",
            "&ge;" to "≥", "&#8805;" to "≥",
            "&le;" to "≤", "&#8804;" to "≤",
            "&plusmn;" to "±", "&#177;" to "±",
            "&micro;" to "µ", "&#181;" to "µ",
            "&deg;" to "°", "&#176;" to "°",
            "&bull;" to "•", "&#8226;" to "•",
            "&#9679;" to "●",
            "&lsquo;" to "‘", "&#8216;" to "‘",
            "&rsquo;" to "’", "&#8217;" to "’",
            "&ldquo;" to "“", "&#8220;" to "“",
            "&rdquo;" to "”", "&#8221;" to "”",
            "&para;" to "¶", "&#182;" to "¶",
            "&sect;" to "§", "&#167;" to "§",
            "&trade;" to "™", "&#8482;" to "™",
            "&copy;" to "©", "&#169;" to "©",
            "&reg;" to "®", "&#174;" to "®",
            "&times;" to "×", "&#215;" to "×",
            "&divide;" to "÷", "&#247;" to "÷",
            "&Delta;" to "Δ", "&delta;" to "δ",
            "&alpha;" to "α", "&beta;" to "β", "&gamma;" to "γ"
        )

        fun inferTopicType(title: String): String {
            val clean = title.trim().lowercase()
            return when {
                clean.contains("drug information") -> "drug"
                clean.startsWith("calculator:") || clean.contains("calculator") -> "calc"
                clean.startsWith("lab interpretation:") -> "narrative"
                else -> "medical"
            }
        }

        private const val MAX_OUTLINE_CACHE_ENTRIES = 250
    }

    private val outlineCache = java.util.concurrent.ConcurrentHashMap<String, List<ParsedSection>>()

    private fun getParsedSections(topicId: String, outlineHtml: String): List<ParsedSection> {
        if (outlineCache.size > MAX_OUTLINE_CACHE_ENTRIES) {
            outlineCache.clear()
        }
        return outlineCache.getOrPut(topicId) { parseOutlineSections(outlineHtml) }
    }

    fun searchTopics(query: String): SearchTopicsResponse {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return SearchTopicsResponse()
        }

        val searchResults = searchRepository.searchTopics(cleanQuery)
        val results = formatSearchResults(searchResults).take(MAX_SEARCH_RESULTS)
        if (results.isNotEmpty()) {
            return SearchTopicsResponse(query = cleanQuery, results = results, message = "Success")
        }

        // No direct hit: consult the typeahead vocabulary once, then retry its best terms.
        val suggestions = searchRepository.getSuggestions(cleanQuery).distinct().take(MAX_REFINE_SUGGESTIONS)
        for (suggestion in suggestions.take(MAX_REFINE_RETRIES)) {
            val retry = formatSearchResults(searchRepository.searchTopics(suggestion)).take(MAX_SEARCH_RESULTS)
            if (retry.isNotEmpty()) {
                return SearchTopicsResponse(
                    query = suggestion,
                    results = retry,
                    refine_with = suggestions.filter { it != suggestion },
                    message = "Auto-refined from '$cleanQuery' to '$suggestion'.",
                    autoRefined = true,
                    refinedQuery = suggestion
                )
            }
        }

        return SearchTopicsResponse(
            query = cleanQuery,
            results = emptyList(),
            refine_with = suggestions,
            message = if (suggestions.isEmpty()) {
                "No topic match and no suggestions available for '$cleanQuery'."
            } else {
                "No results for '$cleanQuery'. Retry with one of the 'refine_with' terms."
            }
        )
    }

    private fun formatSearchResults(searchResults: List<com.clinref.app.domain.SearchResult>): List<SearchResultTopicItem> {
        return searchResults.map { result ->
            when (result) {
                is com.clinref.app.domain.SearchResult.Topic -> {
                    SearchResultTopicItem(
                        id = result.topicId,
                        title = result.title,
                        type = inferTopicType(result.title)
                    )
                }
                is com.clinref.app.domain.SearchResult.Graphic -> {
                    SearchResultTopicItem(
                        id = result.graphicId,
                        title = result.title,
                        type = "table"
                    )
                }
            }
        }
    }

    /**
     * Compound outline bundle. Section ids come from the outline HTML; the graphic and
     * related-topic inventories come from the payload's structured JSON, which is the only
     * place a graphic's `subtype` (table vs. picture vs. algorithm) is recorded.
     */
    fun getTopicOverview(topicId: String): TopicOverviewResponse {
        val cleanTopicId = topicId.trim()
        val content = contentRepository.getTopicContent(cleanTopicId)
            ?: return TopicOverviewResponse(topicId = cleanTopicId, error = "Topic not found: $cleanTopicId")

        val title = contentTitle(content, cleanTopicId)
        val topicType = content.topicType.ifEmpty { if (content.outlineHtml.isBlank()) "calc" else "medical" }

        if (topicType == "calc") {
            val calcMarkdown = htmlToMarkdown(content.bodyHtml) { tid ->
                contentRepository.getTopicTitle(tid) ?: tid
            }
            return TopicOverviewResponse(
                topicId = cleanTopicId,
                title = title,
                topicType = "calc",
                sections = listOf(OutlineSectionItem(id = "FULL", title = "Calculator Tool Content", level = 1)),
                markdown = "=== Calculator: $title ===\n$calcMarkdown"
            )
        }

        val parsedSections = getParsedSections(cleanTopicId, content.outlineHtml)
        return TopicOverviewResponse(
            topicId = cleanTopicId,
            title = title,
            topicType = topicType,
            sections = parsedSections.map {
                OutlineSectionItem(id = it.id, title = it.title, level = it.level, parentId = it.parentId)
            },
            graphics = content.graphics.filter { it.isTable }.map { OutlineGraphicItem(id = it.id, title = it.title) },
            relatedTopics = content.relatedTopics.map { RelatedTopicItem(id = it.id, title = it.title) },
            markdown = ""
        )
    }

    private fun contentTitle(content: ContentRepository.TopicContent, topicId: String): String =
        AiJsonUtils.cleanHtmlText(content.title.ifEmpty { contentRepository.getTopicTitle(topicId) ?: topicId })

    fun getTopicSectionsText(topicId: String, sectionIds: List<String>): TopicSectionsResponse {
        val cleanTopicId = topicId.trim()
        val content = contentRepository.getTopicContent(cleanTopicId)
            ?: return TopicSectionsResponse(topicId = cleanTopicId, topicTitle = "", markdown = "", error = "Topic not found: $cleanTopicId")

        val topicTitle = contentTitle(content, cleanTopicId)

        // Build section ID -> title map from parsed outline
        val sections = getParsedSections(cleanTopicId, content.outlineHtml)
        val idToTitle = sections.associate { it.id to it.title }

        val cleanUpper = sectionIds.map { it.trim().uppercase() }
        val isCalc = content.topicType == "calc" || content.outlineHtml.isBlank()
        if (idToTitle.isEmpty() || isCalc || cleanUpper.contains("FULL") || cleanUpper.contains("ALL")) {
            val markdown = htmlToMarkdown(content.bodyHtml)
            val header = if (isCalc) "=== Calculator: $topicTitle ===" else "=== Section: FULL ==="
            val fullText = "$header\n$markdown"
            return TopicSectionsResponse(
                topicId = cleanTopicId,
                topicTitle = topicTitle,
                sectionTitles = mapOf("FULL" to topicTitle),
                markdown = fullText
            )
        }

        if (sectionIds.isEmpty()) {
            return TopicSectionsResponse(
                topicId = cleanTopicId,
                topicTitle = topicTitle,
                sectionTitles = emptyMap(),
                markdown = "",
                error = "sectionIds cannot be empty. Specify 1 to $MAX_SECTIONS_PER_CALL section IDs from getTopicOverview."
            )
        }

        val targetRequests = sectionIds

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
                    // Try title match (strip leading bullet/dash prefixes both sides — LLMs echo bullets that
                    // parseOutlineTitle already stripped from the outline; includes • and ● bullets)
                    val matchedByTitle = idToTitle.entries.firstOrNull { (id, title) ->
                        title.equals(clean, ignoreCase = true) ||
                        title.trim()
                            .replace(LEADING_BULLET_OR_DASH_REGEX, "")
                            .equals(
                                clean.trim().replace(LEADING_BULLET_OR_DASH_REGEX, ""),
                                ignoreCase = true
                            )
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
            return TopicSectionsResponse(
                topicId = cleanTopicId,
                topicTitle = topicTitle,
                sectionTitles = emptyMap(),
                markdown = "",
                invalidSections = invalidIds
            )
        }

        val outlineOrderMap = sections.mapIndexed { idx, s -> s.id.uppercase() to idx }.toMap()
        val sortedValidIds = validIds.sortedBy { outlineOrderMap[it.uppercase()] ?: Int.MAX_VALUE }
        val subsumedMap = findSubsumedSections(sortedValidIds, sections)

        val sectionsMd = mutableListOf<String>()
        val sectionTitles = mutableMapOf<String, String>()
        val omittedSections = mutableListOf<String>()
        val successfullyExtractedIds = mutableSetOf<String>()
        var extractedContentCount = 0

        for (sectionId in sortedValidIds) {
            val title = idToTitle[sectionId] ?: ""
            val subsumedBy = subsumedMap[sectionId]

            if (subsumedBy != null && subsumedBy in successfullyExtractedIds) {
                // Subsumed subsection: lightweight pointer stub; does not consume an unextracted content slot.
                val secEntry = "=== Section: $sectionId ===\n(Content included in section [$subsumedBy] above)"
                sectionsMd.add(secEntry)
                sectionTitles[sectionId] = title
            } else {
                // Non-subsumed content section: check extraction budget
                if (extractedContentCount >= MAX_SECTIONS_PER_CALL) {
                    omittedSections.add(sectionId)
                    continue
                }

                val sectionHtml = extractSectionHtml(content.bodyHtml, sections, sectionId)
                val secMarkdown = if (sectionHtml != null) {
                    successfullyExtractedIds.add(sectionId)
                    extractedContentCount++
                    htmlToMarkdown(sectionHtml)
                } else {
                    "Section not found."
                }
                val secEntry = "=== Section: $sectionId ===\n$secMarkdown"
                sectionsMd.add(secEntry)
                sectionTitles[sectionId] = title
            }
        }

        return TopicSectionsResponse(
            topicId = cleanTopicId,
            topicTitle = topicTitle,
            sectionTitles = sectionTitles,
            markdown = sectionsMd.joinToString("\n\n"),
            invalidSections = invalidIds.ifEmpty { null },
            omittedSections = omittedSections.ifEmpty { null }
        )
    }

    fun getGraphicContent(graphicId: String): GraphicContentResponse {
        val cleanId = graphicId.trim()
        val rawGraphicId = cleanId.replace(RAW_GRAPHIC_PREFIX_REGEX, "")

        val graphicData = assetRepository.getGraphic(rawGraphicId)
            ?: (if (rawGraphicId != cleanId) assetRepository.getGraphic(cleanId) else null)
            ?: return GraphicContentResponse(graphicId = cleanId, error = "Graphic $cleanId not found")

        if (graphicData.subtype.isNotEmpty() && !graphicData.isTable) {
            return GraphicContentResponse(
                graphicId = cleanId,
                error = "Graphic $cleanId is of type '${graphicData.subtype}', not a table. Only table content is retrievable."
            )
        }

        val imageHtml = graphicData.imageHtml
        if (imageHtml.isBlank()) {
            return GraphicContentResponse(graphicId = cleanId, error = "Graphic $cleanId has empty content")
        }

        val markdown = htmlToMarkdown(imageHtml)
        val title = graphicData.title.ifEmpty { rawGraphicId }
        val fullMarkdown = "### Graphic Table: $title\n\n$markdown"
        return GraphicContentResponse(
            graphicId = cleanId,
            title = title,
            markdown = fullMarkdown
        )
    }

    internal data class ParsedSection(
        val id: String,
        val title: String,
        val level: Int = 1,
        val parentId: String? = null
    )

    /**
     * Determines which requested sections are structurally subsumed by an ancestor section
     * that is already being emitted in full.
     *
     * In UpToDate articles, extracting an ancestor section (e.g. level 1) includes all of its
     * child subsections (level > 1) up to the next sibling/ancestor. If both an ancestor and
     * its descendants are requested, extracting each descendant separately causes massive
     * duplication in LLM context.
     *
     * Returns a map of `descendantSectionId -> ancestorSectionId`.
     */
    internal fun findSubsumedSections(
        sortedValidIds: List<String>,
        outlineSections: List<ParsedSection>
    ): Map<String, String> {
        if (outlineSections.isEmpty() || sortedValidIds.size <= 1) return emptyMap()

        val outlineIndexMap = outlineSections.mapIndexed { idx, s -> s.id.uppercase() to idx }.toMap()
        val subsumedMap = mutableMapOf<String, String>()
        val emittedAncestors = mutableListOf<Triple<String, Int, Int>>()

        for (sid in sortedValidIds) {
            val sidUpper = sid.uppercase()
            val secIndex = outlineIndexMap[sidUpper] ?: continue
            val secLevel = outlineSections[secIndex].level

            var subsumedBy: String? = null
            for ((ancId, ancIndex, ancLevel) in emittedAncestors) {
                if (ancIndex < secIndex) {
                    var isAllDeeper = true
                    for (k in (ancIndex + 1)..secIndex) {
                        if (outlineSections[k].level <= ancLevel) {
                            isAllDeeper = false
                            break
                        }
                    }
                    if (isAllDeeper) {
                        subsumedBy = ancId
                        break
                    }
                }
            }

            if (subsumedBy != null) {
                subsumedMap[sid] = subsumedBy
            } else {
                emittedAncestors.add(Triple(sid, secIndex, secLevel))
            }
        }

        return subsumedMap
    }

    /**
     * Section entries of `outlineHtml`, depth-first, deduplicated. Graphics and related
     * topics are read from the payload JSON instead — the outline hrefs carry no subtype,
     * so an outline-derived graphic inventory cannot tell a table from a photograph.
     */
    private fun parseOutlineSections(outlineHtml: String): List<ParsedSection> {
        if (outlineHtml.isBlank()) return emptyList()

        var depth = 0
        var inSrButton = false
        val parents = mutableMapOf<Int, String>()

        val rawSections = mutableListOf<ParsedSection>()
        val rawSectionIsSr = mutableListOf<Boolean>()

        for (m in TAG_EVENT_REGEX.findAll(outlineHtml)) {
            val token = m.groupValues[1]
            val lower = token.lowercase()
            when {
                lower.startsWith("<ul") -> depth++
                lower.startsWith("</ul") -> depth = maxOf(0, depth - 1)
                lower.startsWith("<li") -> inSrButton = token.contains("sr-button", ignoreCase = true)
                lower.startsWith("<a") -> {
                    val aMatch = A_TAG_REGEX.matchEntire(token) ?: continue
                    val sectionMatch = SECTION_REGEX.find(aMatch.groupValues[1]) ?: continue
                    val cleanTitle = cleanOutlineTitle(aMatch.groupValues[2])
                    if (cleanTitle.isEmpty()) continue

                    val sid = sectionMatch.groupValues[1]
                    val level = maxOf(1, depth)
                    val parentId = if (level > 1) parents[level - 1] else null
                    parents[level] = sid
                    rawSections.add(ParsedSection(id = sid, title = cleanTitle, level = level, parentId = parentId))
                    rawSectionIsSr.add(inSrButton)
                }
            }
        }

        // A section rendered both in-flow and as an sr-button shortcut keeps the in-flow entry.
        val nonSrIds = rawSections.filterIndexed { idx, _ -> !rawSectionIsSr[idx] }.map { it.id }.toSet()
        val sections = mutableListOf<ParsedSection>()
        val seenSectionIds = mutableSetOf<String>()
        for (i in rawSections.indices) {
            val sec = rawSections[i]
            if (rawSectionIsSr[i] && sec.id in nonSrIds) continue
            if (seenSectionIds.add(sec.id)) sections.add(sec)
        }
        return sections
    }

    private fun cleanOutlineTitle(html: String): String {
        return AiJsonUtils.cleanHtmlText(
            html.replace(LEGACY_HYPHEN_REGEX, "")
                .replace(STRIP_TAGS_REGEX, "")
                .replace(LEADING_BULLET_OR_DASH_REGEX, "")
        )
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
            val hlMatch = HEADING_LEVEL_SNIPPET_REGEX.find(snippet)
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
            val referencesMatch = REFERENCE_HEADER_REGEX.find(bodyHtml, startIndex = startMatch.range.last + 1)
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
        s = s.replace(SPAN_H1_REGEX, "\n\n## $1\n\n")
        s = s.replace(SPAN_H2_REGEX, "\n\n### $1\n\n")
        s = s.replace(SPAN_H3_REGEX, "\n\n#### $1\n\n")
        s = s.replace(SPAN_H4_6_REGEX, "\n\n##### $1\n\n")
        s = s.replace(HEADING_END_MARK_REGEX, " ")

        // Standard headings fallback
        s = s.replace(FALLBACK_H1_REGEX, "\n\n# ")
        s = s.replace(FALLBACK_H2_REGEX, "\n\n## ")
        s = s.replace(FALLBACK_H3_6_REGEX, "\n\n### ")

        // Definition lists (<dt>, <dd>)
        s = s.replace(DT_REGEX, "\n- **$1**: ")
        s = s.replace(DD_REGEX, "$1\n")

        // Bullet indentations
        s = s.replace(BULLET_INDENT_REGEX, "\n- ")
        s = s.replace(GLYPH_REGEX, " ")

        // Convert links to plain text (strip link wrappers, preserve clean anchor text for LLM usage)
        s = A_TAG_REGEX.replace(s) { match ->
            val href = match.groupValues[1]
            val rawText = match.groupValues[2].replace(STRIP_TAGS_REGEX, "").trim()
            if (rawText.isEmpty()) {
                val actionMatch = GRAPHIC_ACTION_REGEX.find(href)
                if (actionMatch != null) {
                    val jsonStr = actionMatch.groupValues[1]
                        .replace("&quot;", "\"").replace("&#39;", "'")
                    val idMatch = GRAPHIC_ID_REGEX.find(jsonStr)
                    val topicId = idMatch?.groupValues?.get(1)
                    if (topicId != null) {
                        return@replace titleLookup(topicId)
                    }
                }
                return@replace ""
            }

            rawText
        }

        // Tables
        s = convertHtmlTablesToMarkdown(s)

        // Block elements
        s = s.replace(UL_OL_START_REGEX, "\n")
        s = s.replace(UL_OL_END_REGEX, "\n")
        s = s.replace(LI_START_REGEX, "\n- ")
        s = s.replace(LI_END_REGEX, "")
        s = s.replace(P_REGEX, "\n\n")
        s = s.replace(BR_REGEX, "\n")
        s = s.replace(DIV_REGEX, "\n")

        s = s.replace(STRONG_B_REGEX, "**")
        s = s.replace(EM_I_REGEX, "*")

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
        return TABLE_REGEX.replace(html) { matchResult ->
            val tableContent = matchResult.groupValues[1]
            val rows = TR_REGEX.findAll(tableContent).map { trMatch ->
                CELL_REGEX.findAll(trMatch.groupValues[1]).map { cellMatch ->
                    var cellText = STRIP_TAGS_REGEX.replace(cellMatch.groupValues[1], "")
                    cellText = decodeHtmlEntities(cellText)
                    // Keep each table row on a single line: replace newlines with space
                    cellText = cellText.replace(NEWLINE_REGEX, " ")
                    cellText = cellText.replace(WHITESPACE_REGEX, " ")
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

        var s = NAMED_ENTITY_REGEX.replace(text) { m ->
            ENTITY_LOOKUP[m.value] ?: m.value
        }

        if (s.contains("&#x", ignoreCase = true)) {
            s = HEX_ENTITY_REGEX.replace(s) { m ->
                m.groupValues[1].toIntOrNull(16)?.let { code ->
                    if (code in 32..65535) code.toChar().toString() else ""
                } ?: m.value
            }
        }

        if (s.contains("&#")) {
            s = DECIMAL_ENTITY_REGEX.replace(s) { m ->
                m.groupValues[1].toIntOrNull()?.let { code ->
                    if (code in 32..65535) code.toChar().toString() else ""
                } ?: m.value
            }
        }

        return s
    }

    private fun <T> List<T>.padTo(size: Int, default: T): List<T> {
        if (this.size >= size) return this
        return this + List(size - this.size) { default }
    }
}
