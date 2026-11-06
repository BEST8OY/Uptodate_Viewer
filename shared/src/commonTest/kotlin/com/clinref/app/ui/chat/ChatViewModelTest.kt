package com.clinref.app.ui.chat

import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.domain.ai.ChatExecutionManager
import com.clinref.app.domain.ai.ClinicalOrchestrator
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.SecureLogger
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.fakes.FakeSecurePreferences
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.clinref.app.domain.ai.ChatExecutionEvent
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
    private val reliabilityManager = ReliabilityManager()
    private val securePreferences = FakeSecurePreferences()
    private val secureLogger: SecureLogger = mockk(relaxed = true)
    private val clinicalOrchestrator: ClinicalOrchestrator = mockk(relaxed = true)

    private lateinit var chatExecutionManager: ChatExecutionManager
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { streamingManager.currentStatusText } returns MutableStateFlow("")
        chatExecutionManager = ChatExecutionManager(
            conversationRepository = conversationRepository,
            koogAgentFactory = koogAgentFactory,
            clinicalOrchestrator = clinicalOrchestrator,
            streamingManager = streamingManager,
            reliabilityManager = reliabilityManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            ioDispatcher = testDispatcher
        )
        viewModel = ChatViewModel(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            streamingManager = streamingManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            chatExecutionManager = chatExecutionManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
    fun testAssistantMessageCompletedDoesNotEmitScrollEvent() = runTest(testDispatcher) {
        val executionEvents = MutableSharedFlow<ChatExecutionEvent>()
        val mockManager: ChatExecutionManager = mockk(relaxed = true) {
            every { events } returns executionEvents
            every { canSendMessage(any()) } returns true
            every { sendMessage(any(), any()) } returns true
        }
        val vm = ChatViewModel(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            streamingManager = streamingManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            chatExecutionManager = mockManager
        )
        vm.loadConversation("conv-1")
        advanceUntilIdle()

        var scrollEventEmitted = false
        val job = launch {
            vm.scrollEvents.collect {
                scrollEventEmitted = true
            }
        }

        val assistantMsg = MessageEntity(
            id = "asst-1",
            conversationId = "conv-1",
            role = "assistant",
            content = "Completed guidance.",
            timestamp = 2000L
        )
        executionEvents.emit(ChatExecutionEvent.AssistantMessageCompleted("conv-1", assistantMsg, emptyList<String>()))
        advanceUntilIdle()

        assertFalse("AssistantMessageCompleted must NOT emit scroll events", scrollEventEmitted)
        job.cancel()
    }

    @Test
    fun testLifecycleContinuityAcrossEmissions() = runTest(testDispatcher) {
        val executionEvents = MutableSharedFlow<ChatExecutionEvent>()
        val mockManager: ChatExecutionManager = mockk(relaxed = true) {
            every { events } returns executionEvents
            every { canSendMessage(any()) } returns true
            every { sendMessage(any(), any()) } returns true
        }
        val vm = ChatViewModel(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            streamingManager = streamingManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            chatExecutionManager = mockManager
        )
        vm.loadConversation("conv-1")
        advanceUntilIdle()

        // Send user message
        vm.sendMessage("Clinical query")
        assertTrue("isAwaitingResponse must become true synchronously upon send", vm.isAwaitingResponse.value)

        // GenerationStarted emission
        executionEvents.emit(ChatExecutionEvent.GenerationStarted("conv-1", "asst-1"))
        advanceUntilIdle()
        assertEquals("asst-1", vm.activeAssistantMessageId.value)
        assertTrue(vm.isAwaitingResponse.value)

        // UserMessageAdded emission
        val userMsg = MessageEntity("u1", "conv-1", "user", "Clinical query", 1000L)
        executionEvents.emit(ChatExecutionEvent.UserMessageAdded("conv-1", userMsg))
        advanceUntilIdle()
        assertEquals(listOf("u1"), vm.chatItems.value.map { it.key })
        assertEquals("asst-1", vm.activeAssistantMessageId.value)

        // AssistantMessageCompleted emission
        val assistantMsg = MessageEntity("asst-1", "conv-1", "assistant", "Guidance", 2000L)
        executionEvents.emit(ChatExecutionEvent.AssistantMessageCompleted("conv-1", assistantMsg, emptyList()))
        advanceUntilIdle()
        assertTrue("chatItems must contain assistant message", vm.chatItems.value.any { it.key == "asst-1" })
        assertNull("activeAssistantMessageId must be cleared", vm.activeAssistantMessageId.value)
        assertFalse("isAwaitingResponse must reset to false", vm.isAwaitingResponse.value)
    }

    @Test
    fun testConversationSwitchingDuringStreamPreservesState() = runTest(testDispatcher) {
        val mockEvents = MutableSharedFlow<ChatExecutionEvent>()
        val mockManager: ChatExecutionManager = mockk(relaxed = true) {
            every { events } returns mockEvents
            every { isGenerating("conv-A") } returns true
            every { isGenerating("conv-B") } returns false
            every { activeAssistantMessageId } returns MutableStateFlow("asst-A")
        }
        val vm = ChatViewModel(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            streamingManager = streamingManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            chatExecutionManager = mockManager
        )

        // 1. Load active conversation A
        vm.loadConversation("conv-A")
        advanceUntilIdle()
        assertEquals("asst-A", vm.activeAssistantMessageId.value)
        assertTrue(vm.isAwaitingResponse.value)

        // 2. Switch to idle conversation B
        vm.loadConversation("conv-B")
        advanceUntilIdle()
        assertNull(vm.activeAssistantMessageId.value)
        assertFalse(vm.isAwaitingResponse.value)

        // Verify that B's FAB-governing state is false (FAB is NOT suppressed in B while A generates)
        val isAiActiveInB = vm.activeAssistantMessageId.value != null || vm.isAwaitingResponse.value
        assertFalse("Idle conversation B must NOT suppress the FAB when A is generating", isAiActiveInB)

        // Verify that switching to B did NOT call streamingManager.reset() (which would wipe conv-A's buffer)
        verify(exactly = 0) { streamingManager.reset() }

        // 3. Switch back to active conversation A
        vm.loadConversation("conv-A")
        advanceUntilIdle()
        assertEquals("asst-A", vm.activeAssistantMessageId.value)
        assertTrue(vm.isAwaitingResponse.value)
    }

    @Test
    fun testRejectedSendMessageReturnsFalseAndLeavesStateIntact() = runTest(testDispatcher) {
        val mockManager: ChatExecutionManager = mockk(relaxed = true) {
            every { canSendMessage("conv-1") } returns false
            every { events } returns MutableSharedFlow()
        }
        val vm = ChatViewModel(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            streamingManager = streamingManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            chatExecutionManager = mockManager
        )
        vm.loadConversation("conv-1")
        advanceUntilIdle()

        val accepted = vm.sendMessage("Clinical query")
        assertFalse("sendMessage must return false when manager rejects send", accepted)
        assertFalse("isAwaitingResponse must remain false when send is rejected", vm.isAwaitingResponse.value)
        verify(exactly = 0) { mockManager.sendMessage(any(), any()) }
    }

    @Test
    fun testSendMessageRevertsIsAwaitingResponseWhenExecutionManagerReturnsFalse() = runTest(testDispatcher) {
        val mockManager: ChatExecutionManager = mockk(relaxed = true) {
            every { canSendMessage("conv-1") } returns true
            every { sendMessage("conv-1", "Clinical query") } returns false
            every { events } returns MutableSharedFlow()
        }
        val vm = ChatViewModel(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            streamingManager = streamingManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            chatExecutionManager = mockManager
        )
        vm.loadConversation("conv-1")
        advanceUntilIdle()

        val accepted = vm.sendMessage("Clinical query")
        assertFalse("sendMessage must return false when execution manager returns false", accepted)
        assertFalse("isAwaitingResponse must be reverted to false", vm.isAwaitingResponse.value)
    }

    @Test
    fun testRepositoryThrowInHandleAgentErrorResetsViewModelFlags() = runTest(testDispatcher) {
        // When addMessage throws inside error persistence, the manager's resilient try-catch
        // guarantees GenerationError still emits, and the ViewModel resets both flags.
        coEvery { conversationRepository.addMessage(match { it.isError }) } throws RuntimeException("DB error")
        securePreferences.saveConfiguration(
            com.clinref.app.domain.ai.AiConfiguration(
                provider = com.clinref.app.domain.ai.AiProvider.ANTHROPIC,
                model = "claude-3-5-sonnet",
                isConfigured = true
            )
        )
        coEvery { koogAgentFactory.resolveRuntime(any()) } throws RuntimeException("Runtime crash")

        viewModel.loadConversation("conv-1")
        advanceUntilIdle()

        val accepted = viewModel.sendMessage("Query")
        assertTrue(accepted)
        assertTrue("isAwaitingResponse starts true", viewModel.isAwaitingResponse.value)

        advanceUntilIdle()

        assertFalse("isAwaitingResponse must reset to false after error", viewModel.isAwaitingResponse.value)
        assertNull("activeAssistantMessageId must reset to null after error", viewModel.activeAssistantMessageId.value)
    }

    @Test
    fun testRepositoryThrowInCancellationResetsViewModelFlags() = runTest(testDispatcher) {
        // When addMessage throws inside cancel persistence, the manager's resilient try-catch
        // guarantees GenerationCancelled still emits, and the ViewModel resets both flags.
        coEvery { conversationRepository.addMessage(match { it.role == "cancelled" }) } throws RuntimeException("DB locked")

        viewModel.loadConversation("conv-1")
        advanceUntilIdle()

        viewModel.cancelGeneration()
        advanceUntilIdle()

        assertFalse("isAwaitingResponse must reset to false after cancellation", viewModel.isAwaitingResponse.value)
        assertNull("activeAssistantMessageId must reset to null after cancellation", viewModel.activeAssistantMessageId.value)
    }

    @Test
    fun testStaleQ1EventDoesNotClearQ2Flags() = runTest(testDispatcher) {
        val eventsFlow = MutableSharedFlow<ChatExecutionEvent>()
        val mockManager: ChatExecutionManager = mockk(relaxed = true) {
            every { events } returns eventsFlow
            every { canSendMessage("conv-1") } returns true
            every { sendMessage("conv-1", any()) } returns true
        }
        val vm = ChatViewModel(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            streamingManager = streamingManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            chatExecutionManager = mockManager
        )
        vm.loadConversation("conv-1")
        advanceUntilIdle()

        // 1. Start Q2
        vm.sendMessage("Q2 question")
        eventsFlow.emit(ChatExecutionEvent.GenerationStarted("conv-1", "asst-2"))
        advanceUntilIdle()

        assertTrue("isAwaitingResponse must be true while Q2 is running", vm.isAwaitingResponse.value)
        assertEquals("activeAssistantMessageId must be asst-2", "asst-2", vm.activeAssistantMessageId.value)

        // 2. Stale Q1 GenerationCancelled event arrives
        val staleCancelMsg = MessageEntity(
            id = "asst-1",
            conversationId = "conv-1",
            role = "cancelled",
            content = "Q1 cancelled",
            timestamp = 100L,
            isError = true
        )
        eventsFlow.emit(ChatExecutionEvent.GenerationCancelled("conv-1", staleCancelMsg))
        advanceUntilIdle()

        assertTrue("Stale Q1 cancel must NOT clear Q2 isAwaitingResponse", vm.isAwaitingResponse.value)
        assertEquals("Stale Q1 cancel must NOT clear Q2 activeAssistantMessageId", "asst-2", vm.activeAssistantMessageId.value)
        assertTrue("Stale Q1 message must still be appended to list", vm.messages.value.any { it.id == "asst-1" })

        // 3. Stale Q0 AssistantMessageCompleted event arrives
        val staleCompletedMsg = MessageEntity(
            id = "asst-0",
            conversationId = "conv-1",
            role = "assistant",
            content = "Q0 completed",
            timestamp = 50L
        )
        eventsFlow.emit(ChatExecutionEvent.AssistantMessageCompleted("conv-1", staleCompletedMsg, emptyList()))
        advanceUntilIdle()

        assertTrue("Stale Q0 completion must NOT clear Q2 isAwaitingResponse", vm.isAwaitingResponse.value)
        assertEquals("Stale Q0 completion must NOT clear Q2 activeAssistantMessageId", "asst-2", vm.activeAssistantMessageId.value)
        assertTrue("Stale Q0 message must still be appended to list", vm.messages.value.any { it.id == "asst-0" })

        // 4. Stale Q-err GenerationError event arrives
        val staleErrorMsg = MessageEntity(
            id = "asst-err",
            conversationId = "conv-1",
            role = "assistant",
            content = "Q-err error",
            timestamp = 75L,
            isError = true
        )
        eventsFlow.emit(ChatExecutionEvent.GenerationError("conv-1", staleErrorMsg))
        advanceUntilIdle()

        assertTrue("Stale error must NOT clear Q2 isAwaitingResponse", vm.isAwaitingResponse.value)
        assertEquals("Stale error must NOT clear Q2 activeAssistantMessageId", "asst-2", vm.activeAssistantMessageId.value)
        assertTrue("Stale error message must still be appended to list", vm.messages.value.any { it.id == "asst-err" })

        // 5. Matching Q2 completion event arrives
        val q2CompletedMsg = MessageEntity(
            id = "asst-2",
            conversationId = "conv-1",
            role = "assistant",
            content = "Q2 answer",
            timestamp = 200L
        )
        eventsFlow.emit(ChatExecutionEvent.AssistantMessageCompleted("conv-1", q2CompletedMsg, emptyList()))
        advanceUntilIdle()

        assertFalse("Matching Q2 completion must clear isAwaitingResponse", vm.isAwaitingResponse.value)
        assertNull("Matching Q2 completion must clear activeAssistantMessageId", vm.activeAssistantMessageId.value)
        assertTrue("Q2 message must be appended to list", vm.messages.value.any { it.id == "asst-2" })
    }
}

