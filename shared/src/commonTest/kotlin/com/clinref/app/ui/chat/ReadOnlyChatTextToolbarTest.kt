package com.clinref.app.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import com.clinref.app.ui.chat.components.ReadOnlyChatTextToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyChatTextToolbarTest {

    private class FakeTextToolbar : TextToolbar {
        var hideCallCount = 0
        var showMenuCallCount = 0
        var lastRect: Rect? = null
        var lastOnCopyRequested: (() -> Unit)? = null
        var lastOnPasteRequested: (() -> Unit)? = null
        var lastOnCutRequested: (() -> Unit)? = null
        var lastOnSelectAllRequested: (() -> Unit)? = null
        var lastOnAutofillRequested: (() -> Unit)? = null
        var currentStatus: TextToolbarStatus = TextToolbarStatus.Hidden

        override val status: TextToolbarStatus
            get() = currentStatus

        override fun hide() {
            hideCallCount++
            currentStatus = TextToolbarStatus.Hidden
        }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?
        ) {
            showMenuCallCount++
            lastRect = rect
            lastOnCopyRequested = onCopyRequested
            lastOnPasteRequested = onPasteRequested
            lastOnCutRequested = onCutRequested
            lastOnSelectAllRequested = onSelectAllRequested
            currentStatus = TextToolbarStatus.Shown
        }

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
            onAutofillRequested: (() -> Unit)?
        ) {
            showMenuCallCount++
            lastRect = rect
            lastOnCopyRequested = onCopyRequested
            lastOnPasteRequested = onPasteRequested
            lastOnCutRequested = onCutRequested
            lastOnSelectAllRequested = onSelectAllRequested
            lastOnAutofillRequested = onAutofillRequested
            currentStatus = TextToolbarStatus.Shown
        }
    }

    @Test
    fun suppressesCollapsedSelectionWithoutCopyRequest() {
        val fake = FakeTextToolbar()
        val toolbar = ReadOnlyChatTextToolbar(fake)

        // Collapsed selection tap with only selectAll or cut/paste
        toolbar.showMenu(
            rect = Rect(0f, 0f, 10f, 10f),
            onCopyRequested = null,
            onPasteRequested = { },
            onCutRequested = { },
            onSelectAllRequested = { }
        )

        assertEquals("Should suppress transient menu when no text is selected to copy", 0, fake.showMenuCallCount)
        assertEquals(0, fake.hideCallCount)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    @Test
    fun suppressesEmptyOrInvalidRects() {
        val fake = FakeTextToolbar()
        val toolbar = ReadOnlyChatTextToolbar(fake)

        toolbar.showMenu(
            rect = Rect.Zero, // empty rect
            onCopyRequested = { }
        )
        assertEquals(0, fake.showMenuCallCount)

        toolbar.showMenu(
            rect = Rect(Float.NaN, 0f, 100f, 50f),
            onCopyRequested = { }
        )
        assertEquals(0, fake.showMenuCallCount)
    }

    @Test
    fun showsMenuAndStripsCutPasteAutofill() {
        val fake = FakeTextToolbar()
        val toolbar = ReadOnlyChatTextToolbar(fake)

        var copyCalled = false
        val copyAction = { copyCalled = true }

        val testRect = Rect(10f, 20f, 100f, 60f)
        toolbar.showMenu(
            rect = testRect,
            onCopyRequested = copyAction,
            onPasteRequested = { },
            onCutRequested = { },
            onSelectAllRequested = { },
            onAutofillRequested = { }
        )

        assertEquals(1, fake.showMenuCallCount)
        assertEquals(testRect, fake.lastRect)
        assertNull("Paste must be stripped on read-only toolbar", fake.lastOnPasteRequested)
        assertNull("Cut must be stripped on read-only toolbar", fake.lastOnCutRequested)
        assertNull("Autofill must be stripped on read-only toolbar", fake.lastOnAutofillRequested)

        fake.lastOnCopyRequested?.invoke()
        assertTrue(copyCalled)
    }

    @Test
    fun deduplicatesEquivalentShowMenuCallsWhileShown() {
        val fake = FakeTextToolbar()
        val toolbar = ReadOnlyChatTextToolbar(fake)

        var copy1Called = false
        var copy2Called = false

        val testRect = Rect(10f, 20f, 100f, 60f)
        toolbar.showMenu(
            rect = testRect,
            onCopyRequested = { copy1Called = true },
            onSelectAllRequested = { }
        )
        assertEquals(1, fake.showMenuCallCount)

        // Same rect called with updated copy action (e.g. slight selection change)
        toolbar.showMenu(
            rect = Rect(10.5f, 20.5f, 100.5f, 60.5f), // within EPSILON
            onCopyRequested = { copy2Called = true },
            onSelectAllRequested = { }
        )
        assertEquals("Equivalent rect must not re-trigger showMenu", 1, fake.showMenuCallCount)

        // Invoking copy must execute the updated callback (copy2)
        fake.lastOnCopyRequested?.invoke()
        org.junit.Assert.assertFalse("Stale copy action must not be called", copy1Called)
        assertTrue("Latest copy action must be called", copy2Called)
    }

    @Test
    fun dismissHidesMenuImmediately() {
        val fake = FakeTextToolbar()
        val toolbar = ReadOnlyChatTextToolbar(fake)

        fake.currentStatus = TextToolbarStatus.Shown
        toolbar.dismiss()

        assertEquals(1, fake.hideCallCount)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    @Test
    fun passesNullSelectAllWhenOnSelectAllRequestedIsNull() {
        val fake = FakeTextToolbar()
        val toolbar = ReadOnlyChatTextToolbar(fake)

        toolbar.showMenu(
            rect = Rect(10f, 20f, 100f, 60f),
            onCopyRequested = { },
            onSelectAllRequested = null
        )

        assertEquals(1, fake.showMenuCallCount)
        assertNull("onSelectAllRequested must be null when caller passed null", fake.lastOnSelectAllRequested)

        // 6-arg overload
        toolbar.dismiss()
        toolbar.showMenu(
            rect = Rect(10f, 20f, 100f, 60f),
            onCopyRequested = { },
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = null,
            onAutofillRequested = null
        )
        assertNull("onSelectAllRequested must be null in 6-arg overload when caller passed null", fake.lastOnSelectAllRequested)
    }
}
