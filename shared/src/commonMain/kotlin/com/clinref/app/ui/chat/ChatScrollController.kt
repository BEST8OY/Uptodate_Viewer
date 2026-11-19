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
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Deterministic scroll controller for [ChatScreen].
 *
 * State machine:
 * - Starts pinned to bottom (`isPinnedToBottom = true`) on each conversation load.
 * - Unpins immediately when the user manually scrolls away from the bottom edge.
 * - Re-pins when the user returns near the bottom edge, sends a message, or taps the FAB.
 * - Auto-follows streaming content only while pinned and not actively dragged.
 * - Preserves exact visual anchor pixel offset when older messages are prepended.
 */
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

    var isPinnedToBottom by mutableStateOf(true)
        private set

    private var pendingPrependAnchor: PrependAnchor? = null
    private var isProgrammaticScroll = false

    /**
     * True when the viewport is already at or within a small tolerance of the final item.
     */
    val isAtBottom: Boolean by derivedStateOf {
        val layoutInfo = listState.layoutInfo
        val total = layoutInfo.totalItemsCount
        if (total == 0) return@derivedStateOf true
        if (!listState.canScrollForward) return@derivedStateOf true
        val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf false
        val viewportBottom = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
        lastVisible.index >= total - 1 && (lastVisible.offset + lastVisible.size) <= viewportBottom + BOTTOM_TOLERANCE_PX
    }

    /**
     * Show the floating Scroll-to-Bottom button whenever the user is away from the bottom
     * (including during active streaming so they can jump back to live output).
     */
    val showScrollToBottomFab: Boolean by derivedStateOf {
        hasCompletedInitialScroll && !isAtBottom && listState.layoutInfo.totalItemsCount > 0
    }

    fun onConversationChanged() {
        hasCompletedInitialScroll = false
        isPinnedToBottom = true
        pendingPrependAnchor = null
    }

    /**
     * Unpins sticky-bottom follow when the user begins an upward scroll gesture.
     */
    fun unpin() {
        if (hasCompletedInitialScroll) {
            isPinnedToBottom = false
        }
    }

    /**
     * Called from a `snapshotFlow` observing `listState.isScrollInProgress` and `isAtBottom`.
     */
    fun onScrollStateObserved(isScrollInProgress: Boolean, atBottom: Boolean) {
        if (!hasCompletedInitialScroll || isProgrammaticScroll) return
        if (isScrollInProgress && !atBottom) {
            isPinnedToBottom = false
        } else if (atBottom) {
            isPinnedToBottom = true
        }
    }

    /**
     * Performs the one-time initial jump to the end of a freshly loaded conversation.
     */
    suspend fun jumpToBottomForInitialLoad() {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            isProgrammaticScroll = true
            try {
                collapseTopBarImmediate()
                listState.scrollToItem(index = total - 1, scrollOffset = 0)
            } finally {
                isProgrammaticScroll = false
            }
        }
        isPinnedToBottom = true
        hasCompletedInitialScroll = true
    }

    /**
     * Scrolls to the bottom (animated or immediate), collapsing the flexible TopAppBar
     * cleanly and re-pinning sticky-bottom follow.
     */
    suspend fun scrollToBottom(animated: Boolean) {
        val total = listState.layoutInfo.totalItemsCount
        if (total <= 0) {
            isPinnedToBottom = true
            hasCompletedInitialScroll = true
            return
        }
        isPinnedToBottom = true
        isProgrammaticScroll = true
        try {
            val lastIndex = total - 1
            if (animated) {
                val collapseJob = coroutineScope.launch {
                    val limit = scrollBehavior.state.heightOffsetLimit
                    if (limit < 0f && scrollBehavior.state.heightOffset > limit) {
                        Animatable(scrollBehavior.state.heightOffset).animateTo(limit) {
                            scrollBehavior.state.heightOffset = value
                        }
                    }
                }
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
        } finally {
            isProgrammaticScroll = false
            hasCompletedInitialScroll = true
        }
    }

    /**
     * Lightweight, non-animated snap used while streaming text/steps grow.
     * Never runs if the user has scrolled up or is actively dragging.
     */
    suspend fun followStreamingIfPinned() {
        if (!hasCompletedInitialScroll || !isPinnedToBottom || listState.isScrollInProgress) return
        withFrameNanos { /* await frame measurement */ }
        if (!hasCompletedInitialScroll || !isPinnedToBottom || listState.isScrollInProgress) return
        val currentTotal = listState.layoutInfo.totalItemsCount
        if (currentTotal <= 0) return
        isProgrammaticScroll = true
        try {
            collapseTopBarImmediate()
            listState.scrollToItem(index = currentTotal - 1, scrollOffset = 0)
        } finally {
            isProgrammaticScroll = false
        }
    }

    /**
     * Captures the first visible chat item's key & pixel offset immediately before
     * requesting an older page of messages.
     */
    fun capturePrependAnchor(chatItems: List<ChatListItem>, topHeaderCount: Int) {
        if (pendingPrependAnchor != null || chatItems.isEmpty()) return
        val firstVisibleInfo = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index >= topHeaderCount && (it.index - topHeaderCount) < chatItems.size }
            ?: return
        val itemIndex = firstVisibleInfo.index - topHeaderCount
        val anchorItem = chatItems.getOrNull(itemIndex) ?: return
        // offset is relative to viewport top; scrollToItem expects positive offset downward into item
        val scrollOffset = (-firstVisibleInfo.offset).coerceAtLeast(0)
        pendingPrependAnchor = PrependAnchor(
            itemKey = anchorItem.key,
            scrollOffset = scrollOffset
        )
    }

    /**
     * Restores exact pixel position after older messages have been prepended to [chatItems].
     * Returns true if an anchor was matched and restored.
     */
    suspend fun restorePrependAnchorIfNeeded(chatItems: List<ChatListItem>, topHeaderCount: Int): Boolean {
        val anchor = pendingPrependAnchor ?: return false
        val newItemIndex = chatItems.indexOfFirst { it.key == anchor.itemKey }
        if (newItemIndex >= 0) {
            pendingPrependAnchor = null
            isProgrammaticScroll = true
            try {
                listState.scrollToItem(
                    index = newItemIndex + topHeaderCount,
                    scrollOffset = anchor.scrollOffset
                )
                return true
            } finally {
                isProgrammaticScroll = false
            }
        }
        return false
    }

    fun clearPrependAnchor() {
        pendingPrependAnchor = null
    }

    /**
     * Keeps the latest message visible when the soft keyboard opens while pinned at bottom.
     */
    suspend fun onImeOpenedIfPinned() {
        if (!hasCompletedInitialScroll || !isPinnedToBottom) return
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) {
            isProgrammaticScroll = true
            try {
                listState.scrollToItem(index = total - 1, scrollOffset = 0)
            } finally {
                isProgrammaticScroll = false
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
