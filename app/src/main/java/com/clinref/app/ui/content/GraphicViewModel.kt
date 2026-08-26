package com.clinref.app.ui.content

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.domain.GraphicData
import com.clinref.app.repository.AssetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class GraphicViewModel @Inject constructor(
    private val assetRepository: AssetRepository
) : ViewModel() {

    private val _graphicData = MutableStateFlow<GraphicData?>(null)
    val graphicData: StateFlow<GraphicData?> = _graphicData

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isError = MutableStateFlow(false)
    val isError: StateFlow<Boolean> = _isError

    fun loadGraphic(graphicId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _isError.value = false
            _graphicData.value = null
            try {
                val result = withContext(Dispatchers.IO) {
                    assetRepository.getGraphic(graphicId)
                }
                _graphicData.value = result
                _isError.value = result == null
            } catch (_: Exception) {
                _isError.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }
}
