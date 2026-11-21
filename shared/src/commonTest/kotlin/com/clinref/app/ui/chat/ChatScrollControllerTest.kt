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
        assertFalse(controller.showScrollToBottomFab)
    }

    @Test
    fun testJumpToInitialPositionMarksCompleted() = runTest(testDispatcher) {
        val controller = createController()
        val items = listOf(
            ChatListItem.Message(MessageUiModel(id = "msg-1", role = "user", content = "Hi", timestamp = 100L)),
            ChatListItem.Message(MessageUiModel(id = "msg-2", role = "assistant", content = "Hello", timestamp = 200L))
        )
        controller.jumpToInitialPosition(items, topHeaderCount = 0)
        assertTrue(controller.hasCompletedInitialScroll)
    }

    @Test
    fun testJumpToInitialPositionEmptyList() = runTest(testDispatcher) {
        val controller = createController()
        controller.jumpToInitialPosition(emptyList(), topHeaderCount = 0)
        assertTrue(controller.hasCompletedInitialScroll)
    }

    @Test
    fun testScrollToIndexMarksCompleted() = runTest(testDispatcher) {
        val controller = createController()
        controller.scrollToIndex(0, animated = false)
        assertTrue(controller.hasCompletedInitialScroll)
    }

    @Test
    fun testScrollToBottomMarksCompleted() = runTest(testDispatcher) {
        val controller = createController()
        controller.scrollToBottom(animated = false)
        assertTrue(controller.hasCompletedInitialScroll)
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
