package com.clinref.app.ui.content

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.WebView

/**
 * [WebView] that emits user-initiated vertical scroll updates via [onScrollDelta].
 *
 * Uses [onScrollChanged] rather than raw touch deltas so momentum flings, mouse wheels, and
 * keyboard scrolling are captured accurately, while horizontal table pans, text-selection drags,
 * and non-scrollable documents naturally produce zero vertical deltas.
 *
 * Programmatic scrolls (initial load/restore, section jumps, find-in-page, fragment navigation,
 * and layout/IME resizes) are excluded via [suppressProgrammaticScroll] until the next genuine
 * user interaction.
 */
internal class ArticleScrollWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.webViewStyle
) : WebView(context, attrs, defStyleAttr) {

    var onScrollDelta: ((ArticleScrollDelta) -> Unit)? = null

    private var userScrollArmed = false
    private var isPinching = false

    /** Disarms scroll reporting until the next genuine single-pointer, wheel, or key scroll input. */
    fun suppressProgrammaticScroll() {
        userScrollArmed = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isPinching = false
                userScrollArmed = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch pinch/zoom: suppress scroll updates for the remainder of the gesture.
                isPinching = true
                userScrollArmed = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isPinching = false
                // Intentionally keep userScrollArmed as-is so post-lift momentum flings still report
                // (while a completed pinch gesture remains disarmed until the next touch).
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
            userScrollArmed = true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && isScrollKey(event.keyCode)) {
            userScrollArmed = true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw || h != oldh) {
            suppressProgrammaticScroll()
        }
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!userScrollArmed || isPinching) return
        val dy = t - oldt
        if (dy == 0) return
        val maxScrollY = (computeVerticalScrollRange() - computeVerticalScrollExtent())
            .coerceAtLeast(0)
        onScrollDelta?.invoke(
            ArticleScrollDelta(
                deltaY = dy.toFloat(),
                scrollY = t.coerceAtLeast(0).toFloat(),
                maxScrollY = maxScrollY.toFloat()
            )
        )
    }

    private fun isScrollKey(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END -> true
        else -> false
    }
}
