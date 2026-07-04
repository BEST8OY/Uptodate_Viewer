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
import kotlinx.coroutines.flow.StateFlow
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
        private const val KEY_RECENT_SEARCHES = "recent_searches"
        private const val MAX_RECENT_SEARCHES = 10
    }

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults

    private val _selectedAudience = MutableStateFlow(
        savedStateHandle.get<String>(KEY_AUDIENCE)?.let { Audience.valueOf(it) } ?: Audience.ALL
    )
    val selectedAudience: StateFlow<Audience> = _selectedAudience

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _recentSearches = MutableStateFlow(
        savedStateHandle.get<List<String>>(KEY_RECENT_SEARCHES) ?: emptyList()
    )
    val recentSearches: StateFlow<List<String>> = _recentSearches

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
        lastQuery = query
        savedStateHandle[KEY_QUERY] = query
        savedStateHandle[KEY_AUDIENCE] = _selectedAudience.value.name
        _searchResults.value = emptyList()
        _error.value = null
        _isLoading.value = true
        viewModelScope.launch {
            try {
                _searchResults.value = withContext(Dispatchers.IO) {
                    searchRepository.searchTopics(query, _selectedAudience.value)
                }
                _suggestions.value = emptyList()

                val current = _recentSearches.value.toMutableList()
                current.remove(query)
                current.add(0, query)
                if (current.size > MAX_RECENT_SEARCHES) current.removeLast()
                _recentSearches.value = current
                savedStateHandle[KEY_RECENT_SEARCHES] = current
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
        _selectedAudience.value = audience
        savedStateHandle[KEY_AUDIENCE] = audience.name
        if (lastQuery.isNotEmpty()) {
            search(lastQuery)
        }
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
        savedStateHandle[KEY_RECENT_SEARCHES] = emptyList<String>()
    }

    fun removeRecentSearch(query: String) {
        val current = _recentSearches.value.toMutableList()
        current.remove(query)
        _recentSearches.value = current
        savedStateHandle[KEY_RECENT_SEARCHES] = current
    }
}
