package com.clinref.app.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Binds a LazyListState to reselection events for a specific navigation route.
 * Reselecting the active top-level route scrolls the list to top.
 */
@Composable
fun LazyListState.bindReselectScrollToTop(
    reselectEvents: Flow<NavKey> = emptyFlow(),
    targetRoute: NavKey
) {
    LaunchedEffect(this, reselectEvents, targetRoute) {
        reselectEvents.collect { route ->
            if (route == targetRoute) {
                if (firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 0) {
                    if (firstVisibleItemIndex > 25) {
                        scrollToItem(25)
                    }
                    animateScrollToItem(0)
                }
            }
        }
    }
}
