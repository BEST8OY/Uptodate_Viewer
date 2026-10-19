package com.clinref.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult

suspend fun showUndoSnackbar(
    snackbarHostState: SnackbarHostState,
    message: String,
    onUndo: () -> Unit
) {
    val result = snackbarHostState.showSnackbar(
        message = message,
        actionLabel = "Undo",
        duration = SnackbarDuration.Long
    )
    if (result == SnackbarResult.ActionPerformed) {
        onUndo()
    }
}
