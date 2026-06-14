package com.uptodate.viewer.repository

import com.uptodate.viewer.data.ContentDao
import com.uptodate.viewer.data.SearchDao
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
        val contributors: List<Map<String, Any?>>? = null
    )

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
        return searchDao.getTopicTitle(topicId)
    }
}
