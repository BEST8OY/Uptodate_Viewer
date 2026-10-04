package com.clinref.app.ui.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * Visual scroll position state for a chat conversation.
 *
 * @property firstVisibleItemIndex The index of the first visible item in the LazyColumn.
 * @property firstVisibleItemScrollOffset The pixel offset of the first visible item.
 * @property anchorMessageId Unique ID of the message at or nearest the top of the viewport.
 * @property isAtBottom Whether the user was at the bottom of the conversation when leaving.
 */
data class ChatScrollPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int = 0,
    val anchorMessageId: String? = null,
    val isAtBottom: Boolean = true
)

/**
 * In-memory thread-safe cache preserving chat scroll positions across in-session
 * navigation (e.g. reading an article citation and returning, or tab switching).
 */
class ChatScrollStateCache {
    private val positions = ConcurrentHashMap<String, ChatScrollPosition>()

    fun get(conversationId: String): ChatScrollPosition? = positions[conversationId]

    fun save(conversationId: String, position: ChatScrollPosition) {
        positions[conversationId] = position
    }

    fun clear(conversationId: String) {
        positions.remove(conversationId)
    }

    fun clearAll() {
        positions.clear()
    }
}
