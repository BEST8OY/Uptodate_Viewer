package com.uptodate.viewer.data.database.content

import com.uptodate.viewer.data.database.content.models.TopicContent

interface ContentRepository {
    suspend fun getTopicContent(topicId: String): TopicContent?
}
