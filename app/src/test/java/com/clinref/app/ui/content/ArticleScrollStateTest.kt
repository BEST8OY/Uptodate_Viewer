package com.clinref.app.ui.content

import androidx.compose.animation.core.snap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArticleScrollStateTest {

    private fun createState(
        backgroundScope: kotlinx.coroutines.CoroutineScope
    ): ArticleScrollState =
        ArticleScrollState(backgroundScope, snap<Float>()).apply {
            travelDistancePx = 100f
            hideActivationThresholdPx = 40f
        }

    // Compose convention: negative available.y = scrolling deeper into content.
    private fun ArticleScrollState.feedDown(px: Float) {
        onPostScroll(Offset.Zero, Offset(0f, -px), NestedScrollSource.UserInput)
    }

    private fun ArticleScrollState.feedUp(px: Float) {
        onPostScroll(Offset.Zero, Offset(0f, px), NestedScrollSource.UserInput)
    }

    @Test
    fun belowThresholdDoesNotMoveChrome() = runTest {
        val state = createState(backgroundScope)
        state.feedDown(30f)
        runCurrent()
        assertEquals(0f, state.hideFraction, 0.001f)
    }

    @Test
    fun crossingThresholdStartsTracking() = runTest {
        val state = createState(backgroundScope)
        state.feedDown(30f)
        state.feedDown(20f)
        runCurrent()
        // Pending 50px crosses the 40px gate; only the crossing event's delta moves chrome.
        assertEquals(0.2f, state.hideFraction, 0.001f)
    }

    @Test
    fun upwardDeltaRevealsInstantlyAndResetsAccumulator() = runTest {
        val state = createState(backgroundScope)
        state.feedDown(40f)
        state.feedDown(10f)
        runCurrent()
        assertEquals(0.1f, state.hideFraction, 0.001f)

        state.feedUp(20f)
        runCurrent()
        assertEquals(0.1f, state.hideFraction, 0.001f)

        // Accumulator was reset: a small downward scroll must not resume hiding.
        state.feedDown(15f)
        runCurrent()
        assertEquals(0.1f, state.hideFraction, 0.001f)
    }

    @Test
    fun disabledGateIgnoresDeltas() = runTest {
        val state = createState(backgroundScope)
        state.gate = { false }
        state.feedDown(500f)
        runCurrent()
        assertEquals(0f, state.hideFraction, 0.001f)
    }

    @Test
    fun revealSnapsFullyVisibleAndResetsAccumulator() = runTest {
        val state = createState(backgroundScope)
        state.feedDown(40f)
        state.feedDown(60f)
        runCurrent()
        assertEquals(1f, state.hideFraction, 0.001f)

        state.reveal()
        runCurrent()
        assertEquals(0f, state.hideFraction, 0.001f)

        state.feedDown(20f)
        runCurrent()
        assertEquals(0f, state.hideFraction, 0.001f)
    }

    @Test
    fun suppressionWindowIgnoresDeltas() = runTest {
        val state = createState(backgroundScope)
        state.suppressFor(60_000)
        state.feedDown(500f)
        runCurrent()
        assertEquals(0f, state.hideFraction, 0.001f)
    }
}
