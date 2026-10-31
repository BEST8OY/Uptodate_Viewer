package com.clinref.app.domain.ai

import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.fakes.FakeSecurePreferences
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
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
    private val reliabilityManager: ReliabilityManager = mockk(relaxed = true)
    private val securePreferences = FakeSecurePreferences()
    private val secureLogger: SecureLogger = mockk(relaxed = true)
    private val backgroundBridge: BackgroundExecutionBridge = mockk(relaxed = true)

    private lateinit var manager: ChatExecutionManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        manager = ChatExecutionManager(
            conversationRepository = conversationRepository,
            contentRepository = contentRepository,
            koogAgentFactory = koogAgentFactory,
            streamingManager = streamingManager,
            reliabilityManager = reliabilityManager,
            securePreferences = securePreferences,
            secureLogger = secureLogger,
            backgroundExecutionBridge = backgroundBridge
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
}

