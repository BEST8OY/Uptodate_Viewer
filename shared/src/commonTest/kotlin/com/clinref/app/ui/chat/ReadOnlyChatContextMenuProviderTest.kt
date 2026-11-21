package com.clinref.app.ui.chat

import androidx.compose.foundation.text.contextmenu.data.TextContextMenuComponent
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuData
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import com.clinref.app.ui.chat.components.ReadOnlyChatContextMenuProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadOnlyChatContextMenuProviderTest {

    private fun createTestComponent(key: Any): TextContextMenuComponent {
        val clazz = Class.forName("androidx.compose.foundation.text.contextmenu.data.TextContextMenuItemWithComposableLeadingIcon")
        val ctor = clazz.declaredConstructors.first { it.parameterCount == 5 }
        ctor.isAccessible = true
        return ctor.newInstance(key, key.toString(), true, null, { _: Any -> }) as TextContextMenuComponent
    }

    private class FakeContextMenuProvider : TextContextMenuProvider {
        var callCount = 0
        var lastReceivedData: TextContextMenuData? = null
        var sessionDeferred: CompletableDeferred<Unit>? = null

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            callCount++
            lastReceivedData = dataProvider.data()
            sessionDeferred = CompletableDeferred()
            sessionDeferred?.await()
        }
    }

    private class SimpleDataProvider(private val data: TextContextMenuData) : TextContextMenuDataProvider {
        override fun data(): TextContextMenuData = data
        override fun position(destinationCoordinates: LayoutCoordinates): Offset = Offset.Zero
        override fun contentBounds(destinationCoordinates: LayoutCoordinates): Rect = Rect.Zero
    }

    @Test
    fun passesThroughDataWhenCopyKeyIsPresent() = runTest {
        val fakeDelegate = FakeContextMenuProvider()
        val provider = ReadOnlyChatContextMenuProvider(fakeDelegate)

        val copyComp = createTestComponent(TextContextMenuKeys.CopyKey)
        val selectAllComp = createTestComponent(TextContextMenuKeys.SelectAllKey)
        val testData = TextContextMenuData(listOf(copyComp, selectAllComp))

        val job = launch {
            provider.showTextContextMenu(SimpleDataProvider(testData))
        }
        advanceUntilIdle()

        assertEquals(1, fakeDelegate.callCount)
        assertEquals(2, fakeDelegate.lastReceivedData?.components?.size)
        assertTrue(fakeDelegate.lastReceivedData?.components?.any { it.key == TextContextMenuKeys.CopyKey } == true)

        provider.dismiss()
        job.join()
    }

    @Test
    fun stripsComponentsWhenCopyKeyIsAbsent() = runTest {
        val fakeDelegate = FakeContextMenuProvider()
        val provider = ReadOnlyChatContextMenuProvider(fakeDelegate)

        // b/519816995 condition: only SelectAllKey present when selection is cleared upon tap-outside
        val selectAllComp = createTestComponent(TextContextMenuKeys.SelectAllKey)
        val testData = TextContextMenuData(listOf(selectAllComp))

        val job = launch {
            provider.showTextContextMenu(SimpleDataProvider(testData))
        }
        advanceUntilIdle()

        assertEquals(1, fakeDelegate.callCount)
        assertEquals(
            "Components must be stripped when CopyKey is absent to prevent Select All flicker",
            0,
            fakeDelegate.lastReceivedData?.components?.size
        )

        provider.dismiss()
        job.join()
    }

    @Test
    fun dismissCancelsActiveSession() = runTest {
        val fakeDelegate = FakeContextMenuProvider()
        val provider = ReadOnlyChatContextMenuProvider(fakeDelegate)

        val copyComp = createTestComponent(TextContextMenuKeys.CopyKey)
        val testData = TextContextMenuData(listOf(copyComp))

        val job = launch {
            provider.showTextContextMenu(SimpleDataProvider(testData))
        }
        advanceUntilIdle()

        assertEquals(1, fakeDelegate.callCount)
        assertTrue(job.isActive)

        provider.dismiss()
        advanceUntilIdle()

        assertTrue("Job must be cancelled and completed after dismiss()", job.isCompleted)
    }
}
