package com.clinref.app.ui.common

import androidx.compose.runtime.Composable

/**
 * Multiplatform BackHandler.
 *
 * On Android, intercepts the system back button / predictive back gesture.
 * On Desktop, operates as a no-op or delegates to keyboard shortcuts.
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)
