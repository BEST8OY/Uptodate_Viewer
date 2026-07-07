package com.clinref.app.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.domain.FavoriteEntry
import com.clinref.app.repository.FavoriteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoriteRepository: FavoriteRepository
) : ViewModel() {

    val favorites: StateFlow<List<FavoriteEntry>> = favoriteRepository.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeFavorite(topicId: String) {
        viewModelScope.launch {
            favoriteRepository.remove(topicId)
        }
    }

    fun addFavorite(topicId: String, title: String) {
        viewModelScope.launch {
            favoriteRepository.add(topicId, title)
        }
    }

    fun clearFavorites() {
        viewModelScope.launch {
            favoriteRepository.clearAll()
        }
    }
}
