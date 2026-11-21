package com.clinref.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)
class ChatScrollControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private fun createController(
        listState: LazyListState = LazyListState(),
        scrollBehavior: TopAppBarScrollBehavior = mockk(relaxed = true),
        scope: CoroutineScope = testScope
    ): ChatScrollController {
        return ChatScrollController(
            listState = listState,
            scrollBehavior = scrollBehavior,
            coroutineScope = scope
        )
    }

    @Test
    fun testInitialState() {
        val controller = createController()
        assertFalse(controller.hasCompletedInitialScroll)
        assertTrue(controller.isPinnedToBottom)
    }

    @Test
    fun testJumpToBottomForInitialLoadMarksCompleted() = runTest(testDispatcher) {
        val controller = createController()
        controller.jumpToBottomForInitialLoad()
        assertTrue(controller.hasCompletedInitialScroll)
        assertTrue(controller.isPinnedToBottom)
    }

    @Test
    fun testUnpinOnlyFunctionsAfterInitialScrollCompleted() {
        val controller = createController()
        // Before initial scroll, unpin is ignored
        controller.unpin()
        assertTrue(controller.isPinnedToBottom)

        // After initial scroll, unpin takes effect
        testScope.runTest {
            controller.jumpToBottomForInitialLoad()
        }
        controller.unpin()
        assertFalse(controller.isPinnedToBottom)
    }

    @Test
    fun testOnScrollStateObservedTransitions() = runTest(testDispatcher) {
        val controller = createController()
        controller.jumpToBottomForInitialLoad()

        // Scrolling in progress while NOT at bottom unpins
        controller.onScrollStateObserved(isScrollInProgress = true, atBottom = false)
        assertFalse(controller.isPinnedToBottom)

        // Reaching bottom re-pins
        controller.onScrollStateObserved(isScrollInProgress = false, atBottom = true)
        assertTrue(controller.isPinnedToBottom)
    }

    @Test
    fun testOnConversationChangedResetsState() = runTest(testDispatcher) {
        val controller = createController()
        controller.jumpToBottomForInitialLoad()
        controller.unpin()
        assertFalse(controller.isPinnedToBottom)

        controller.onConversationChanged()
        assertFalse(controller.hasCompletedInitialScroll)
        assertTrue(controller.isPinnedToBottom)
    }

    @Test
    fun testPrependAnchorRestoreReturnsFalseWhenNoAnchor() = runTest(testDispatcher) {
        val controller = createController()
        val items = listOf(
            ChatListItem.Message(MessageUiModel(id = "msg-1", role = "user", content = "Hi", timestamp = 100L))
        )
        val restored = controller.restorePrependAnchorIfNeeded(items, topHeaderCount = 1)
        assertFalse(restored)
    }

    @Test
    fun testClearPrependAnchor() = runTest(testDispatcher) {
        val controller = createController()
        controller.clearPrependAnchor()
        val items = listOf(
            ChatListItem.Message(MessageUiModel(id = "msg-1", role = "user", content = "Hi", timestamp = 100L))
        )
        assertFalse(controller.restorePrependAnchorIfNeeded(items, topHeaderCount = 1))
    }
}
