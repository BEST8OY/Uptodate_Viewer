package com.clinref.app.ui.content

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.webkit.WebView
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.ViewCompat
import kotlin.math.abs

/**
 * WebView that participates in the Android nested scroll protocol. Consumed deltas are
 * measured around [super.onTouchEvent]; the unconsumed remainder is dispatched even at
 * the content edges, where scrollY cannot change. Fling velocity is propagated the same
 * way when a boundary fling cannot scroll the page.
 */
class NestedScrollWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr), NestedScrollingChild3 {

    private val childHelper = NestedScrollingChildHelper(this).apply {
        isNestedScrollingEnabled = true
    }

    private var velocityTracker: VelocityTracker? = null
    private var activePointerId = -1
    private var lastTouchY = 0f

    /** Emits reading progress (0f..1f) whenever the page scroll position changes. */
    var progressEmitter: ((Float) -> Unit)? = null

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        emitProgress()
    }

    fun emitProgress() {
        val range = computeVerticalScrollRange() - computeVerticalScrollExtent()
        if (range > 0) {
            progressEmitter?.invoke(
                (computeVerticalScrollOffset().toFloat() / range).coerceIn(0f, 1f)
            )
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchY = event.getY(0)
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // A pinch begins: suspend vertical dispatch until a single pointer remains.
                stopNestedScroll(ViewCompat.TYPE_TOUCH)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 == 1) {
                    val remainingIndex = if (event.actionIndex == 0) 1 else 0
                    lastTouchY = event.getY(remainingIndex)
                    startNestedScroll(ViewCompat.SCROLL_AXIS_VERTICAL, ViewCompat.TYPE_TOUCH)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val vy = velocityTracker?.yVelocity ?: 0f
                if (abs(vy) > FLING_MIN_VELOCITY) {
                    val towardBottom = vy < 0
                    val canScroll = canScrollVertically(if (towardBottom) 1 else -1)
                    if (!canScroll && dispatchNestedPreFling(0f, vy)) {
                        // Parent consumed the fling.
                    } else if (!canScroll) {
                        dispatchNestedFling(0f, vy, false)
                    }
                }
                velocityTracker?.recycle()
                velocityTracker = null
                activePointerId = -1
                stopNestedScroll(ViewCompat.TYPE_TOUCH)
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val index = if (activePointerId == -1) 0 else event.findPointerIndex(activePointerId)
                val y = if (index >= 0) event.getY(index) else event.y
                val delta = (lastTouchY - y).toInt() // + = toward bottom, matching scrollY
                lastTouchY = y
                val before = scrollY
                val handled = super.onTouchEvent(event)
                if (event.pointerCount == 1 && delta != 0) {
                    val consumedY = scrollY - before
                    val unconsumedY = delta - consumedY
                    if (consumedY != 0 || unconsumedY != 0) {
                        dispatchNestedScroll(
                            0, consumedY, 0, unconsumedY, null, ViewCompat.TYPE_TOUCH
                        )
                    }
                }
                return handled
            }
        }
        return super.onTouchEvent(event)
    }

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled(): Boolean = childHelper.isNestedScrollingEnabled

    override fun startNestedScroll(axes: Int): Boolean = childHelper.startNestedScroll(axes)

    override fun startNestedScroll(axes: Int, type: Int): Boolean =
        childHelper.startNestedScroll(axes, type)

    override fun stopNestedScroll() = childHelper.stopNestedScroll()

    override fun stopNestedScroll(type: Int) = childHelper.stopNestedScroll(type)

    override fun hasNestedScrollingParent(): Boolean = childHelper.hasNestedScrollingParent()

    override fun hasNestedScrollingParent(type: Int): Boolean =
        childHelper.hasNestedScrollingParent(type)

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow
    )

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type
    )

    override fun dispatchNestedScroll(
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        offsetInWindow: IntArray?,
        type: Int,
        consumed: IntArray
    ) {
        childHelper.dispatchNestedScroll(
            dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed
        )
    }

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
    ): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedPreScroll(
        dx: Int,
        dy: Int,
        consumed: IntArray?,
        offsetInWindow: IntArray?,
        type: Int,
    ): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

    override fun dispatchNestedFling(velocityX: Float, velocityY: Float, consumed: Boolean) =
        childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float) =
        childHelper.dispatchNestedPreFling(velocityX, velocityY)

    companion object {
        private const val FLING_MIN_VELOCITY = 50f
    }
}
