package com.clinref.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LifecycleStartEffect

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

/**
 * Coordinates lifecycle-safe undo snackbar presentation and pending-item cleanup.
 */
@Composable
fun <T> UndoDeleteEffect(
    pendingItems: List<T>,
    snackbarHostState: SnackbarHostState,
    messageFor: (List<T>) -> String,
    onRestore: (List<T>) -> Unit,
    onClearPending: () -> Unit,
    onLifecycleStop: () -> Unit = {}
) {
    val currentMessageFor by rememberUpdatedState(messageFor)
    val currentOnRestore by rememberUpdatedState(onRestore)
    val currentOnClearPending by rememberUpdatedState(onClearPending)
    val currentOnLifecycleStop by rememberUpdatedState(onLifecycleStop)

    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            currentOnClearPending()
            currentOnLifecycleStop()
        }
    }

    LaunchedEffect(pendingItems) {
        if (pendingItems.isNotEmpty()) {
            showUndoSnackbar(
                snackbarHostState = snackbarHostState,
                message = currentMessageFor(pendingItems),
                onUndo = { currentOnRestore(pendingItems) }
            )
            currentOnClearPending()
        }
    }
}
