package com.uptodate.viewer.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.domain.model.HistoryEntry
import com.uptodate.viewer.domain.persistence.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository
) : ViewModel() {

    data class UiState(
        val items: List<HistoryEntry> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch(Dispatchers.IO) {
            historyRepository.load()
            historyRepository.history.collect { entries ->
                _state.value = UiState(items = entries)
            }
        }
    }

    fun remove(topicId: String) {
        historyRepository.remove(topicId)
    }

    fun clear() {
        historyRepository.clear()
    }
}
