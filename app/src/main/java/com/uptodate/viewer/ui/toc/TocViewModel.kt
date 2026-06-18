package com.uptodate.viewer.ui.toc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.domain.TocItem
import com.uptodate.viewer.repository.TocRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TocViewModel @Inject constructor(
    private val tocRepository: TocRepository
) : ViewModel() {

    private val _tocItems = MutableStateFlow<List<TocItem>>(emptyList())
    val tocItems: StateFlow<List<TocItem>> = _tocItems

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _expandedIds = MutableStateFlow<Set<String>>(emptySet())
    val expandedIds: StateFlow<Set<String>> = _expandedIds

    init {
        loadTocItems()
    }

    private fun loadTocItems() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _tocItems.value = tocRepository.getTocItems()
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load table of contents"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun retry() {
        loadTocItems()
    }

    fun loadChildren(parentId: String) {
        viewModelScope.launch {
            try {
                val children = tocRepository.getTocItems(parentId)
                _tocItems.value = updateTree(_tocItems.value, parentId, children)
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load children"
            }
        }
    }

    fun toggleExpanded(id: String) {
        _expandedIds.value = if (id in _expandedIds.value) {
            _expandedIds.value - id
        } else {
            _expandedIds.value + id
        }
    }

    fun isExpanded(id: String): Boolean = id in _expandedIds.value

    fun resolveTopicId(tocId: String): String? {
        return tocRepository.getTopicIdFromTocId(tocId)
    }

    private fun updateTree(items: List<TocItem>, parentId: String, children: List<TocItem>): List<TocItem> {
        return items.map { item ->
            if (item.id == parentId) {
                item.copy(childrenInfo = children)
            } else {
                item.copy(
                    childrenInfo = item.childrenInfo?.let { updateTree(it, parentId, children) }
                )
            }
        }
    }
}
