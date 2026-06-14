package com.uptodate.viewer.repository

import com.uptodate.viewer.data.SearchDao
import com.uptodate.viewer.domain.SearchResult
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

    fun searchTopics(query: String): List<SearchResult> {
        return searchDao.searchTopics(query).map { map ->
            SearchResult(
                topicId = map["topic_id"] ?: "",
                title = map["title"] ?: ""
            )
        }
    }
}
