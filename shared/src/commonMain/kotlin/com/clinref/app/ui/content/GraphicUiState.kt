package com.clinref.app.ui.content

import com.clinref.app.domain.GraphicData

sealed interface GraphicUiState {
    data object Loading : GraphicUiState
    data class Success(val data: GraphicData) : GraphicUiState {
        val graphicId: String get() = data.id
    }
    data object Error : GraphicUiState
}
