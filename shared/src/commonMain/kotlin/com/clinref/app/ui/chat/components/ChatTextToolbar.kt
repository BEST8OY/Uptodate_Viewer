package com.clinref.app.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import kotlin.math.abs

/**
 * List-scoped read-only [TextToolbar] coordinator for assistant clinical messages.
 *
 * Guarantees:
 * - Suppresses transient collapsed taps (no `onCopyRequested`).
 * - Strips editable-only actions (`Cut`, `Paste`, `Autofill`) on read-only messages.
 * - Rejects empty/offscreen/non-finite rects that cause `(0,0)` floating toolbars.
 * - Deduplicates equivalent `showMenu` calls while already [TextToolbarStatus.Shown],
 *   preventing Android floating `ActionMode` flicker and duplicate stacked toolbars.
 * - Exposes [dismiss] for scroll-start, overlay-open, edit-mode, and lifecycle cleanup.
 */
class ReadOnlyChatTextToolbar(
    private val delegate: TextToolbar
) : TextToolbar {

    private var lastShownRect: Rect? = null
    private var lastHasSelectAll: Boolean = false
    private var latestOnCopy: (() -> Unit)? = null
    private var latestOnSelectAll: (() -> Unit)? = null

    override val status: TextToolbarStatus
        get() = delegate.status

    override fun hide() {
        lastShownRect = null
        lastHasSelectAll = false
        latestOnCopy = null
        latestOnSelectAll = null
        if (delegate.status == TextToolbarStatus.Shown) {
            delegate.hide()
        }
    }

    /** Explicitly dismisses any active floating text toolbar (e.g., on scroll or overlay). */
    fun dismiss() {
        hide()
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        if (!shouldShow(rect, onCopyRequested)) return

        val hasSelectAll = onSelectAllRequested != null
        latestOnCopy = onCopyRequested
        latestOnSelectAll = onSelectAllRequested

        if (isEquivalentToActiveMenu(rect, hasSelectAll)) return

        if (delegate.status == TextToolbarStatus.Shown) {
            delegate.hide()
        }
        lastShownRect = rect
        lastHasSelectAll = hasSelectAll

        delegate.showMenu(
            rect = rect,
            onCopyRequested = {
                latestOnCopy?.invoke()
                lastShownRect = null
                lastHasSelectAll = false
            },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = if (hasSelectAll) ({ latestOnSelectAll?.invoke() }) else null
        )
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
        onAutofillRequested: (() -> Unit)?
    ) {
        if (!shouldShow(rect, onCopyRequested)) return

        val hasSelectAll = onSelectAllRequested != null
        latestOnCopy = onCopyRequested
        latestOnSelectAll = onSelectAllRequested

        if (isEquivalentToActiveMenu(rect, hasSelectAll)) return

        if (delegate.status == TextToolbarStatus.Shown) {
            delegate.hide()
        }
        lastShownRect = rect
        lastHasSelectAll = hasSelectAll

        delegate.showMenu(
            rect = rect,
            onCopyRequested = {
                latestOnCopy?.invoke()
                lastShownRect = null
                lastHasSelectAll = false
            },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = if (hasSelectAll) ({ latestOnSelectAll?.invoke() }) else null,
            onAutofillRequested = null
        )
    }

    private fun shouldShow(rect: Rect, onCopyRequested: (() -> Unit)?): Boolean {
        if (onCopyRequested == null) return false
        if (rect.isEmpty || !rect.left.isFinite() || !rect.top.isFinite() ||
            !rect.right.isFinite() || !rect.bottom.isFinite()
        ) {
            return false
        }
        return true
    }

    private fun isEquivalentToActiveMenu(rect: Rect, hasSelectAll: Boolean): Boolean {
        if (delegate.status != TextToolbarStatus.Shown) return false
        val prev = lastShownRect ?: return false
        if (lastHasSelectAll != hasSelectAll) return false
        return abs(prev.left - rect.left) <= RECT_EPSILON_PX &&
            abs(prev.top - rect.top) <= RECT_EPSILON_PX &&
            abs(prev.right - rect.right) <= RECT_EPSILON_PX &&
            abs(prev.bottom - rect.bottom) <= RECT_EPSILON_PX
    }

    private companion object {
        const val RECT_EPSILON_PX = 2f
    }
}

@Composable
fun rememberReadOnlyChatTextToolbar(
    platformToolbar: TextToolbar = LocalTextToolbar.current
): ReadOnlyChatTextToolbar {
    val toolbar = remember(platformToolbar) {
        if (platformToolbar is ReadOnlyChatTextToolbar) {
            platformToolbar
        } else {
            ReadOnlyChatTextToolbar(platformToolbar)
        }
    }
    DisposableEffect(toolbar) {
        onDispose {
            toolbar.dismiss()
        }
    }
    return toolbar
}
