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
            secureLogger = secureLogger
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testReadingPositionPreservation() {
        assertNull(viewModel.savedReadingPosition)
        viewModel.updateReadingPosition(12, 45, "msg-123")

        val position = viewModel.savedReadingPosition
        assertNotNull(position)
        assertEquals(12, position?.index)
        assertEquals(45, position?.offset)
        assertEquals("msg-123", position?.anchorMessageId)

        // Spurious teardown capture (0, 0, null) must NOT overwrite valid reading position
        viewModel.updateReadingPosition(0, 0, null)
        assertEquals(12, viewModel.savedReadingPosition?.index)
        assertEquals("msg-123", viewModel.savedReadingPosition?.anchorMessageId)

        viewModel.clearSavedReadingPosition()
        assertNull(viewModel.savedReadingPosition)
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
    fun testFreshConversationLoadResetsReadingPosition() = runTest(testDispatcher) {
        viewModel.updateReadingPosition(5, 100)
        assertNotNull(viewModel.savedReadingPosition)

        viewModel.loadConversation("conv-1")
        assertNull(viewModel.savedReadingPosition)
    }

    @Test
    fun testChatItemsDecomposesAssistantMessageWithReferences() = runTest(testDispatcher) {
        val userMsg = MessageEntity(
            id = "user-1",
            conversationId = "conv-1",
            role = "user",
            content = "What are the first-line asthma treatments?",
            timestamp = 1000L
        )
        val assistantMsgWithRefs = MessageEntity(
            id = "asst-1",
            conversationId = "conv-1",
            role = "assistant",
            content = "Inhaled corticosteroids are first line.",
            timestamp = 2000L,
            topicRefsJson = """[{"topicId":"1001","topicTitle":"Asthma Management","label":"Asthma Management"}]"""
        )
        coEvery { contentRepository.getTopicTitle("1001") } returns "Asthma Management"
        coEvery { conversationRepository.getMessagesPage("conv-1", 50, 0) } returns listOf(assistantMsgWithRefs, userMsg)

        viewModel.loadConversation("conv-1")
        advanceUntilIdle()

        val items = viewModel.chatItems.value
        // User message: 1 item (ChatListItem.Message)
        // Assistant message: 2 items (ChatListItem.Message + ChatListItem.ClinicalReferences)
        assertEquals(3, items.size)

        val item0 = items[0]
        assertTrue(item0 is ChatListItem.Message)
        assertEquals("user-1", (item0 as ChatListItem.Message).uiModel.id)
        assertEquals("user-1", item0.key)

        val item1 = items[1]
        assertTrue(item1 is ChatListItem.Message)
        assertEquals("asst-1", (item1 as ChatListItem.Message).uiModel.id)
        assertEquals("asst-1", item1.key)

        val item2 = items[2]
        assertTrue(item2 is ChatListItem.ClinicalReferences)
        val refsItem = item2 as ChatListItem.ClinicalReferences
        assertEquals("asst-1", refsItem.messageId)
        assertEquals("refs_asst-1", refsItem.key)
        assertEquals(1, refsItem.articles.size)
        assertEquals("1001", refsItem.articles[0].topicId)
    }

    @Test
    fun testChatItemsDoesNotDecomposeAssistantMessageWithoutReferences() = runTest(testDispatcher) {
        val assistantMsg = MessageEntity(
            id = "asst-plain",
            conversationId = "conv-1",
            role = "assistant",
            content = "Hello doctor, how can I help you today?",
            timestamp = 1000L
        )
        coEvery { conversationRepository.getMessagesPage("conv-1", 50, 0) } returns listOf(assistantMsg)

        viewModel.loadConversation("conv-1")
        advanceUntilIdle()

        val items = viewModel.chatItems.value
        assertEquals(1, items.size)
        assertTrue(items[0] is ChatListItem.Message)
        assertEquals("asst-plain", items[0].key)
    }

    @Test
    fun testCancelGenerationInsertsCancelledMessage() = runTest(testDispatcher) {
        viewModel.loadConversation("conv-1")
        advanceUntilIdle()

        viewModel.cancelGeneration()
        advanceUntilIdle()

        val lastMsg = viewModel.messages.value.lastOrNull()
        assertNotNull(lastMsg)
        assertEquals("cancelled", lastMsg?.role)
        assertTrue(lastMsg?.isError == true)
        assertTrue(lastMsg?.content?.contains("terminated by operator") == true)
    }

    @Test
    fun testSendMessageIgnoresBlankContent() = runTest(testDispatcher) {
        viewModel.loadConversation("conv-1")
        advanceUntilIdle()

        val initialCount = viewModel.messages.value.size
        viewModel.sendMessage("   ")
        advanceUntilIdle()

        assertEquals(initialCount, viewModel.messages.value.size)
    }

    @Test
    fun testChatItemsSynchronouslyUpdatedWithMessages() = runTest(testDispatcher) {
        val userMsg = MessageEntity("u1", "conv-1", "user", "Test", 1000L)
        coEvery { conversationRepository.getMessagesPage("conv-1", 50, 0) } returns listOf(userMsg)

        viewModel.loadConversation("conv-1")
        advanceUntilIdle()

        // _chatItems must be populated synchronously with _messages
        assertEquals(1, viewModel.messages.value.size)
        assertEquals(1, viewModel.chatItems.value.size)
        assertEquals("u1", viewModel.chatItems.value[0].key)
    }
}

