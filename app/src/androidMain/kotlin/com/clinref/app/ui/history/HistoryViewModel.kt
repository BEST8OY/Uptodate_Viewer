package com.clinref.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.domain.HistoryEntry
import com.clinref.app.repository.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository
) : ViewModel() {

    val history: StateFlow<List<HistoryEntry>> = historyRepository.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun removeHistory(topicId: String) {
        viewModelScope.launch {
            historyRepository.remove(topicId)
        }
    }

    fun addHistory(topicId: String, title: String, timestamp: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            historyRepository.addOrPromote(topicId, title, timestamp)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clear()
        }
    }
}
