package com.uptodate.viewer.data.database.search

import com.uptodate.viewer.data.database.search.models.SearchResultRow
import com.uptodate.viewer.data.database.search.models.Suggestion

interface SearchRepository {
    suspend fun getTopicTitle(topicId: String): String?
    suspend fun getSuggestions(query: String): List<Suggestion>
    suspend fun searchTopics(query: String, preference: String): List<SearchResultRow>
}
