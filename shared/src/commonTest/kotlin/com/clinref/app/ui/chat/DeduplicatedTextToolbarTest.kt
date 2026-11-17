package com.clinref.app.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import com.clinref.app.ui.chat.components.DeduplicatedTextToolbar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduplicatedTextToolbarTest {

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
    fun suppressesCollapsedSelectionWithSelectAllOnly() {
        val fake = FakeTextToolbar()
        val toolbar = DeduplicatedTextToolbar(fake)

        // Simulate initial touch / collapsed tap that only provides onSelectAllRequested
        toolbar.showMenu(
            rect = Rect.Zero,
            onCopyRequested = null,
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = { }
        )

        assertEquals("Should suppress transient menu when no text is selected to copy or cut", 0, fake.showMenuCallCount)
        assertEquals(0, fake.hideCallCount)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }

    @Test
    fun suppressesCollapsedSelectionInOverloadWithSelectAllOnly() {
        val fake = FakeTextToolbar()
        val toolbar = DeduplicatedTextToolbar(fake)

        toolbar.showMenu(
            rect = Rect.Zero,
            onCopyRequested = null,
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = { },
            onAutofillRequested = null
        )

        assertEquals("Should suppress transient menu in 6-arg overload", 0, fake.showMenuCallCount)
        assertEquals(0, fake.hideCallCount)
    }

    @Test
    fun showsMenuAndDismissesPreviousWhenTextSelectedToCopy() {
        val fake = FakeTextToolbar()
        val toolbar = DeduplicatedTextToolbar(fake)

        var copyCalled = false
        var selectAllCalled = false
        val copyAction = { copyCalled = true }
        val selectAllAction = { selectAllCalled = true }

        val testRect = Rect(0f, 0f, 100f, 50f)
        toolbar.showMenu(
            rect = testRect,
            onCopyRequested = copyAction,
            onPasteRequested = null,
            onCutRequested = null,
            onSelectAllRequested = selectAllAction
        )

        assertEquals("Should call hide() to dismiss any previous action mode before showing", 1, fake.hideCallCount)
        assertEquals("Should forward showMenu when copy is requested", 1, fake.showMenuCallCount)
        assertEquals(testRect, fake.lastRect)
        assertEquals(TextToolbarStatus.Shown, toolbar.status)

        fake.lastOnCopyRequested?.invoke()
        assertTrue(copyCalled)

        fake.lastOnSelectAllRequested?.invoke()
        assertTrue(selectAllCalled)
    }

    @Test
    fun showsMenuAndDismissesPreviousWhenCutIsRequested() {
        val fake = FakeTextToolbar()
        val toolbar = DeduplicatedTextToolbar(fake)

        var cutCalled = false
        val cutAction = { cutCalled = true }

        toolbar.showMenu(
            rect = Rect.Zero,
            onCopyRequested = null,
            onPasteRequested = null,
            onCutRequested = cutAction,
            onSelectAllRequested = null
        )

        assertEquals(1, fake.hideCallCount)
        assertEquals(1, fake.showMenuCallCount)

        fake.lastOnCutRequested?.invoke()
        assertTrue(cutCalled)
    }

    @Test
    fun forwardsHideAndStatusDirectly() {
        val fake = FakeTextToolbar()
        val toolbar = DeduplicatedTextToolbar(fake)

        fake.currentStatus = TextToolbarStatus.Shown
        assertEquals(TextToolbarStatus.Shown, toolbar.status)

        toolbar.hide()
        assertEquals(1, fake.hideCallCount)
        assertEquals(TextToolbarStatus.Hidden, toolbar.status)
    }
}
