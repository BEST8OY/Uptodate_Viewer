package com.clinref.app.ui.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FloatingToolbarHideBehavior(
    private val scope: CoroutineScope,
    private val spatialSpec: FiniteAnimationSpec<Float>
) {
    var gate: () -> Boolean = { true }

    var hideDistancePx: Float = 0f

    val translationY: Float
        get() = offset.value

    private val offset = Animatable(0f)
    private var settleJob: Job? = null
    private var suppressUntilNanos = 0L

    fun onScrolled(dy: Int) {
        if (!gate() || System.nanoTime() < suppressUntilNanos || hideDistancePx <= 0f) return
        settleJob?.cancel()
        scope.launch {
            offset.snapTo((offset.value - dy).coerceIn(0f, hideDistancePx))
            settleJob = scope.launch {
                delay(SETTLE_DEBOUNCE_MS)
                val target = if (offset.value > hideDistancePx / 2f) hideDistancePx else 0f
                offset.animateTo(target, spatialSpec)
            }
        }
    }

    fun reveal() {
        settleJob?.cancel()
        scope.launch { offset.snapTo(0f) }
    }

    fun suppressFor(durationMs: Long = JUMP_SUPPRESSION_MS) {
        suppressUntilNanos = System.nanoTime() + durationMs * 1_000_000
    }

    companion object {
        private const val SETTLE_DEBOUNCE_MS = 180L
        private const val JUMP_SUPPRESSION_MS = 1_000L
    }
}
