package com.clinref.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.domain.Audience
import com.clinref.app.domain.SearchResult
import com.clinref.app.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_QUERY = "search_query"
        private const val KEY_AUDIENCE = "selected_audience"
    }

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults

    private val _selectedAudience = MutableStateFlow(
        savedStateHandle.get<String>(KEY_AUDIENCE)?.let { Audience.valueOf(it) } ?: Audience.ALL
    )
    val selectedAudience: StateFlow<Audience> = _selectedAudience

    private var searchJob: Job? = null
    private var lastQuery: String = savedStateHandle.get<String>(KEY_QUERY) ?: ""

    init {
        if (lastQuery.isNotEmpty()) {
            search(lastQuery)
        }
    }

    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        lastQuery = query
        savedStateHandle[KEY_QUERY] = query
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
        lastQuery = query
        savedStateHandle[KEY_QUERY] = query
        savedStateHandle[KEY_AUDIENCE] = _selectedAudience.value.name
        viewModelScope.launch {
            _searchResults.value = searchRepository.searchTopics(query, _selectedAudience.value)
            _suggestions.value = emptyList()
        }
    }

    fun onAudienceChanged(audience: Audience) {
        _selectedAudience.value = audience
        savedStateHandle[KEY_AUDIENCE] = audience.name
        if (lastQuery.isNotEmpty()) {
            search(lastQuery)
        }
    }
}
