package com.uptodate.viewer.ui.graphic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.data.database.assets.AssetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GraphicViewModel @Inject constructor(
    private val assetRepository: AssetRepository
) : ViewModel() {

    data class UiState(
        val rawImageHtml: String = "",
        val base64Image: String? = null,
        val isLoading: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var graphicId: String = ""

    fun initGraphic(id: String) {
        graphicId = id
        loadGraphic()
    }

    private fun loadGraphic() {
        viewModelScope.launch(Dispatchers.IO) {
            val payload = assetRepository.getGraphic(graphicId)
            if (payload == null) {
                _state.value = UiState(isLoading = false)
                return@launch
            }
            _state.value = UiState(
                rawImageHtml = payload.imageHtml ?: "",
                base64Image = payload.base64Image,
                isLoading = false
            )
        }
    }
}
