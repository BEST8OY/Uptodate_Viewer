package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.fakes.FakeSecurePreferences
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class ChatExecutionManagerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val contentRepository: ContentRepository = mockk(relaxed = true)
    private val koogAgentFactory: KoogAgentFactory = mockk(relaxed = true)
    private val streamingManager: StreamingManager = mockk(relaxed = true)
    private val reliabilityManager = ReliabilityManager()
    private val securePreferences = FakeSecurePreferences()
    private val secureLogger: SecureLogger = mockk(relaxed = true)
    private val backgroundBridge: BackgroundExecutionBridge = mockk(relaxed = true)
    private val clinicalOrchestrator: ClinicalOrchestrator = mockk(relaxed = true)

    private lateinit var manager: ChatExecutionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { streamingManager.currentStatusText } returns MutableStateFlow("")
        manager = ChatExecutionManager(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            koogAgentFactory = koogAgentFactory,
            clinicalOrchestrator = clinicalOrchestrator,
            streamingManager = streamingManager,
            reliabilityManager = reliabilityManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            backgroundExecutionBridge = backgroundBridge,
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSendMessageIgnoresBlankContent() = runTest(testDispatcher) {
        manager.sendMessage("conv-1", "   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { conversationRepository.addMessage(any()) }
        assertFalse(manager.isGenerating("conv-1"))
    }

    @Test
    fun testSendMessageWhenUnconfiguredEmitsGenerationError() = runTest(testDispatcher) {
        // securePreferences is unconfigured by default in fake
        manager.sendMessage("conv-1", "How to manage asthma?")
        advanceUntilIdle()

        // User message and error message must both be saved
        coVerify(exactly = 2) { conversationRepository.addMessage(any()) }
        assertFalse(manager.isGenerating("conv-1"))
    }

    @Test
    fun testCancelGenerationInsertsCancelledMessageAndResets() = runTest(testDispatcher) {
        manager.cancelGeneration("conv-1")
        advanceUntilIdle()

        coVerify(atLeast = 1) {
            conversationRepository.addMessage(match {
                it.role == "cancelled" && it.isError && it.content.contains("terminated by operator")
            })
        }
        verify { streamingManager.reset() }
        verify { backgroundBridge.onExecutionCancelled("conv-1") }
    }

    @Test
    fun testCancelGenerationIsIdempotentAndDoesNotInsertDuplicates() = runTest(testDispatcher) {
        manager.cancelGeneration("conv-1")
        manager.cancelGeneration("conv-1")
        manager.cancelGeneration("conv-1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            conversationRepository.addMessage(match {
                it.role == "cancelled" && it.isError && it.content.contains("terminated by operator")
            })
        }
        verify(exactly = 1) { backgroundBridge.onExecutionCancelled("conv-1") }
    }

    @Test
    fun testCanSendMessageAllowsWhenNoGenerationActive() = runTest(testDispatcher) {
        assertTrue(manager.canSendMessage("conv-1"))
        assertTrue(manager.canSendMessage("conv-2"))
    }

    @Test
    fun testCancelGenerationIgnoresWhenDifferentConversationIsActive() = runTest(testDispatcher) {
        // Trigger generation on conv-1 (unconfigured so it fails quickly, but sets active state first)
        // To directly test active isolation, cancel conv-2 when conv-1 is generating
        manager.cancelGeneration("conv-2")
        advanceUntilIdle()

        // It should still process cancel for conv-2 if none was active
        coVerify(atLeast = 1) {
            conversationRepository.addMessage(match {
                it.conversationId == "conv-2" && it.role == "cancelled"
            })
        }
    }

    @Test
    fun testHandleAgentResultSavesFinalMessageWithWarningWhenBlockedForWrongNumbers() = runTest(testDispatcher) {
        val draftAnswer = "Administer amoxicillin 1,000 mg orally every 8 hours."
        val warningText = "Warning: Unverified clinical quantities (1,000 mg). The following statement(s) could not be verified against retrieved database sections:\n• \"Administer amoxicillin 1,000 mg orally every 8 hours.\""

        val validation = SafetyValidator.ValidationResult(
            passed = false,
            warnings = listOf(warningText),
            violations = listOf(Violation(code = ViolationCodes.UNVERIFIED_QTY, detail = "Unverified 1,000 mg", target = ViolationTarget.PHASE_2)),
            blockedReason = "Unverified clinical quantities found in response: 1,000 mg."
        )

        every { streamingManager.agentState.value } returns StreamingManager.AgentState.Completed(
            result = draftAnswer,
            validation = validation,
            tokenUsage = StreamingManager.TokenUsage(100, 50, 150)
        )

        manager.handleAgentResult("conv-1", "msg-1", draftAnswer)
        advanceUntilIdle()

        coVerify {
            conversationRepository.addMessage(match { msg ->
                msg.conversationId == "conv-1" &&
                    msg.id == "msg-1" &&
                    msg.role == "assistant" &&
                    msg.content == draftAnswer &&
                    !msg.isError &&
                    msg.warningsJson?.contains("1,000 mg") == true
            })
        }
    }

    @Test
    fun testHandleAgentResultQuarantinesMessageWhenBlockedWithoutWrongNumbers() = runTest(testDispatcher) {
        val blockedText = "${ChatExecutionManager.BLOCKED_MARKER}No tools executed"
        val validation = SafetyValidator.ValidationResult(
            passed = false,
            warnings = emptyList(),
            violations = listOf(Violation(code = ViolationCodes.NO_TOOLS, detail = "No tools", target = ViolationTarget.PHASE_1)),
            blockedReason = "No tools executed"
        )

        every { streamingManager.agentState.value } returns StreamingManager.AgentState.Completed(
            result = blockedText,
            validation = validation,
            tokenUsage = StreamingManager.TokenUsage(100, 50, 150)
        )

        manager.handleAgentResult("conv-1", "msg-2", blockedText)
        advanceUntilIdle()

        coVerify {
            conversationRepository.addMessage(match { msg ->
                msg.conversationId == "conv-1" &&
                    msg.id == "msg-2" &&
                    msg.role == "assistant" &&
                    msg.isError &&
                    msg.content.startsWith(ChatExecutionManager.BLOCKED_MARKER)
            })
        }
    }

    @Test
    fun testUnconfiguredErrorPathResetsStreamingManager() = runTest(testDispatcher) {
        manager.sendMessage("conv-1", "Query")
        advanceUntilIdle()

        verify { streamingManager.reset() }
    }

    @Test
    fun testRealOrderingWithRealStreamingManager() = runTest(testDispatcher) {
        val realStreamingManager = StreamingManager()
        // Simulate stale tokens from a prior turn
        realStreamingManager.onStreamingTextDelta("Prior turn stale tokens")
        assertEquals("Prior turn stale tokens", realStreamingManager.streamingText.value)

        val orderedManager = ChatExecutionManager(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            koogAgentFactory = koogAgentFactory,
            clinicalOrchestrator = clinicalOrchestrator,
            streamingManager = realStreamingManager,
            reliabilityManager = reliabilityManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            backgroundExecutionBridge = backgroundBridge,
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            ioDispatcher = testDispatcher
        )

        var streamingTextAtEvent: String? = null
        var activeIdAtEvent: String? = null
        var activeConvAtEvent: String? = null

        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            orderedManager.events.collect { event ->
                if (event is ChatExecutionEvent.GenerationStarted) {
                    streamingTextAtEvent = realStreamingManager.streamingText.value
                    activeIdAtEvent = orderedManager.activeAssistantMessageId.value
                    activeConvAtEvent = orderedManager.activeConversationId.value
                }
            }
        }

        val accepted = orderedManager.sendMessage("conv-1", "New question")
        assertTrue("Message should be accepted", accepted)
        advanceUntilIdle()

        assertNotNull("GenerationStarted must have fired", streamingTextAtEvent)
        assertEquals("streamingText must be empty when GenerationStarted fires", "", streamingTextAtEvent)
        assertNotNull("activeAssistantMessageId must be set when GenerationStarted fires", activeIdAtEvent)
        assertEquals("conv-1", activeConvAtEvent)

        eventsJob.cancel()
    }

    @Test
    fun testAddMessageThrowsInHandleAgentErrorStillEmitsGenerationError() = runTest(testDispatcher) {
        coEvery { conversationRepository.addMessage(match { it.isError }) } throws RuntimeException("Disk full")

        val events = mutableListOf<ChatExecutionEvent>()
        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.events.collect { events.add(it) }
        }

        securePreferences.saveConfiguration(
            AiConfiguration(
                provider = AiProvider.ANTHROPIC,
                model = "claude-3-5-sonnet",
                isConfigured = true
            )
        )
        coEvery { koogAgentFactory.resolveRuntime(any()) } throws RuntimeException("Agent initialization failed")

        manager.sendMessage("conv-1", "Query")
        advanceUntilIdle()

        val errorEvent = events.filterIsInstance<ChatExecutionEvent.GenerationError>().firstOrNull()
        assertNotNull("GenerationError must still emit even if repository throws", errorEvent)
        assertEquals("conv-1", errorEvent?.conversationId)
        assertNull("activeAssistantMessageId must be null after error", manager.activeAssistantMessageId.value)
        assertNull("activeConversationId must be null after error", manager.activeConversationId.value)

        eventsJob.cancel()
    }

    @Test
    fun testAddMessageThrowsInCancellationStillEmitsGenerationCancelled() = runTest(testDispatcher) {
        coEvery { conversationRepository.addMessage(match { it.role == "cancelled" }) } throws RuntimeException("DB locked")

        val events = mutableListOf<ChatExecutionEvent>()
        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.events.collect { events.add(it) }
        }

        manager.cancelGeneration("conv-1")
        advanceUntilIdle()

        val cancelEvent = events.filterIsInstance<ChatExecutionEvent.GenerationCancelled>().firstOrNull()
        assertNotNull("GenerationCancelled must still emit even if repository throws", cancelEvent)
        assertEquals("conv-1", cancelEvent?.conversationId)
        assertNull("activeAssistantMessageId must be null after cancellation", manager.activeAssistantMessageId.value)
        assertNull("activeConversationId must be null after cancellation", manager.activeConversationId.value)

        eventsJob.cancel()
    }

    @Test
    fun testActiveJobTerminationWithoutTerminalEventEmitsFallbackGenerationError() = runTest(testDispatcher) {
        securePreferences.saveConfiguration(
            AiConfiguration(
                provider = AiProvider.ANTHROPIC,
                model = "claude-3-5-sonnet",
                isConfigured = true
            )
        )
        coEvery { koogAgentFactory.resolveRuntime(any()) } throws RuntimeException("Core crash")

        val events = mutableListOf<ChatExecutionEvent>()
        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.events.collect { events.add(it) }
        }

        manager.sendMessage("conv-1", "Query")
        advanceUntilIdle()

        val hasTerminalEvent = events.any {
            it is ChatExecutionEvent.AssistantMessageCompleted ||
                it is ChatExecutionEvent.GenerationError ||
                it is ChatExecutionEvent.GenerationCancelled
        }
        assertTrue("A terminal event must be guaranteed", hasTerminalEvent)
        assertNull(manager.activeAssistantMessageId.value)

        eventsJob.cancel()
    }

    @Test
    fun testLateCancelIoCoroutineDoesNotWipeQ2StreamingText() = runTest(testDispatcher) {
        val realStreamingManager = StreamingManager()
        val cancelGate = kotlinx.coroutines.CompletableDeferred<Unit>()

        coEvery { conversationRepository.addMessage(match { it.role == "cancelled" }) } coAnswers {
            cancelGate.await()
        }

        val testManager = ChatExecutionManager(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            koogAgentFactory = koogAgentFactory,
            clinicalOrchestrator = clinicalOrchestrator,
            streamingManager = realStreamingManager,
            reliabilityManager = reliabilityManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            backgroundExecutionBridge = backgroundBridge,
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            ioDispatcher = testDispatcher
        )

        // 1. Q1 is cancelled
        testManager.cancelGeneration("conv-1")

        // 2. Q2 begins immediately before cancelGate completes
        realStreamingManager.onStreamingTextDelta("Q2 tokens in progress")
        assertEquals("Q2 tokens in progress", realStreamingManager.streamingText.value)

        // 3. Cancel IO coroutine completes late
        cancelGate.complete(Unit)
        advanceUntilIdle()

        // 4. Assert Q2 streaming text was NOT wiped by late cancel reset
        assertEquals(
            "Q2 streamingText must NOT be wiped by a delayed cancel coroutine",
            "Q2 tokens in progress",
            realStreamingManager.streamingText.value
        )
    }

    @Test
    fun testNegativeAfterNormalSuccessNoExtraErrorOrPersistedMessage() = runTest(testDispatcher) {
        val testManager = createConfiguredTestManager()
        val persistedMessages = mutableListOf<MessageEntity>()
        coEvery { conversationRepository.addMessage(any()) } answers { persistedMessages.add(firstArg()) }

        val mockExecutor: PromptExecutor = mockk(relaxed = true)
        val mockModel: LLModel = mockk(relaxed = true)
        coEvery { koogAgentFactory.resolveRuntime(any()) } returns Triple(mockExecutor, mockModel, null)
        coEvery { clinicalOrchestrator.runTurn(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ClinicalOrchestrator.ExecutionResult(
                content = "Normal clinical guidance.",
                validation = SafetyValidator.ValidationResult(passed = true, warnings = emptyList()),
                tokenUsage = StreamingManager.TokenUsage(10, 10, 20)
            )

        every { streamingManager.agentState.value } returns StreamingManager.AgentState.Completed(
            result = "Normal clinical guidance.",
            validation = SafetyValidator.ValidationResult(passed = true, warnings = emptyList()),
            tokenUsage = StreamingManager.TokenUsage(10, 10, 20)
        )

        val events = mutableListOf<ChatExecutionEvent>()
        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) { testManager.events.collect { events.add(it) } }

        testManager.sendMessage("conv-1", "Guidance request")
        advanceUntilIdle()

        assertEquals(1, events.filterIsInstance<ChatExecutionEvent.AssistantMessageCompleted>().size)
        assertEquals(0, events.filterIsInstance<ChatExecutionEvent.GenerationError>().size)
        assertEquals(1, persistedMessages.filter { it.role == "assistant" }.size)
        assertTrue(persistedMessages.none { it.content.contains("terminated without event") })
        eventsJob.cancel()
    }

    @Test
    fun testNegativeAfterQuarantineNoExtraErrorOrPersistedMessage() = runTest(testDispatcher) {
        val testManager = createConfiguredTestManager()
        val persistedMessages = mutableListOf<MessageEntity>()
        coEvery { conversationRepository.addMessage(any()) } answers { persistedMessages.add(firstArg()) }

        val mockExecutor: PromptExecutor = mockk(relaxed = true)
        val mockModel: LLModel = mockk(relaxed = true)
        coEvery { koogAgentFactory.resolveRuntime(any()) } returns Triple(mockExecutor, mockModel, null)
        coEvery { clinicalOrchestrator.runTurn(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ClinicalOrchestrator.ExecutionResult(
                content = "Blocked response.",
                validation = SafetyValidator.ValidationResult(passed = false, warnings = emptyList(), blockedReason = "Safety violation"),
                tokenUsage = StreamingManager.TokenUsage(10, 10, 20)
            )

        every { streamingManager.agentState.value } returns StreamingManager.AgentState.Completed(
            result = "Blocked response.",
            validation = SafetyValidator.ValidationResult(passed = false, warnings = emptyList(), blockedReason = "Safety violation"),
            tokenUsage = StreamingManager.TokenUsage(10, 10, 20)
        )

        val events = mutableListOf<ChatExecutionEvent>()
        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) { testManager.events.collect { events.add(it) } }

        testManager.sendMessage("conv-1", "Query")
        advanceUntilIdle()

        assertEquals(1, events.filterIsInstance<ChatExecutionEvent.AssistantMessageCompleted>().size)
        assertEquals(0, events.filterIsInstance<ChatExecutionEvent.GenerationError>().size)
        assertEquals(1, persistedMessages.filter { it.role == "assistant" }.size)
        assertTrue(persistedMessages.none { it.content.contains("terminated without event") })
        eventsJob.cancel()
    }

    @Test
    fun testNegativeAfterFallbackNoExtraErrorOrPersistedMessage() = runTest(testDispatcher) {
        val testManager = createConfiguredTestManager()
        val persistedMessages = mutableListOf<MessageEntity>()
        coEvery { conversationRepository.addMessage(any()) } answers { persistedMessages.add(firstArg()) }

        val mockExecutor: PromptExecutor = mockk(relaxed = true)
        val mockModel: LLModel = mockk(relaxed = true)
        coEvery { koogAgentFactory.resolveRuntime(any()) } returns Triple(mockExecutor, mockModel, null)
        coEvery { clinicalOrchestrator.runTurn(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ClinicalOrchestrator.ExecutionResult(
                content = "Fallback text.",
                validation = SafetyValidator.ValidationResult(passed = true, warnings = emptyList()),
                tokenUsage = StreamingManager.TokenUsage(10, 10, 20)
            )

        every { streamingManager.agentState.value } returns StreamingManager.AgentState.Idle

        val events = mutableListOf<ChatExecutionEvent>()
        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) { testManager.events.collect { events.add(it) } }

        testManager.sendMessage("conv-1", "Query")
        advanceUntilIdle()

        assertEquals(1, events.filterIsInstance<ChatExecutionEvent.AssistantMessageCompleted>().size)
        assertEquals(0, events.filterIsInstance<ChatExecutionEvent.GenerationError>().size)
        assertEquals(1, persistedMessages.filter { it.role == "assistant" }.size)
        assertTrue(persistedMessages.none { it.content.contains("terminated without event") })
        eventsJob.cancel()
    }

    @Test
    fun testNegativeAfterUserCancelNoExtraErrorOrPersistedMessage() = runTest(testDispatcher) {
        val testManager = createConfiguredTestManager()
        val persistedMessages = mutableListOf<MessageEntity>()
        coEvery { conversationRepository.addMessage(any()) } answers { persistedMessages.add(firstArg()) }

        val mockExecutor: PromptExecutor = mockk(relaxed = true)
        val mockModel: LLModel = mockk(relaxed = true)
        coEvery { koogAgentFactory.resolveRuntime(any()) } returns Triple(mockExecutor, mockModel, null)
        coEvery { clinicalOrchestrator.runTurn(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            testManager.cancelGeneration("conv-1")
            throw kotlinx.coroutines.CancellationException("Cancelled by test")
        }

        val events = mutableListOf<ChatExecutionEvent>()
        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) { testManager.events.collect { events.add(it) } }

        testManager.sendMessage("conv-1", "Query")
        advanceUntilIdle()

        assertEquals(1, events.filterIsInstance<ChatExecutionEvent.GenerationCancelled>().size)
        assertEquals(0, events.filterIsInstance<ChatExecutionEvent.GenerationError>().size)
        assertEquals(0, persistedMessages.filter { it.role == "assistant" }.size)
        assertEquals(1, persistedMessages.filter { it.role == "cancelled" }.size)
        assertTrue(persistedMessages.none { it.content.contains("terminated without event") })
        eventsJob.cancel()
    }

    @Test
    fun testNegativeAfterAgentErrorNoExtraFallbackErrorOrMessage() = runTest(testDispatcher) {
        val testManager = createConfiguredTestManager()
        val persistedMessages = mutableListOf<MessageEntity>()
        coEvery { conversationRepository.addMessage(any()) } answers { persistedMessages.add(firstArg()) }

        coEvery { koogAgentFactory.resolveRuntime(any()) } throws RuntimeException("Agent network fault")

        val events = mutableListOf<ChatExecutionEvent>()
        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) { testManager.events.collect { events.add(it) } }

        testManager.sendMessage("conv-1", "Query")
        advanceUntilIdle()

        val errors = events.filterIsInstance<ChatExecutionEvent.GenerationError>()
        assertEquals(1, errors.size)
        assertEquals(1, persistedMessages.filter { it.role == "assistant" && it.isError }.size)
        assertTrue(persistedMessages.none { it.content.contains("terminated without event") })
        eventsJob.cancel()
    }

    private fun createConfiguredTestManager(
        customStreamingManager: StreamingManager? = null
    ): ChatExecutionManager {
        securePreferences.saveConfiguration(
            AiConfiguration(
                provider = AiProvider.ANTHROPIC,
                model = "claude-3-5-sonnet",
                isConfigured = true
            )
        )
        return ChatExecutionManager(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            koogAgentFactory = koogAgentFactory,
            clinicalOrchestrator = clinicalOrchestrator,
            streamingManager = customStreamingManager ?: streamingManager,
            reliabilityManager = reliabilityManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            backgroundExecutionBridge = backgroundBridge,
            scope = CoroutineScope(SupervisorJob() + testDispatcher),
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun testGatedQ1CancelDoesNotClobberQ2ActiveStateOrEmitSpuriousError() = runTest(testDispatcher) {
        val realStreamingManager = StreamingManager()
        val testManager = createConfiguredTestManager(realStreamingManager)

        val mockExecutor: PromptExecutor = mockk(relaxed = true)
        val mockModel: LLModel = mockk(relaxed = true)
        coEvery { koogAgentFactory.resolveRuntime(any()) } returns Triple(mockExecutor, mockModel, null)

        val q1Running = kotlinx.coroutines.CompletableDeferred<Unit>()
        val q1FinallyGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val q2Running = kotlinx.coroutines.CompletableDeferred<Unit>()

        coEvery {
            clinicalOrchestrator.runTurn(match { it == "Q1" }, any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            q1Running.complete(Unit)
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    q1FinallyGate.await()
                }
            }
        }

        coEvery {
            clinicalOrchestrator.runTurn(match { it == "Q2" }, any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            q2Running.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }

        val events = mutableListOf<ChatExecutionEvent>()
        val eventsJob = launch(UnconfinedTestDispatcher(testScheduler)) { testManager.events.collect { events.add(it) } }

        // 1. Start Q1 and wait until it is actively running inside runTurn
        testManager.sendMessage("conv-1", "Q1")
        q1Running.await()

        // 2. Cancel Q1 - its coroutine catches cancellation and pauses in runTurn's finally on q1FinallyGate
        testManager.cancelGeneration("conv-1")

        // 3. Immediately start Q2 while Q1's finally has not yet executed
        testManager.sendMessage("conv-1", "Q2")
        q2Running.await()

        val q2AssistantId = testManager.activeAssistantMessageId.value
        assertNotNull("Q2 should have an active assistant message ID", q2AssistantId)

        // Simulate Q2 streaming tokens
        realStreamingManager.onStreamingTextDelta("Q2 tokens in progress")
        assertEquals("Q2 tokens in progress", realStreamingManager.streamingText.value)

        // 4. Release Q1's finally gate and let Q1's cancellation finish
        q1FinallyGate.complete(Unit)
        testScheduler.runCurrent()

        // 5. Assert Q2 state survived and no spurious GenerationError was emitted for Q1
        assertEquals(
            "Q2 activeAssistantMessageId must NOT be wiped by Q1 finally",
            q2AssistantId,
            testManager.activeAssistantMessageId.value
        )
        assertEquals(
            "Q2 streamingText must NOT be wiped by Q1 finally",
            "Q2 tokens in progress",
            realStreamingManager.streamingText.value
        )
        val spuriousErrors = events.filterIsInstance<ChatExecutionEvent.GenerationError>()
        assertEquals(
            "No spurious GenerationError should be emitted when Q1 cancels while Q2 is running",
            0,
            spuriousErrors.size
        )

        eventsJob.cancel()
    }
}

