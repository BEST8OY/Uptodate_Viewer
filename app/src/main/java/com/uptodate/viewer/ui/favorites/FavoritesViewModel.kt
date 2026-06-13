package com.uptodate.viewer.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.domain.model.FavoritesEntry
import com.uptodate.viewer.domain.persistence.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {

    data class UiState(
        val items: List<FavoritesEntry> = emptyList(),
        val filter: String = ""
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch(Dispatchers.IO) {
            favoritesRepository.load()
            _state.value = UiState(items = favoritesRepository.favorites.value)
        }
    }

    fun onFilterChange(filter: String) {
        val all = favoritesRepository.favorites.value
        val filtered = if (filter.isBlank()) all
        else all.filter { it.title.contains(filter, ignoreCase = true) }
        _state.value = UiState(items = filtered, filter = filter)
    }

    fun remove(topicId: String) {
        favoritesRepository.remove(topicId)
        onFilterChange(_state.value.filter)
    }
}
