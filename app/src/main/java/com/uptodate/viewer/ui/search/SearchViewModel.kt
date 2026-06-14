package com.uptodate.viewer.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.domain.SearchResult
import com.uptodate.viewer.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository
) : ViewModel() {

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        if (query.length > 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                _suggestions.value = searchRepository.getSuggestions(query)
            }
        } else {
            _suggestions.value = emptyList()
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            _searchResults.value = searchRepository.searchTopics(query)
            _suggestions.value = emptyList()
        }
    }

    fun getTopicTitle(topicId: String): String? {
        return searchRepository.getTopicTitle(topicId)
    }
}
