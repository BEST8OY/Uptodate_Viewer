package com.clinref.app.ui.common

import androidx.compose.runtime.Composable

@Composable
actual fun BackHandler(enabled: Boolean, onBack: () -> Unit) {
    // Desktop platforms lack a hardware/system back button gesture.
    // Navigation is managed via UI breadcrumbs/top bars or keyboard shortcuts.
}
