package com.clinref.app.ui.chat

import com.clinref.app.domain.ai.ClinicalSource
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.StreamingManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDisplayItemsTest {

    private val userMsg = ChatListItem.Message(
        MessageUiModel(
            id = "user-1",
            role = "user",
            content = "What is the dose for syphilis?",
            timestamp = 1000L
        )
    )

    private val assistantPersistedMsg = ChatListItem.Message(
        MessageUiModel(
            id = "asst-1",
            role = "assistant",
            content = "Penicillin G benzathine 2.4 million units IM single dose.",
            timestamp = 2000L
        )
    )

    private val clinicalRefs = ChatListItem.ClinicalReferences(
        messageId = "asst-1",
        articles = listOf(ClinicalSource.Article("t1", "Syphilis treatment")),
        tables = emptyList()
    )

    @Test
    fun testResetBeforeEventPreservesAssistantKeyInEverySnapshot() {
        val baseChatItems = listOf(userMsg)
        val assistantId = "asst-1"
        val streamedAnswer = "Penicillin G benzathine 2.4 million units IM single dose."

        // Snapshot 1: Active streaming with incoming deltas
        val snapshot1 = computeDisplayItems(
            chatItems = baseChatItems,
            activeAssistantId = assistantId,
            streamingText = streamedAnswer,
            fallbackText = streamedAnswer
        )
        val keys1 = snapshot1.map { it.key }
        assertTrue("Snapshot 1 must contain assistant key", keys1.contains(assistantId))
        assertEquals(2, snapshot1.size)

        // Snapshot 2: RACE CONDITION - reset() runs in background before AssistantMessageCompleted reaches ViewModel
        // streamingText is wiped to "", but fallbackText retains the last streamed tokens
        val snapshot2 = computeDisplayItems(
            chatItems = baseChatItems,
            activeAssistantId = assistantId,
            streamingText = "",
            fallbackText = streamedAnswer
        )
        val keys2 = snapshot2.map { it.key }
        assertTrue("Snapshot 2 (post-reset, pre-event) must NOT drop assistant key", keys2.contains(assistantId))
        assertEquals(2, snapshot2.size)
        val item2 = snapshot2.filterIsInstance<ChatListItem.Message>().first { it.uiModel.id == assistantId }
        assertEquals(streamedAnswer, item2.uiModel.content)

        // Snapshot 3: AssistantMessageCompleted processed by ChatViewModel, updateMessages() runs
        val chatItemsWithPersisted = listOf(userMsg, assistantPersistedMsg, clinicalRefs)
        val snapshot3 = computeDisplayItems(
            chatItems = chatItemsWithPersisted,
            activeAssistantId = assistantId,
            streamingText = "",
            fallbackText = streamedAnswer
        )
        val keys3 = snapshot3.map { it.key }
        assertTrue("Snapshot 3 must contain assistant key", keys3.contains(assistantId))
        assertTrue("Snapshot 3 must contain references key", keys3.contains("refs_asst-1"))
        assertEquals(3, snapshot3.size)

        // Snapshot 4: activeAssistantId cleared to null
        val snapshot4 = computeDisplayItems(
            chatItems = chatItemsWithPersisted,
            activeAssistantId = null,
            streamingText = "",
            fallbackText = ""
        )
        val keys4 = snapshot4.map { it.key }
        assertTrue("Snapshot 4 must contain assistant key", keys4.contains(assistantId))
        assertTrue("Snapshot 4 must contain references key", keys4.contains("refs_asst-1"))
        assertEquals(3, snapshot4.size)
    }

    @Test
    fun testEventBeforeResetPreservesAssistantKeyInEverySnapshot() {
        val baseChatItems = listOf(userMsg)
        val assistantId = "asst-1"
        val streamedAnswer = "Penicillin G benzathine 2.4 million units IM single dose."

        // Snapshot 1: Active streaming
        val snapshot1 = computeDisplayItems(
            chatItems = baseChatItems,
            activeAssistantId = assistantId,
            streamingText = streamedAnswer,
            fallbackText = streamedAnswer
        )
        assertTrue(snapshot1.map { it.key }.contains(assistantId))

        // Snapshot 2: Event arrives before reset()
        val chatItemsWithPersisted = listOf(userMsg, assistantPersistedMsg, clinicalRefs)
        val snapshot2 = computeDisplayItems(
            chatItems = chatItemsWithPersisted,
            activeAssistantId = assistantId,
            streamingText = streamedAnswer,
            fallbackText = streamedAnswer
        )
        assertTrue("Snapshot 2 must retain assistant key", snapshot2.map { it.key }.contains(assistantId))
        assertEquals(3, snapshot2.size)

        // Snapshot 3: activeAssistantId cleared to null
        val snapshot3 = computeDisplayItems(
            chatItems = chatItemsWithPersisted,
            activeAssistantId = null,
            streamingText = streamedAnswer,
            fallbackText = streamedAnswer
        )
        assertTrue("Snapshot 3 must retain assistant key", snapshot3.map { it.key }.contains(assistantId))

        // Snapshot 4: reset() runs
        val snapshot4 = computeDisplayItems(
            chatItems = chatItemsWithPersisted,
            activeAssistantId = null,
            streamingText = "",
            fallbackText = ""
        )
        assertTrue("Snapshot 4 must retain assistant key", snapshot4.map { it.key }.contains(assistantId))
        assertEquals(3, snapshot4.size)
    }

    @Test
    fun testCancelPreservesAssistantKeyInEverySnapshot() {
        val baseChatItems = listOf(userMsg)
        val assistantId = "asst-1"
        val partialAnswer = "Penicillin G"

        // Snapshot 1: Active streaming prior to cancellation
        val snapshot1 = computeDisplayItems(
            chatItems = baseChatItems,
            activeAssistantId = assistantId,
            streamingText = partialAnswer,
            fallbackText = partialAnswer
        )
        assertTrue(snapshot1.map { it.key }.contains(assistantId))

        // Snapshot 2: GenerationCancelled event received and merged into chatItems
        val cancelledMsg = ChatListItem.Message(
            MessageUiModel(
                id = assistantId,
                role = "cancelled",
                content = "[Clinical generation session terminated by operator]",
                timestamp = 2000L,
                isError = true
            )
        )
        val chatItemsWithCancelled = listOf(userMsg, cancelledMsg)
        val snapshot2 = computeDisplayItems(
            chatItems = chatItemsWithCancelled,
            activeAssistantId = assistantId,
            streamingText = "",
            fallbackText = partialAnswer
        )
        assertTrue("Snapshot 2 must retain assistant key", snapshot2.map { it.key }.contains(assistantId))

        // Snapshot 3: activeAssistantId cleared to null
        val snapshot3 = computeDisplayItems(
            chatItems = chatItemsWithCancelled,
            activeAssistantId = null,
            streamingText = "",
            fallbackText = ""
        )
        assertTrue("Snapshot 3 must retain assistant key", snapshot3.map { it.key }.contains(assistantId))
        assertEquals(2, snapshot3.size)
    }

    @Test
    fun testErrorPreservesAssistantKeyInEverySnapshot() {
        val baseChatItems = listOf(userMsg)
        val assistantId = "asst-1"

        // Snapshot 1: Generation started before tokens
        val snapshot1 = computeDisplayItems(
            chatItems = baseChatItems,
            activeAssistantId = assistantId,
            streamingText = "",
            fallbackText = ""
        )
        // No tokens yet, so baseChatItems returned
        assertEquals(1, snapshot1.size)

        // Snapshot 2: GenerationError arrives and merged into chatItems
        val errorMsg = ChatListItem.Message(
            MessageUiModel(
                id = assistantId,
                role = "assistant",
                content = "Reference core timeout.",
                timestamp = 2000L,
                isError = true
            )
        )
        val chatItemsWithError = listOf(userMsg, errorMsg)
        val snapshot2 = computeDisplayItems(
            chatItems = chatItemsWithError,
            activeAssistantId = assistantId,
            streamingText = "",
            fallbackText = ""
        )
        assertTrue("Snapshot 2 must retain assistant key", snapshot2.map { it.key }.contains(assistantId))

        // Snapshot 3: activeAssistantId cleared to null
        val snapshot3 = computeDisplayItems(
            chatItems = chatItemsWithError,
            activeAssistantId = null,
            streamingText = "",
            fallbackText = ""
        )
        assertTrue("Snapshot 3 must retain assistant key", snapshot3.map { it.key }.contains(assistantId))
        assertEquals(2, snapshot3.size)
    }

    @Test
    fun testHolderStreamingSequenceRetainsAssistantKeyAcrossResetAndCompletion() {
        val holder = ChatDisplayItemsHolder()
        val baseChatItems = listOf(userMsg)
        val assistantId = "asst-1"

        // 1. First streaming delta arrives
        holder.onStreamingText(assistantId, "Penicillin G")
        val fallback1 = holder.getFallbackText(assistantId)
        val items1 = computeDisplayItems(baseChatItems, assistantId, "Penicillin G", fallback1)
        assertEquals(2, items1.size)
        assertTrue(items1.map { it.key }.contains(assistantId))
        assertEquals("Penicillin G", holder.lastStreamedText)

        // 2. Second streaming delta arrives
        holder.onStreamingText(assistantId, "Penicillin G benzathine 2.4 million units")
        val fallback2 = holder.getFallbackText(assistantId)
        val items2 = computeDisplayItems(baseChatItems, assistantId, "Penicillin G benzathine 2.4 million units", fallback2)
        assertEquals(2, items2.size)
        assertTrue(items2.map { it.key }.contains(assistantId))
        assertEquals("Penicillin G benzathine 2.4 million units", holder.lastStreamedText)

        // 3. Reset occurs in background before ViewModel applies completion event (streamingText wiped to "")
        val fallback3 = holder.getFallbackText(assistantId)
        val items3 = computeDisplayItems(baseChatItems, assistantId, "", fallback3)
        assertEquals(2, items3.size)
        assertTrue("Assistant key must NOT drop when buffer is reset prior to event", items3.map { it.key }.contains(assistantId))
        val messageItem = items3.filterIsInstance<ChatListItem.Message>().first { it.uiModel.id == assistantId }
        assertEquals("Penicillin G benzathine 2.4 million units", messageItem.uiModel.content)

        // 4. AssistantMessageCompleted event applied by ViewModel
        val chatItemsWithPersisted = listOf(userMsg, assistantPersistedMsg, clinicalRefs)
        val fallback4 = holder.getFallbackText(assistantId)
        val items4 = computeDisplayItems(chatItemsWithPersisted, assistantId, "", fallback4)
        assertEquals(3, items4.size)
        assertTrue(items4.map { it.key }.contains(assistantId))
        assertTrue(items4.map { it.key }.contains("refs_asst-1"))

        // 5. activeAssistantId cleared to null
        holder.onStreamingText(null, "")
        val fallback5 = holder.getFallbackText(null)
        val items5 = computeDisplayItems(chatItemsWithPersisted, null, "", fallback5)
        assertEquals(3, items5.size)
        assertTrue(items5.map { it.key }.contains(assistantId))
        assertTrue(items5.map { it.key }.contains("refs_asst-1"))
        assertEquals("", holder.lastStreamedText)
    }

    /**
     * Holder-scoping test: Verifies that ChatDisplayItemsHolder scopes its fallback cache
     * strictly by assistant ID, ensuring that even if Q1 had streamed, Q2's initial render
     * with empty streamingText and a new assistant ID does not leak Q1's text.
     * Note: Real lifecycle ordering (reset() called before GenerationStarted) is verified in ChatExecutionManagerTest.
     */
    @Test
    fun testHolderIdScopingPreventsQ1TextLeakIntoQ2() {
        val streamingManager = StreamingManager()
        val holder = ChatDisplayItemsHolder()
        val baseChatItems = listOf(userMsg)

        // 1. Q1 streams and completes
        streamingManager.onStreamingTextDelta("Q1 answer: Ceftriaxone 1g IV")
        holder.onStreamingText("asst-1", streamingManager.streamingText.value)
        val q1Display = computeDisplayItems(
            baseChatItems,
            "asst-1",
            streamingManager.streamingText.value,
            holder.getFallbackText("asst-1")
        )
        assertEquals("Q1 answer: Ceftriaxone 1g IV", (q1Display[1] as ChatListItem.Message).uiModel.content)

        streamingManager.onCompleted("Q1 answer: Ceftriaxone 1g IV", SafetyValidator.ValidationResult(passed = true, warnings = emptyList()))

        // 2. Q2 begins: reset() is called BEFORE GenerationStarted("asst-2")
        streamingManager.reset()

        // 3. GenerationStarted("asst-2") emitted, activeAssistantId becomes "asst-2"
        // Before any Q2 deltas arrive, streamingText is "" and holder has no fallback for "asst-2"
        val q2Fallback = holder.getFallbackText("asst-2")
        val q2Display = computeDisplayItems(baseChatItems, "asst-2", streamingManager.streamingText.value, q2Fallback)

        // Assert that Q2 does NOT show Q1's text
        assertEquals("Q2 display items must not contain assistant bubble before Q2 tokens arrive", 1, q2Display.size)
        assertEquals("", q2Fallback)
    }
}
