package com.clinref.app.repository

import com.clinref.app.data.ContributorGroup
import com.clinref.app.data.ContentDao
import com.clinref.app.data.SearchDao
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
