package com.clinref.app.ui.chat.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * A [TextToolbar] decorator that suppresses transient collapsed tap events (which only
 * request `onSelectAllRequested` without any active selection to copy/cut) and ensures
 * that any previously active toolbar is cleanly dismissed before showing a new one.
 *
 * This prevents Android's floating ActionMode from spawning duplicate stacked toolbars
 * (e.g., one with just "Select all" and a second with full contextual options) when
 * selecting read-only Markdown text in chat messages.
 */
class DeduplicatedTextToolbar(
    private val delegate: TextToolbar
) : TextToolbar {
    override val status: TextToolbarStatus
        get() = delegate.status

    override fun hide() {
        delegate.hide()
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        // Suppress transient collapsed tap events where no text is actually selected to copy/cut
        if (onCopyRequested == null && onCutRequested == null) {
            return
        }
        delegate.hide()
        delegate.showMenu(
            rect = rect,
            onCopyRequested = onCopyRequested,
            onPasteRequested = onPasteRequested,
            onCutRequested = onCutRequested,
            onSelectAllRequested = onSelectAllRequested
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
        // Suppress transient collapsed tap events where no text is actually selected to copy/cut
        if (onCopyRequested == null && onCutRequested == null) {
            return
        }
        delegate.hide()
        delegate.showMenu(
            rect = rect,
            onCopyRequested = onCopyRequested,
            onPasteRequested = onPasteRequested,
            onCutRequested = onCutRequested,
            onSelectAllRequested = onSelectAllRequested,
            onAutofillRequested = onAutofillRequested
        )
    }
}

@Composable
fun rememberDeduplicatedTextToolbar(
    currentToolbar: TextToolbar = LocalTextToolbar.current
): TextToolbar = remember(currentToolbar) {
    if (currentToolbar is DeduplicatedTextToolbar) currentToolbar
    else DeduplicatedTextToolbar(currentToolbar)
}
