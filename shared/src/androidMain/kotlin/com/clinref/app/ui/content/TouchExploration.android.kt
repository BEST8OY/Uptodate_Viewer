package com.clinref.app.ui.content

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberTouchExplorationState(): Boolean {
    val context = LocalContext.current
    val accessibilityManager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember(accessibilityManager) {
        mutableStateOf(accessibilityManager?.isTouchExplorationEnabled == true)
    }

    DisposableEffect(accessibilityManager) {
        if (accessibilityManager == null) return@DisposableEffect onDispose {}
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { isEnabled ->
            enabled = isEnabled
        }
        accessibilityManager.addTouchExplorationStateChangeListener(listener)
        enabled = accessibilityManager.isTouchExplorationEnabled
        onDispose {
            accessibilityManager.removeTouchExplorationStateChangeListener(listener)
        }
    }

    return enabled
}
