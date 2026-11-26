package com.clinref.app.ui.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArticleScrollStateTest {

    private fun createState(
        hideThresholdPx: Float = 100f,
        revealThresholdPx: Float = 50f,
        topPinnedZonePx: Float = 120f,
        minScrollableRangePx: Float = 240f,
        directionNoiseSlopPx: Float = 10f
    ) = ArticleScrollState(
        hideThresholdPx = hideThresholdPx,
        revealThresholdPx = revealThresholdPx,
        topPinnedZonePx = topPinnedZonePx,
        minScrollableRangePx = minScrollableRangePx,
        directionNoiseSlopPx = directionNoiseSlopPx
    )

    @Test
    fun `deliberate downward scroll past threshold hides toolbar`() {
        val state = createState()

        state.onScrollDelta(ArticleScrollDelta(deltaY = 60f, scrollY = 300f, maxScrollY = 2000f))
        assertTrue(state.isToolbarVisible)

        state.onScrollDelta(ArticleScrollDelta(deltaY = 40f, scrollY = 340f, maxScrollY = 2000f))
        assertFalse(state.isToolbarVisible)
    }

    @Test
    fun `asymmetric upward scroll reveals toolbar faster than hiding`() {
        val state = createState()

        state.onScrollDelta(ArticleScrollDelta(deltaY = 120f, scrollY = 400f, maxScrollY = 2000f))
        assertFalse(state.isToolbarVisible)

        state.onScrollDelta(ArticleScrollDelta(deltaY = -30f, scrollY = 370f, maxScrollY = 2000f))
        assertFalse(state.isToolbarVisible)

        state.onScrollDelta(ArticleScrollDelta(deltaY = -20f, scrollY = 350f, maxScrollY = 2000f))
        assertTrue(state.isToolbarVisible)
    }

    @Test
    fun `top pinned zone never hides and immediately reveals on return to top`() {
        val state = createState(topPinnedZonePx = 120f)

        // Scrolling down inside the top pinned zone keeps toolbar visible.
        state.onScrollDelta(ArticleScrollDelta(deltaY = 110f, scrollY = 110f, maxScrollY = 2000f))
        assertTrue(state.isToolbarVisible)

        // Scroll deeper to hide.
        state.onScrollDelta(ArticleScrollDelta(deltaY = 150f, scrollY = 260f, maxScrollY = 2000f))
        assertFalse(state.isToolbarVisible)

        // Even a tiny upward scroll entering the top pinned zone reveals immediately.
        state.onScrollDelta(ArticleScrollDelta(deltaY = -5f, scrollY = 100f, maxScrollY = 2000f))
        assertTrue(state.isToolbarVisible)
    }

    @Test
    fun `short or non-scrollable articles never hide toolbar`() {
        val state = createState(minScrollableRangePx = 240f)

        state.onScrollDelta(ArticleScrollDelta(deltaY = 200f, scrollY = 200f, maxScrollY = 200f))
        assertTrue(state.isToolbarVisible)

        state.onScrollDelta(ArticleScrollDelta(deltaY = 100f, scrollY = 0f, maxScrollY = 0f))
        assertTrue(state.isToolbarVisible)
    }

    @Test
    fun `small opposite jitter does not reset accumulated progress but real reversal does`() {
        val state = createState(hideThresholdPx = 100f, directionNoiseSlopPx = 10f)

        // Accumulate 70px down
        state.onScrollDelta(ArticleScrollDelta(deltaY = 70f, scrollY = 300f, maxScrollY = 2000f))
        // Tiny 6px upward thumb tremor (< 10px slop) must not reset accumulator
        state.onScrollDelta(ArticleScrollDelta(deltaY = -6f, scrollY = 294f, maxScrollY = 2000f))
        // Remaining 30px down crosses 100px threshold and hides
        state.onScrollDelta(ArticleScrollDelta(deltaY = 30f, scrollY = 324f, maxScrollY = 2000f))
        assertFalse(state.isToolbarVisible)

        // Reset to visible
        state.reveal()

        // Accumulate 70px down, then real 15px upward reversal (>= 10px slop) resets accumulator
        state.onScrollDelta(ArticleScrollDelta(deltaY = 70f, scrollY = 400f, maxScrollY = 2000f))
        state.onScrollDelta(ArticleScrollDelta(deltaY = -15f, scrollY = 385f, maxScrollY = 2000f))
        state.onScrollDelta(ArticleScrollDelta(deltaY = 40f, scrollY = 425f, maxScrollY = 2000f))
        assertTrue(state.isToolbarVisible)
    }

    @Test
    fun `pinning reveals immediately and keeps toolbar visible after unpinning`() {
        val state = createState()

        state.onScrollDelta(ArticleScrollDelta(deltaY = 120f, scrollY = 400f, maxScrollY = 2000f))
        assertFalse(state.isToolbarVisible)

        // Pin (e.g., TalkBack enabled, search opened, outline opened)
        state.setPinnedVisible(true)
        assertTrue(state.isToolbarVisible)

        // Downward scroll while pinned has no effect
        state.onScrollDelta(ArticleScrollDelta(deltaY = 500f, scrollY = 900f, maxScrollY = 2000f))
        assertTrue(state.isToolbarVisible)

        // Unpinning keeps toolbar visible until fresh downward scroll crosses threshold
        state.setPinnedVisible(false)
        assertTrue(state.isToolbarVisible)

        state.onScrollDelta(ArticleScrollDelta(deltaY = 50f, scrollY = 950f, maxScrollY = 2000f))
        assertTrue(state.isToolbarVisible)

        state.onScrollDelta(ArticleScrollDelta(deltaY = 50f, scrollY = 1000f, maxScrollY = 2000f))
        assertFalse(state.isToolbarVisible)
    }

    @Test
    fun `reveal clears hidden state and accumulated hysteresis`() {
        val state = createState(hideThresholdPx = 100f)

        state.onScrollDelta(ArticleScrollDelta(deltaY = 80f, scrollY = 300f, maxScrollY = 2000f))
        state.reveal()
        // Must require full 100px again, not leftover 20px
        state.onScrollDelta(ArticleScrollDelta(deltaY = 50f, scrollY = 350f, maxScrollY = 2000f))
        assertTrue(state.isToolbarVisible)
    }

    @Test
    fun `non-finite and zero deltas are ignored safely`() {
        val state = createState()

        state.onScrollDelta(ArticleScrollDelta(deltaY = Float.NaN, scrollY = 300f, maxScrollY = 2000f))
        state.onScrollDelta(ArticleScrollDelta(deltaY = Float.POSITIVE_INFINITY, scrollY = 300f, maxScrollY = 2000f))
        state.onScrollDelta(ArticleScrollDelta(deltaY = 0f, scrollY = 300f, maxScrollY = 2000f))
        assertTrue(state.isToolbarVisible)
    }
}
