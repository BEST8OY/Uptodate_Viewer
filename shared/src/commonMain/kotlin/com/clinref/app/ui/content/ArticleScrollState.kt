package com.clinref.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * User-initiated vertical scroll update reported by a platform article renderer.
 *
 * @property deltaY Signed vertical scroll delta in pixels (`> 0` toward bottom, `< 0` toward top).
 * @property scrollY Current vertical scroll offset from the top of the document, in pixels.
 * @property maxScrollY Maximum scrollable vertical offset (`contentHeight - viewportHeight`), in pixels.
 */
data class ArticleScrollDelta(
    val deltaY: Float,
    val scrollY: Float,
    val maxScrollY: Float
)

/**
 * Deterministic visibility state machine for floating chrome overlaid on an article viewport.
 *
 * Uses asymmetric scroll hysteresis so hiding requires deliberate downward reading while revealing
 * responds quickly to upward intent or returning near the top:
 * - Never hides on non-scrollable or barely scrollable articles (`maxScrollY < minScrollableRangePx`).
 * - Always visible within the top pinned zone (`scrollY <= topPinnedZonePx`).
 * - Absorbs tiny opposite-direction jitter (`< directionNoiseSlopPx`) without wiping accumulated travel.
 * - Stays visible and ignores scroll deltas whenever [isPinnedVisible] is true.
 */
@Stable
class ArticleScrollState(
    hideThresholdPx: Float,
    revealThresholdPx: Float,
    topPinnedZonePx: Float,
    minScrollableRangePx: Float,
    directionNoiseSlopPx: Float,
    initialVisible: Boolean = true
) {
    val hideThresholdPx: Float = hideThresholdPx.coerceAtLeast(1f)
    val revealThresholdPx: Float = revealThresholdPx.coerceAtLeast(1f)
    val topPinnedZonePx: Float = topPinnedZonePx.coerceAtLeast(0f)
    val minScrollableRangePx: Float = minScrollableRangePx.coerceAtLeast(0f)
    val directionNoiseSlopPx: Float = directionNoiseSlopPx.coerceAtLeast(0f)

    private var hiddenByScroll by mutableStateOf(!initialVisible)
    private var isPinned by mutableStateOf(false)

    private var accumulatedPx = 0f
    private var oppositeNoisePx = 0f

    /** True when the floating toolbar should be shown. */
    val isToolbarVisible: Boolean
        get() = isPinned || !hiddenByScroll

    /** True when surrounding UI or accessibility state currently pins the toolbar visible. */
    val isPinnedVisible: Boolean
        get() = isPinned

    /**
     * Pins or unpins toolbar visibility. Entering a pinned state also resets [hiddenByScroll] so
     * releasing the pin keeps the toolbar visible until the user deliberately scrolls down again.
     */
    fun setPinnedVisible(pinned: Boolean) {
        if (isPinned == pinned) return
        isPinned = pinned
        if (pinned) {
            reveal()
        } else {
            resetAccumulators()
        }
    }

    /** Immediately reveals the toolbar and clears any accumulated scroll hysteresis. */
    fun reveal() {
        hiddenByScroll = false
        resetAccumulators()
    }

    /** Processes one user-initiated vertical scroll update from the article renderer. */
    fun onScrollDelta(delta: ArticleScrollDelta) {
        val dy = delta.deltaY
        if (!dy.isFinite() || dy == 0f) return
        if (isPinned) {
            resetAccumulators()
            return
        }

        val scrollY = if (delta.scrollY.isFinite()) delta.scrollY.coerceAtLeast(0f) else 0f
        val maxScrollY = if (delta.maxScrollY.isFinite()) delta.maxScrollY.coerceAtLeast(0f) else 0f

        if (maxScrollY < minScrollableRangePx || scrollY <= topPinnedZonePx) {
            if (hiddenByScroll) hiddenByScroll = false
            resetAccumulators()
            return
        }

        if (!hiddenByScroll) {
            handleWhileVisible(dy)
        } else {
            handleWhileHidden(dy)
        }
    }

    private fun handleWhileVisible(dy: Float) {
        if (dy > 0f) {
            oppositeNoisePx = 0f
            accumulatedPx += dy
            if (accumulatedPx >= hideThresholdPx) {
                hiddenByScroll = true
                resetAccumulators()
            }
        } else {
            oppositeNoisePx += -dy
            if (oppositeNoisePx >= directionNoiseSlopPx) {
                resetAccumulators()
            }
        }
    }

    private fun handleWhileHidden(dy: Float) {
        if (dy < 0f) {
            oppositeNoisePx = 0f
            accumulatedPx += -dy
            if (accumulatedPx >= revealThresholdPx) {
                hiddenByScroll = false
                resetAccumulators()
            }
        } else {
            oppositeNoisePx += dy
            if (oppositeNoisePx >= directionNoiseSlopPx) {
                resetAccumulators()
            }
        }
    }

    private fun resetAccumulators() {
        accumulatedPx = 0f
        oppositeNoisePx = 0f
    }

    companion object {
        val DefaultHideThreshold: Dp = 48.dp
        val DefaultRevealThreshold: Dp = 24.dp
        val DefaultTopPinnedZone: Dp = 64.dp
        val DefaultMinScrollableRange: Dp = 120.dp
        val DefaultDirectionNoiseSlop: Dp = 4.dp
    }
}

@Composable
fun rememberArticleScrollState(
    pinnedVisible: Boolean = false,
    hideThreshold: Dp = ArticleScrollState.DefaultHideThreshold,
    revealThreshold: Dp = ArticleScrollState.DefaultRevealThreshold,
    topPinnedZone: Dp = ArticleScrollState.DefaultTopPinnedZone,
    minScrollableRange: Dp = ArticleScrollState.DefaultMinScrollableRange,
    directionNoiseSlop: Dp = ArticleScrollState.DefaultDirectionNoiseSlop
): ArticleScrollState {
    val density = LocalDensity.current
    val hidePx = with(density) { hideThreshold.toPx() }
    val revealPx = with(density) { revealThreshold.toPx() }
    val topPinnedPx = with(density) { topPinnedZone.toPx() }
    val minScrollablePx = with(density) { minScrollableRange.toPx() }
    val noiseSlopPx = with(density) { directionNoiseSlop.toPx() }

    val state = remember(hidePx, revealPx, topPinnedPx, minScrollablePx, noiseSlopPx) {
        ArticleScrollState(
            hideThresholdPx = hidePx,
            revealThresholdPx = revealPx,
            topPinnedZonePx = topPinnedPx,
            minScrollableRangePx = minScrollablePx,
            directionNoiseSlopPx = noiseSlopPx
        )
    }

    LaunchedEffect(state, pinnedVisible) {
        state.setPinnedVisible(pinnedVisible)
    }

    return state
}
