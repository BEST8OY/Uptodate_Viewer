package com.clinref.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Stable
class ChatScrollController(
    val listState: LazyListState,
    private val scrollBehavior: TopAppBarScrollBehavior,
    private val coroutineScope: CoroutineScope
) {
    data class PrependAnchor(
        val itemKey: String,
        val scrollOffset: Int
    )

    var hasCompletedInitialScroll by mutableStateOf(false)
        private set

    private var pendingPrependAnchor: PrependAnchor? = null

    val isAtBottom: Boolean by derivedStateOf {
        val layoutInfo = listState.layoutInfo
        val total = layoutInfo.totalItemsCount
        if (total == 0) return@derivedStateOf true
        if (!listState.canScrollForward) return@derivedStateOf true
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
        val viewportBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
        lastVisible.index >= total - 1 && (lastVisible.offset + lastVisible.size) <= viewportBottom + BOTTOM_TOLERANCE_PX
    }

    val showScrollToBottomFab: Boolean by derivedStateOf {
        hasCompletedInitialScroll && !isAtBottom && listState.layoutInfo.totalItemsCount > 0
    }

    /**
     * Initial conversation open: positions the viewport at the user's last message
     * (so their final question and the start of the assistant response are immediately visible)
     * rather than jumping to the bottom footer.
     */
    suspend fun jumpToInitialPosition(chatItems: List<ChatListItem>, topHeaderCount: Int) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            val lastUserIdx = chatItems.indexOfLast {
                it is ChatListItem.Message && it.uiModel.role == "user"
            }
            val targetIndex = if (lastUserIdx >= 0) {
                (lastUserIdx + topHeaderCount).coerceIn(0, total - 1)
            } else {
                total - 1
            }
            if (targetIndex > topHeaderCount) {
                collapseTopBarImmediate()
            }
            listState.scrollToItem(index = targetIndex, scrollOffset = 0)
        }
        hasCompletedInitialScroll = true
    }

    /**
     * Scrolls a specific item (e.g., a newly sent user message) to the top of the viewport.
     */
    suspend fun scrollToIndex(index: Int, animated: Boolean) {
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 0) {
            hasCompletedInitialScroll = true
            return
        }
        val targetIndex = index.coerceIn(0, total - 1)
        if (animated) {
            val collapseJob = if (targetIndex > 0) {
                coroutineScope.launch { collapseTopBarAnimated() }
            } else null
            listState.animateScrollToItem(index = targetIndex, scrollOffset = 0)
            collapseJob?.join()
        } else {
            if (targetIndex > 0) collapseTopBarImmediate()
            listState.scrollToItem(index = targetIndex, scrollOffset = 0)
        }
        hasCompletedInitialScroll = true
    }

    /**
     * Explicit jump to the very bottom (used by ScrollToBottomFAB and error messages).
     */
    suspend fun scrollToBottom(animated: Boolean) {
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 0) {
            hasCompletedInitialScroll = true
            return
        }
        val lastIndex = total - 1
        if (animated) {
            val collapseJob = coroutineScope.launch { collapseTopBarAnimated() }
            listState.animateScrollToItem(index = lastIndex)
            collapseJob.join()
            if (listState.canScrollForward) {
                listState.scrollToItem(index = lastIndex, scrollOffset = 0)
            }
            collapseTopBarImmediate()
        } else {
            collapseTopBarImmediate()
            listState.scrollToItem(index = lastIndex, scrollOffset = 0)
        }
        hasCompletedInitialScroll = true
    }

    fun capturePrependAnchor(chatItems: List<ChatListItem>, topHeaderCount: Int) {
        if (pendingPrependAnchor != null || chatItems.isEmpty()) return
        val firstVisibleInfo = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index >= topHeaderCount && (it.index - topHeaderCount) < chatItems.size }
            ?: return
        val anchorItem = chatItems.getOrNull(firstVisibleInfo.index - topHeaderCount) ?: return
        val scrollOffset = (-firstVisibleInfo.offset).coerceAtLeast(0)
        pendingPrependAnchor = PrependAnchor(
            itemKey = anchorItem.key,
            scrollOffset = scrollOffset
        )
    }

    suspend fun restorePrependAnchorIfNeeded(chatItems: List<ChatListItem>, topHeaderCount: Int): Boolean {
        val anchor = pendingPrependAnchor ?: return false
        val newItemIndex = chatItems.indexOfFirst { it.key == anchor.itemKey }
        if (newItemIndex >= 0) {
            pendingPrependAnchor = null
            listState.scrollToItem(
                index = newItemIndex + topHeaderCount,
                scrollOffset = anchor.scrollOffset
            )
            return true
        }
        return false
    }

    fun clearPrependAnchor() {
        pendingPrependAnchor = null
    }

    private suspend fun collapseTopBarAnimated() {
        val limit = scrollBehavior.state.heightOffsetLimit
        if (limit < 0f && scrollBehavior.state.heightOffset > limit) {
            Animatable(scrollBehavior.state.heightOffset).animateTo(limit) {
                scrollBehavior.state.heightOffset = value
            }
        }
    }

    private fun collapseTopBarImmediate() {
        val limit = scrollBehavior.state.heightOffsetLimit
        if (limit < 0f && scrollBehavior.state.heightOffset > limit) {
            scrollBehavior.state.heightOffset = limit
        }
    }

    private companion object {
        const val BOTTOM_TOLERANCE_PX = 48
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberChatScrollController(
    conversationId: String,
    listState: LazyListState,
    scrollBehavior: TopAppBarScrollBehavior
): ChatScrollController {
    val coroutineScope = rememberCoroutineScope()
    return remember(conversationId, listState, scrollBehavior, coroutineScope) {
        ChatScrollController(
            listState = listState,
            scrollBehavior = scrollBehavior,
            coroutineScope = coroutineScope
        )
    }
}
