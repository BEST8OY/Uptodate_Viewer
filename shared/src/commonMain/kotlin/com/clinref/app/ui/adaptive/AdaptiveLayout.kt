package com.clinref.app.ui.adaptive

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Standard Material 3 window width size classes.
 */
enum class WindowWidthClass {
    COMPACT,   // < 600dp (standard phone in portrait)
    MEDIUM,    // 600dp - 839dp (small tablet, foldable unfolded, phone landscape)
    EXPANDED   // >= 840dp (large tablet, desktop, laptop)
}

/**
 * Standard Material 3 window height size classes.
 */
enum class WindowHeightClass {
    COMPACT,   // < 480dp (phone in landscape, split-screen)
    MEDIUM,    // 480dp - 899dp (standard phone in portrait, short desktop window)
    EXPANDED   // >= 900dp (large tablet in portrait, tall desktop display)
}

/**
 * Unified window layout adaptive metrics for responsive UI across Android and Desktop JVM.
 */
@Immutable
data class WindowAdaptiveInfo(
    val widthClass: WindowWidthClass = WindowWidthClass.COMPACT,
    val heightClass: WindowHeightClass = WindowHeightClass.MEDIUM,
    val widthDp: Dp = 360.dp,
    val heightDp: Dp = 800.dp,
) {
    val isCompact: Boolean get() = widthClass == WindowWidthClass.COMPACT
    val isMedium: Boolean get() = widthClass == WindowWidthClass.MEDIUM
    val isExpanded: Boolean get() = widthClass == WindowWidthClass.EXPANDED
    val useNavigationRail: Boolean get() = widthClass != WindowWidthClass.COMPACT
    val isCompactHeight: Boolean get() = heightClass == WindowHeightClass.COMPACT
    val supportsSupportingPane: Boolean get() = widthDp >= AdaptiveTokens.SupportingPaneMinWidth
    val horizontalMargin: Dp get() = when (widthClass) {
        WindowWidthClass.COMPACT -> 16.dp
        WindowWidthClass.MEDIUM -> 24.dp
        WindowWidthClass.EXPANDED -> 24.dp
    }
}

/**
 * Composition local providing the current [WindowAdaptiveInfo].
 */
val LocalWindowAdaptiveInfo = compositionLocalOf { WindowAdaptiveInfo() }

/**
 * Height in dp of the active bottom bar chrome to clear for scrollable lists/FABs.
 */
val LocalBottomBarPadding = compositionLocalOf { 0.dp }

/**
 * Computes a unified bottom list/content padding that harmonizes Desktop, narrow Android (with
 * bottom navigation bar overlay), and wide Android (with edge-to-edge system navigation bar).
 * Explicitly excludes IME insets to prevent recomposition loops during keyboard animations.
 */
@Composable
fun rememberContentBottomPadding(extra: Dp = 16.dp): Dp {
    val navBarBottom = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Bottom)
        .exclude(WindowInsets.ime)
        .asPaddingValues()
        .calculateBottomPadding()
    return maxOf(LocalBottomBarPadding.current, navBarBottom) + extra
}

object AdaptiveTokens {
    // Window size breakpoints (Material 3)
    val BreakpointWidthMedium: Dp = 600.dp
    val BreakpointWidthExpanded: Dp = 840.dp
    val BreakpointHeightMedium: Dp = 480.dp
    val BreakpointHeightExpanded: Dp = 900.dp
    val SupportingPaneMinWidth: Dp = 1040.dp

    // Content width caps (optimal line length & scannability)
    val MaxReadableContentWidth: Dp = 840.dp   // Chat stream, Chat input, TOC/History/Favorites/Conversations
    val MaxFormWidth: Dp = 720.dp              // Settings, Setup, ModelSelectionSheet
    val MaxSheetCompactWidth: Dp = 560.dp      // PatientProfileSheet, Dialogs, UserMessageBubble max
    val MaxSheetReadingWidth: Dp = 760.dp      // ReferenceSheet, GradeSheet
    val MaxSheetWideWidth: Dp = 960.dp         // OutlineOverlay, GraphicSheet
    val MaxSearchToolbarWidth: Dp = 600.dp     // In-article find bar
}

fun calculateWindowWidthClass(width: Dp): WindowWidthClass = when {
    width < AdaptiveTokens.BreakpointWidthMedium -> WindowWidthClass.COMPACT
    width < AdaptiveTokens.BreakpointWidthExpanded -> WindowWidthClass.MEDIUM
    else -> WindowWidthClass.EXPANDED
}

fun calculateWindowHeightClass(height: Dp): WindowHeightClass = when {
    height < AdaptiveTokens.BreakpointHeightMedium -> WindowHeightClass.COMPACT
    height < AdaptiveTokens.BreakpointHeightExpanded -> WindowHeightClass.MEDIUM
    else -> WindowHeightClass.EXPANDED
}

fun calculateWindowAdaptiveInfo(width: Dp, height: Dp): WindowAdaptiveInfo = WindowAdaptiveInfo(
    widthClass = calculateWindowWidthClass(width),
    heightClass = calculateWindowHeightClass(height),
    widthDp = width,
    heightDp = height
)
