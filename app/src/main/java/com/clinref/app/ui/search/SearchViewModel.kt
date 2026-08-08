package com.clinref.app.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.domain.Audience
import com.clinref.app.domain.SearchResult
import com.clinref.app.repository.SearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val audienceNameFlow = savedStateHandle.getMutableStateFlow(KEY_AUDIENCE, Audience.ALL.name)
    val selectedAudience: StateFlow<Audience> = audienceNameFlow
        .map { name -> Audience.entries.firstOrNull { it.name == name } ?: Audience.ALL }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Audience.entries.firstOrNull { it.name == audienceNameFlow.value } ?: Audience.ALL)

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var searchJob: Job? = null
    val searchQueryState = savedStateHandle.getMutableStateFlow(KEY_QUERY, "")

    init {
        if (searchQueryState.value.isNotEmpty()) {
            search(searchQueryState.value)
        }
    }

    fun onQueryChanged(query: String) {
        searchJob?.cancel()
        searchQueryState.value = query
        if (query.length > 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                try {
                    _suggestions.value = withContext(Dispatchers.IO) {
                        searchRepository.getSuggestions(query)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // suggestions are best-effort; silently ignore failures
                }
            }
        } else {
            _suggestions.value = emptyList()
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        searchQueryState.value = query
        audienceNameFlow.value = selectedAudience.value.name
        _searchResults.value = emptyList()
        _error.value = null
        _isLoading.value = true
        viewModelScope.launch {
            try {
                _searchResults.value = withContext(Dispatchers.IO) {
                    searchRepository.searchTopics(query, selectedAudience.value)
                }
                _suggestions.value = emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "Search failed"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun onAudienceChanged(audience: Audience) {
        audienceNameFlow.value = audience.name
        if (searchQueryState.value.isNotEmpty()) {
            search(searchQueryState.value)
        }
    }
}

