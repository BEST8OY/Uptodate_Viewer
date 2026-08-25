package com.clinref.app.ui.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Single owner of hide/reveal policy for chrome floating over the article. Consumes the
 * unified nested-scroll stream from [NestedScrollWebView] (drag deltas plus unconsumed
 * edge deltas) and exposes a normalized [hideFraction] that any number of bars translate
 * against their own travel distance.
 */
@Stable
class ArticleScrollState(
    private val scope: CoroutineScope,
    private val settleSpec: FiniteAnimationSpec<Float>
) : NestedScrollConnection {

    var gate: () -> Boolean = { true }

    /** Cumulative downward delta required before hiding begins tracking. */
    var hideActivationThresholdPx: Float = 0f

    /** Total translation distance of the primary chrome element, in pixels. */
    var travelDistancePx: Float = 0f

    /** 0f = fully visible, 1f = fully hidden. */
    val hideFraction: Float
        get() = if (travelDistancePx > 0f) {
            (offsetPx.value / travelDistancePx).coerceIn(0f, 1f)
        } else {
            0f
        }

    private val offsetPx = Animatable(0f)
    private var settleJob: Job? = null
    private var suppressUntilNanos = 0L
    private var pendingHidePx = 0f

    /**
     * Compose convention: negative available/consumed y means scrolling deeper into the
     * content (finger up); positive means back toward the top. We never consume — the
     * WebView owns its own scrolling.
     */
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource
    ): Offset {
        onScrolled(-(consumed.y + available.y).roundToInt())
        return Offset.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        settleToNearest()
        return Velocity.Zero
    }

    fun reveal() {
        pendingHidePx = 0f
        settleJob?.cancel()
        scope.launch { offsetPx.snapTo(0f) }
    }

    fun suppressFor(durationMs: Long = SUPPRESSION_MS) {
        suppressUntilNanos = System.nanoTime() + durationMs * 1_000_000
    }

    private fun onScrolled(webViewDy: Int) {
        if (!gate() || System.nanoTime() < suppressUntilNanos || travelDistancePx <= 0f) return
        settleJob?.cancel()
        scope.launch {
            if (webViewDy < 0) {
                pendingHidePx = 0f
                offsetPx.snapTo((offsetPx.value + webViewDy).coerceIn(0f, travelDistancePx))
            } else {
                pendingHidePx += webViewDy
                if (pendingHidePx >= hideActivationThresholdPx) {
                    offsetPx.snapTo((offsetPx.value + webViewDy).coerceIn(0f, travelDistancePx))
                }
            }
            settleJob = scope.launch {
                delay(SETTLE_DEBOUNCE_MS)
                animateToNearestEnd()
            }
        }
    }

    private fun settleToNearest() {
        settleJob?.cancel()
        settleJob = scope.launch { animateToNearestEnd() }
    }

    private suspend fun animateToNearestEnd() {
        val target = if (offsetPx.value > travelDistancePx / 2f) travelDistancePx else 0f
        offsetPx.animateTo(target, settleSpec)
    }

    companion object {
        private const val SETTLE_DEBOUNCE_MS = 180L
        private const val SUPPRESSION_MS = 1_000L
    }
}

@Composable
fun rememberArticleScrollState(): ArticleScrollState {
    val scope = rememberCoroutineScope()
    val settleSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    return remember(scope, settleSpec) { ArticleScrollState(scope, settleSpec) }
}
