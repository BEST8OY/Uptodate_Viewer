package com.uptodate.viewer.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.data.database.search.SearchRepository
import com.uptodate.viewer.domain.model.SearchResultItem
import com.uptodate.viewer.util.SearchPref
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchRepository: SearchRepository
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val suggestions: List<String> = emptyList(),
        val results: List<SearchResultItem> = emptyList(),
        val preference: String = SearchPref.ALL,
        val isSearching: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val preferences = listOf(
        SearchPref.ALL to "All",
        SearchPref.ADULT to "Adult",
        SearchPref.PEDIATRIC to "Pediatric",
        SearchPref.PATIENT to "Patient"
    )

    fun getPreferences(): List<Pair<String, String>> = preferences

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(query = query)
        if (query.length > 2) {
            viewModelScope.launch(Dispatchers.IO) {
                val suggestions = searchRepository.getSuggestions(query)
                _state.value = _state.value.copy(
                    suggestions = suggestions.map { it.word }
                )
            }
        } else {
            _state.value = _state.value.copy(suggestions = emptyList())
        }
    }

    fun onSuggestionClick(word: String) {
        _state.value = _state.value.copy(query = word, suggestions = emptyList())
        performSearch()
    }

    fun onPreferenceChange(pref: String) {
        _state.value = _state.value.copy(preference = pref)
        if (_state.value.query.isNotEmpty()) performSearch()
    }

    fun performSearch() {
        val query = _state.value.query
        if (query.isEmpty()) return
        _state.value = _state.value.copy(isSearching = true)
        val pref = _state.value.preference
        viewModelScope.launch(Dispatchers.IO) {
            val results = searchRepository.searchTopics(query, pref)
            _state.value = _state.value.copy(
                results = results.map { SearchResultItem(topicId = it.topicId, title = it.title) },
                isSearching = false,
                suggestions = emptyList()
            )
        }
    }
}
