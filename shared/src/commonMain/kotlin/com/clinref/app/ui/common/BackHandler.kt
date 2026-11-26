package com.clinref.app.ui.common

import androidx.compose.runtime.Composable
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/**
 * Multiplatform BackHandler powered by NavigationEvent.
 *
 * Participates directly in the Navigation 3 / NavigationEvent dispatcher hierarchy across platforms.
 * Safely no-ops when rendered outside a host that provides a NavigationEventDispatcher (e.g. Previews or isolated tests).
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    if (LocalNavigationEventDispatcherOwner.current == null) return
    val backEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = backEventState,
        isBackEnabled = enabled,
        onBackCompleted = onBack
    )
}
