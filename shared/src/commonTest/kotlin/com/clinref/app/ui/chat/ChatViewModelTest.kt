package com.clinref.app.ui.chat

import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.SecureLogger
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.fakes.FakeSecurePreferences
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val contentRepository: ContentRepository = mockk(relaxed = true)
    private val koogAgentFactory: KoogAgentFactory = mockk(relaxed = true)
    private val streamingManager: StreamingManager = mockk(relaxed = true)
    private val reliabilityManager: ReliabilityManager = mockk(relaxed = true)
    private val securePreferences = FakeSecurePreferences()
    private val secureLogger: SecureLogger = mockk(relaxed = true)
    private val chatScrollStateCache = ChatScrollStateCache()

    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ChatViewModel(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            koogAgentFactory = koogAgentFactory,
            streamingManager = streamingManager,
            reliabilityManager = reliabilityManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            chatScrollStateCache = chatScrollStateCache
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testChatScrollStateCacheSaveAndRetrieve() {
        val position = ChatScrollPosition(
            firstVisibleItemIndex = 12,
            firstVisibleItemScrollOffset = 45,
            anchorMessageId = "msg-123",
            isAtBottom = false
        )
        chatScrollStateCache.save("conv-1", position)

        val retrieved = chatScrollStateCache.get("conv-1")
        assertNotNull(retrieved)
        assertEquals(12, retrieved?.firstVisibleItemIndex)
        assertEquals(45, retrieved?.firstVisibleItemScrollOffset)
        assertEquals("msg-123", retrieved?.anchorMessageId)
        assertFalse(retrieved?.isAtBottom ?: true)

        chatScrollStateCache.clear("conv-1")
        assertNull(chatScrollStateCache.get("conv-1"))
    }

    @Test
    fun testLoadConversationLoadsChronologicalMessages() = runTest(testDispatcher) {
        // Repository returns messages ordered by timestamp DESC
        val m1 = MessageEntity("m1", "conv-1", "user", "Hello", 1000L)
        val m2 = MessageEntity("m2", "conv-1", "assistant", "Hi doctor", 2000L)
        coEvery { conversationRepository.getMessagesPage("conv-1", 50, 0) } returns listOf(m2, m1)

        viewModel.loadConversation("conv-1")
        advanceUntilIdle()

        val messages = viewModel.messages.value
        assertEquals(2, messages.size)
        // Reversed: m1 (1000L) first, then m2 (2000L)
        assertEquals("m1", messages[0].id)
        assertEquals("m2", messages[1].id)
    }

    @Test
    fun testLoadConversationIsIdempotentWhenAlreadyLoaded() = runTest(testDispatcher) {
        val m1 = MessageEntity("m1", "conv-1", "user", "First", 1000L)
        coEvery { conversationRepository.getMessagesPage("conv-1", 50, 0) } returns listOf(m1)

        viewModel.loadConversation("conv-1")
        advanceUntilIdle()
        assertEquals(1, viewModel.messages.value.size)

        // Second call with same conversationId and already loaded messages should be a no-op
        viewModel.loadConversation("conv-1")
        advanceUntilIdle()
        assertEquals(1, viewModel.messages.value.size)
    }

    @Test
    fun testLoadOlderMessagesPrependsChronologically() = runTest(testDispatcher) {
        // Initial page of 50 messages to trigger hasMoreMessages = true
        val initialMessages = (1..50).map { i ->
            MessageEntity("m-init-$i", "conv-1", "user", "Message $i", 2000L + i)
        }.reversed() // getMessagesPage returns DESC
        coEvery { conversationRepository.getMessagesPage("conv-1", 50, 0) } returns initialMessages

        // Older page of 2 messages (m-old-1, m-old-2 in DESC order: m-old-2, m-old-1)
        val old1 = MessageEntity("m-old-1", "conv-1", "user", "Old 1", 1000L)
        val old2 = MessageEntity("m-old-2", "conv-1", "assistant", "Old 2", 1001L)
        coEvery { conversationRepository.getMessagesPage("conv-1", 50, 50) } returns listOf(old2, old1)

        viewModel.loadConversation("conv-1")
        advanceUntilIdle()
        assertEquals(50, viewModel.messages.value.size)
        assertTrue(viewModel.hasMoreMessages.value)

        viewModel.loadOlderMessages()
        advanceUntilIdle()

        // After loading older, total is 52, and oldest are prepended chronologically
        val messageIds = viewModel.messages.value.map { it.id }
        assertEquals(52, messageIds.size)
        assertEquals("m-old-1", messageIds[0])
        assertEquals("m-old-2", messageIds[1])
        assertEquals("m-init-1", messageIds[2])
    }

    @Test
    fun testSendMessageEmitsScrollToBottomEvent() = runTest(testDispatcher) {
        viewModel.loadConversation("conv-1")
        advanceUntilIdle()

        viewModel.sendMessage("Checking renal function")

        val event = viewModel.scrollEvents.first()
        assertTrue(event is ChatScrollEvent.ScrollToBottom)
        assertTrue((event as ChatScrollEvent.ScrollToBottom).animated)
        val userMsg = viewModel.messages.value.first { it.role == "user" }
        assertEquals(userMsg.id, event.awaitMessageId)
    }

    @Test
    fun testScrollPositionPreservationViaViewModel() {
        val position = ChatScrollPosition(
            firstVisibleItemIndex = 5,
            firstVisibleItemScrollOffset = 100,
            anchorMessageId = "msg-anchor",
            isAtBottom = false
        )

        viewModel.loadConversation("conv-1")
        viewModel.saveScrollPosition(position)

        val saved = viewModel.getSavedScrollPosition("conv-1")
        assertNotNull(saved)
        assertEquals(5, saved?.firstVisibleItemIndex)
        assertEquals(100, saved?.firstVisibleItemScrollOffset)
        assertEquals("msg-anchor", saved?.anchorMessageId)
        assertFalse(saved?.isAtBottom ?: true)

        viewModel.clearSavedScrollPosition("conv-1")
        assertNull(viewModel.getSavedScrollPosition("conv-1"))
    }
}
