package com.clinref.app.ui.toc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.domain.TocItem
import com.clinref.app.repository.TocRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TocViewModel @Inject constructor(
    private val tocRepository: TocRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_EXPANDED_IDS = "expanded_ids"
    }

    private val _tocItems = MutableStateFlow<List<TocItem>>(emptyList())
    val tocItems: StateFlow<List<TocItem>> = _tocItems

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _expandedIds = MutableStateFlow<Set<String>>(
        savedStateHandle.get<List<String>>(KEY_EXPANDED_IDS)?.toSet() ?: emptySet()
    )
    val expandedIds: StateFlow<Set<String>> = _expandedIds

    init {
        loadTocItems()
    }

    private fun loadTocItems() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                val roots = withContext(Dispatchers.IO) { tocRepository.getTocItems() }
                _tocItems.value = roots
                for (root in roots) {
                    if (root.id in _expandedIds.value) {
                        loadChildren(root.id)
                    }
                }
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
                val children = withContext(Dispatchers.IO) { tocRepository.getTocItems(parentId) }
                _tocItems.value = updateTree(_tocItems.value, parentId, children)
                for (child in children) {
                    if (child.id in _expandedIds.value) {
                        loadChildren(child.id)
                    }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load children"
            }
        }
    }

    fun toggleExpanded(id: String) {
        val current = _expandedIds.value
        val newSet = if (id in current) current - id else current + id
        _expandedIds.value = newSet
        savedStateHandle[KEY_EXPANDED_IDS] = newSet.toList()
    }

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
