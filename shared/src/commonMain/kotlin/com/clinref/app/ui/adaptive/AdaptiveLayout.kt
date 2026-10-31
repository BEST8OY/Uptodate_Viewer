package com.clinref.app.ui.adaptive

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standard Material 3 window width size classes.
 */
enum class WindowWidthClass {
    COMPACT,   // < 600dp (standard phone in portrait)
    MEDIUM,    // 600dp - 840dp (small tablet, foldable unfolded, phone landscape)
    EXPANDED   // >= 840dp (large tablet, desktop, laptop)
}

/**
 * Global composition locals for responsive and adaptive layout awareness.
 */
val LocalWindowWidthClass = compositionLocalOf { WindowWidthClass.COMPACT }
val LocalBottomBarVisible = compositionLocalOf { false }
val LocalIsWideScreen = compositionLocalOf { false }

object AdaptiveTokens {
    val BreakpointMedium: Dp = 600.dp
    val BreakpointExpanded: Dp = 840.dp

    // Typographic reading bounds
    val MaxChatAssistantWidth: Dp = 760.dp
    val MaxChatInputWidth: Dp = 840.dp
    val MaxSettingsContentWidth: Dp = 760.dp
    val MaxFormContentWidth: Dp = 720.dp
    val MaxCatalogListWidth: Dp = 960.dp
    val MaxDialogWidth: Dp = 560.dp
    val MaxSearchToolbarWidth: Dp = 600.dp

    // Grid column sizing
    val AdaptiveGridCellMin: Dp = 340.dp
}

fun calculateWindowWidthClass(width: Dp): WindowWidthClass = when {
    width < AdaptiveTokens.BreakpointMedium -> WindowWidthClass.COMPACT
    width < AdaptiveTokens.BreakpointExpanded -> WindowWidthClass.MEDIUM
    else -> WindowWidthClass.EXPANDED
}

