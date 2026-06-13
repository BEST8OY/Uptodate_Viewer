package com.uptodate.viewer.ui.toc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.data.database.toc.TocRepository
import com.uptodate.viewer.domain.model.TocItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class TocViewModel @Inject constructor(
    private val tocRepository: TocRepository
) : ViewModel() {

    data class UiState(
        val roots: List<TocItem> = emptyList(),
        val children: Map<String, List<TocItem>> = emptyMap(),
        val expandedIds: Set<String> = emptySet(),
        val isLoading: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val childrenCache = ConcurrentHashMap<String, List<TocItem>>()
    private val loadingIds = ConcurrentHashMap.newKeySet<String>()

    init {
        loadRoots()
    }

    private fun loadRoots() {
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val nodes = tocRepository.getRootItems()
            val roots = nodes.map { it.toDomain() }
            _state.value = UiState(roots = roots, isLoading = false)
        }
    }

    fun loadChildren(parentId: String) {
        if (childrenCache.containsKey(parentId)) {
            toggleExpanded(parentId)
            return
        }
        if (!loadingIds.add(parentId)) return
        viewModelScope.launch(Dispatchers.IO) {
            val nodes = tocRepository.getChildItems(parentId)
            val items = nodes.map { it.toDomain() }
            childrenCache[parentId] = items
            loadingIds.remove(parentId)
            _state.update {
                it.copy(
                    children = it.children + (parentId to items),
                    expandedIds = it.expandedIds + parentId
                )
            }
        }
    }

    fun toggleExpanded(parentId: String) {
        _state.update {
            val newExpanded = if (parentId in it.expandedIds) {
                it.expandedIds - parentId
            } else {
                it.expandedIds + parentId
            }
            it.copy(expandedIds = newExpanded)
        }
    }

    private fun com.uptodate.viewer.data.database.toc.models.TocNode.toDomain() = TocItem(
        id = id,
        title = title,
        isLeaf = isLeaf,
        type = type,
        hasChildren = childrenInfo != null
    )
}
