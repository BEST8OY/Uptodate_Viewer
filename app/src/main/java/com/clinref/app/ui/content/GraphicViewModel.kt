package com.clinref.app.ui.content

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.di.IoDispatcher
import com.clinref.app.repository.AssetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class GraphicViewModel @Inject constructor(
    private val assetRepository: AssetRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow<GraphicUiState>(GraphicUiState.Loading)
    val uiState: StateFlow<GraphicUiState> = _uiState.asStateFlow()

    private var lastGraphicId: String? = null

    fun loadGraphic(graphicId: String) {
        lastGraphicId = graphicId
        load(graphicId)
    }

    fun retry() {
        lastGraphicId?.let { load(it) }
    }

    private fun load(graphicId: String) {
        viewModelScope.launch {
            _uiState.value = GraphicUiState.Loading
            try {
                val result = withContext(ioDispatcher) {
                    assetRepository.getGraphic(graphicId)
                }
                _uiState.value = result?.let { GraphicUiState.Success(it) } ?: GraphicUiState.Error
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load graphic $graphicId", e)
                _uiState.value = GraphicUiState.Error
            }
        }
    }

    private companion object {
        const val TAG = "GraphicViewModel"
    }
}
