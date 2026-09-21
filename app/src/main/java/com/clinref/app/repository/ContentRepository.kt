package com.clinref.app.repository

import com.clinref.app.data.ContributorGroup
import com.clinref.app.data.ContentDao
import com.clinref.app.data.SearchDao
import com.clinref.app.domain.ai.AiJsonUtils
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContentRepository @Inject constructor(
    private val contentDao: ContentDao,
    private val searchDao: SearchDao
) {
    data class TopicContent(
        val bodyHtml: String,
        val outlineHtml: String = "",
        val relatedGraphics: List<Map<String, Any?>> = emptyList(),
        val contributors: List<ContributorGroup>? = null
    )

    private val topicSectionTitlesCache = ConcurrentHashMap<String, Map<String, String>>()
    private val topicTitlesCache = ConcurrentHashMap<String, String>()

    fun getTopicContent(topicId: String): TopicContent? {
        val content = contentDao.getTopicContent(topicId) ?: return null
        return TopicContent(
            bodyHtml = content.bodyHtml,
            outlineHtml = content.outlineHtml,
            relatedGraphics = content.relatedGraphics,
            contributors = content.contributors
        )
    }

    fun getTopicTitle(topicId: String): String? {
        val clean = topicId.trim()
        if (clean.isEmpty()) return null
        return topicTitlesCache.computeIfAbsent(clean) { tid ->
            searchDao.getTopicTitle(tid) ?: contentDao.getTopicTitle(tid) ?: ""
        }.takeIf { it.isNotEmpty() }
    }

    fun getSectionTitle(topicId: String, sectionId: String): String? {
        val cleanSection = sectionId.trim()
        if (cleanSection.isEmpty() || cleanSection.equals("FULL", ignoreCase = true)) return null

        val sectionsMap = topicSectionTitlesCache.computeIfAbsent(topicId) { tid ->
            val outlineHtml = getTopicContent(tid)?.outlineHtml
            if (outlineHtml.isNullOrBlank()) {
                emptyMap()
            } else {
                parseOutlineSections(outlineHtml)
            }
        }
        return sectionsMap[cleanSection]
    }

    private fun parseOutlineSections(outlineHtml: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (match in A_TAG_REGEX.findAll(outlineHtml)) {
            val href = match.groupValues[1]
            val sectionMatch = SECTION_REGEX.find(href) ?: continue
            val secId = sectionMatch.groupValues[1].trim()

            val innerHtml = match.groupValues[2]
            val text = innerHtml
                .replace(LEGACY_HYPHEN_REGEX, "")
                .replace(STRIP_TAGS_REGEX, "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            val cleanTitle = AiJsonUtils.cleanSectionTitle(text)
            if (secId.isNotEmpty() && cleanTitle.isNotBlank()) {
                map[secId] = cleanTitle
            }
        }
        return map
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
        private val LEGACY_HYPHEN_REGEX = Regex(
            """<span[^>]*class="legacyTopicViewHyphen"[^>]*>.*?</span>""",
            setOf(RegexOption.IGNORE_CASE)
        )
    }
}

