package com.clinref.app.data

import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.SearchRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MedicalDatabaseTools @Inject constructor(
    private val searchRepository: SearchRepository,
    private val contentRepository: ContentRepository
) : ToolSet {

    companion object {
        private val A_TAG_REGEX = Regex(
            """<a\s+[^>]*href=['"]([^'"]*)['"][^>]*>(.*?)</a>""",
            RegexOption.IGNORE_CASE or RegexOption.DOT_MATCHES_ALL
        )
        private val SECTION_REGEX = Regex(
            """section(?:&quot;|"):\s*(?:&quot;|")([a-zA-Z0-9_-]+)(?:&quot;|")""",
            RegexOption.IGNORE_CASE
        )
        private val STRIP_TAGS_REGEX = Regex("<[^>]*>")
    }

    @Tool
    @LLMDescription("Search medical topics by keywords to find matching topic IDs and titles.")
    fun searchTopics(
        @LLMDescription("The search query keywords") query: String
    ): String {
        val results = searchRepository.searchTopics(query).map {
            mapOf("id" to it.id, "title" to it.title)
        }
        return Json.encodeToString(results)
    }

    @Tool
    @LLMDescription("Retrieve the table of contents outline of a topic, returned as a JSON array of stable section IDs and titles.")
    fun getTopicOutline(
        @LLMDescription("The unique topic ID") topicId: String
    ): String {
        val content = contentRepository.getTopicContent(topicId) ?: return "Topic not found"
        return parseOutlineWithStableIds(content.outlineHtml)
    }

    @Tool
    @LLMDescription("Retrieve the text content of a specific section using its stable ID, with a fallback title if IDs drifted.")
    fun getTopicSectionText(
        @LLMDescription("The unique topic ID") topicId: String,
        @LLMDescription("The stable section ID returned by getTopicOutline") sectionId: String,
        @LLMDescription("The fallback title of the section in case ID lookup fails") sectionTitle: String
    ): String {
        val content = contentRepository.getTopicContent(topicId) ?: return "Topic not found"
        val bodyHtml = content.bodyHtml

        var targetId = sectionId
        var sectionHtml = extractSectionHtml(bodyHtml, targetId)

        // Recovery flow for ID drift
        if (sectionHtml == null && sectionTitle.isNotEmpty()) {
            val outline = parseOutlineList(content.outlineHtml)
            val matched = outline.firstOrNull { 
                it["title"]?.equals(sectionTitle, ignoreCase = true) == true ||
                it["title"]?.contains(sectionTitle, ignoreCase = true) == true ||
                sectionTitle.contains(it["title"] ?: "_", ignoreCase = true)
            }
            if (matched != null) {
                val newId = matched["id"]
                if (newId != null) {
                    targetId = newId
                    sectionHtml = extractSectionHtml(bodyHtml, targetId)
                }
            }
        }

        if (sectionHtml == null) {
            return "Section not found."
        }

        // Safety warning detection (clinical safety policy)
        val hasComplexData = sectionHtml.contains("<table", ignoreCase = true) ||
                             sectionHtml.contains("<img", ignoreCase = true) ||
                             sectionHtml.contains("class=\"dosing-table\"", ignoreCase = true) ||
                             sectionHtml.contains("class=\"dosing-info\"", ignoreCase = true)

        val markdown = htmlToMarkdown(sectionHtml)

        return if (hasComplexData) {
            "[WARNING: This section contains complex dosing tables, formulas, or images that cannot be summarized. " +
            "Please verify the raw details directly in the original formatting by opening this topic.]\n\n$markdown"
        } else {
            markdown
        }
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

    private fun parseOutlineWithStableIds(outlineHtml: String): String {
        return Json.encodeToString(parseOutlineList(outlineHtml))
    }

    private fun extractSectionHtml(bodyHtml: String, sectionId: String): String? {
        val startTagRegex = Regex("""<\w+\s+[^>]*id=["']${sectionId}["'][^>]*>""", RegexOption.IGNORE_CASE)
        val startMatch = startTagRegex.find(bodyHtml) ?: return null
        val startIdx = startMatch.range.first

        // Matches headers with class headingAnchor, class drugH1Div, or id references
        val nextHeadingRegex = Regex(
            """<\w+\s+[^>]*(?:class=["'][^"']*(?:headingAnchor|drugH1Div)[^"']*["']|id=["']references["'])[^>]*>""",
            RegexOption.IGNORE_CASE
        )

        val nextMatch = nextHeadingRegex.find(bodyHtml, startIndex = startMatch.range.last + 1)
        return if (nextMatch != null) {
            bodyHtml.substring(startIdx, nextMatch.range.first)
        } else {
            bodyHtml.substring(startIdx)
        }
    }

    private fun htmlToMarkdown(html: String): String {
        var s = html
        s = s.replace(Regex("</?p\\b[^>]*>", RegexOption.IGNORE_CASE), "\n\n")
        s = s.replace(Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE), "\n- ")
        s = s.replace(Regex("</li>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</?tr\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</?t[dh]\\b[^>]*>", RegexOption.IGNORE_CASE), " | ")
        s = s.replace(Regex("</?strong\\b[^>]*>", RegexOption.IGNORE_CASE), "**")
        s = s.replace(Regex("</?b\\b[^>]*>", RegexOption.IGNORE_CASE), "**")
        s = s.replace(Regex("</?em\\b[^>]*>", RegexOption.IGNORE_CASE), "*")
        s = s.replace(Regex("</?i\\b[^>]*>", RegexOption.IGNORE_CASE), "*")

        // Strip remaining HTML tags FIRST to avoid breaking text containing '<' (e.g. 'CrCl <50')
        s = s.replace(STRIP_TAGS_REGEX, "")

        // HTML entities LAST
        s = s.replace("&#160;", " ").replace("&nbsp;", " ")
        s = s.replace("&#8212;", "—").replace("&mdash;", "—")
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")

        // Normalize whitespace and blank lines
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
