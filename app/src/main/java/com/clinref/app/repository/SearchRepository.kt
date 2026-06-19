package com.clinref.app.repository

import com.clinref.app.data.SearchDao
import com.clinref.app.domain.Audience
import com.clinref.app.domain.SearchResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val searchDao: SearchDao
) {
    fun getTopicTitle(topicId: String): String? {
        return searchDao.getTopicTitle(topicId)
    }

    fun getSuggestions(query: String): List<String> {
        return searchDao.getSuggestions(query)
    }

    fun searchTopics(query: String, audience: Audience = Audience.ALL): List<SearchResult> {
        return searchDao.searchTopics(query, audience.code).map { map ->
            SearchResult(
                topicId = map["topic_id"] ?: "",
                title = map["title"] ?: ""
            )
        }
    }
}
