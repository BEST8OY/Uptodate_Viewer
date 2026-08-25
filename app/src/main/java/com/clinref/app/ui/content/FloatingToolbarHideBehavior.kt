package com.clinref.app.ui.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.FloatingToolbarScrollBehavior
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FloatingToolbarHideBehavior(
    private val scrollBehavior: FloatingToolbarScrollBehavior,
    private val scope: CoroutineScope,
    private val spatialSpec: FiniteAnimationSpec<Float>
) {
    var gate: () -> Boolean = { true }

    private var settleJob: Job? = null
    private var suppressUntilNanos = 0L

    fun onScrolled(dy: Int) {
        if (!gate() || System.nanoTime() < suppressUntilNanos) return
        settleJob?.cancel()
        val state = scrollBehavior.state
        state.offset -= dy
        settleJob = scope.launch {
            delay(SETTLE_DEBOUNCE_MS)
            val limit = state.offsetLimit
            if (limit != 0f) {
                val target = if (state.offset < limit / 2f) limit else 0f
                Animatable(state.offset).animateTo(target, spatialSpec) {
                    state.offset = this.value
                }
            }
        }
    }

    fun reveal() {
        settleJob?.cancel()
        scrollBehavior.state.offset = 0f
    }

    fun suppressFor(durationMs: Long = JUMP_SUPPRESSION_MS) {
        suppressUntilNanos = System.nanoTime() + durationMs * 1_000_000
    }

    companion object {
        private const val SETTLE_DEBOUNCE_MS = 180L
        private const val JUMP_SUPPRESSION_MS = 1_000L
    }
}
