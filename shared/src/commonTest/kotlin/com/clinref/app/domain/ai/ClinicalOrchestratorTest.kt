package com.clinref.app.domain.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.SearchResultTopicItem
import com.clinref.app.data.SearchTopicsResponse
import com.clinref.app.data.TopicSectionsResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClinicalOrchestratorTest {

    private val medicalDatabaseTools: MedicalDatabaseTools = mockk(relaxed = true)
    private val safetyValidator = SafetyValidator()
    private val streamingManager = StreamingManager()
    private val model = LLModel(provider = LLMProvider.OpenAI, id = "test-model")

    private class FakePromptExecutor : PromptExecutor() {
        var executeHandler: suspend (Prompt, LLModel, List<ToolDescriptor>) -> Message.Assistant = { _, _, _ ->
            Message.Assistant(content = "", metaInfo = ResponseMetaInfo(KoogClock.System.now()))
        }
        var streamingHandler: (Prompt, LLModel, List<ToolDescriptor>) -> Flow<StreamFrame> = { _, _, _ ->
            emptyFlow()
        }
        val executedPrompts = mutableListOf<Prompt>()
        val streamingPrompts = mutableListOf<Prompt>()

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant {
            executedPrompts.add(prompt)
            return executeHandler(prompt, model, tools)
        }

        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            streamingPrompts.add(prompt)
            return streamingHandler(prompt, model, tools)
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult {
            return ModerationResult(isHarmful = false, categories = emptyMap())
        }

        override fun close() {}
    }

    private lateinit var executor: FakePromptExecutor
    private lateinit var orchestrator: ClinicalOrchestrator

    @Before
    fun setUp() {
        streamingManager.reset()
        executor = FakePromptExecutor()
        every { medicalDatabaseTools.asToolList() } returns emptyList()
        orchestrator = ClinicalOrchestrator(
            medicalDatabaseTools = medicalDatabaseTools,
            safetyValidator = safetyValidator
        )
    }

    @Test
    fun testSmallTalkReturnsDeterministicCannedReplyWithoutLlm() = runTest {
        val result = orchestrator.runTurn(
            question = "hello doctor",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        assertEquals(QuestionRouter.CANNED_FALLBACK_REPLY, result.content)
        assertTrue(result.validation.passed)
        assertEquals(0, result.tokenUsage.totalTokens)

        // Verifies zero LLM calls were made
        assertEquals(0, executor.executedPrompts.size)
        assertEquals(0, executor.streamingPrompts.size)

        val finalState = streamingManager.agentState.value
        assertTrue(finalState is StreamingManager.AgentState.Completed)
        assertEquals(QuestionRouter.CANNED_FALLBACK_REPLY, (finalState as StreamingManager.AgentState.Completed).result)
    }

    @Test
    fun testRetrievalAndSynthesisFlow() = runTest {
        val topicId = "topic-101"
        val sectionId = "sec-dosing"
        val topicTitle = "Amoxicillin: Drug Information"
        val sectionTitle = "Dosing & Administration"
        val sectionText = "Usual oral dosage is 500 mg every 8 hours."

        coEvery { medicalDatabaseTools.getTopicSectionsText(topicId, listOf(sectionId)) } returns TopicSectionsResponse(
            topicId = topicId,
            topicTitle = topicTitle,
            sectionTitles = mapOf(sectionId to sectionTitle),
            markdown = sectionText
        )
        every { medicalDatabaseTools.getTopicTitle(topicId) } returns topicTitle
        every { medicalDatabaseTools.getSectionTitle(topicId, sectionId) } returns sectionTitle

        val toolCall = MessagePart.Tool.Call(
            id = "call_1",
            tool = "getTopicSectionsText",
            args = """{"topicId":"$topicId","sectionIds":["$sectionId"]}"""
        )

        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 50,
            outputTokensCount = 20
        )

        var executeCount = 0
        executor.executeHandler = { _, _, _ ->
            executeCount++
            if (executeCount == 1) {
                // Round 1: Model requests section text
                Message.Assistant(parts = listOf(toolCall), metaInfo = meta)
            } else {
                // Round 2: Model finishes retrieval
                Message.Assistant(parts = emptyList(), metaInfo = meta)
            }
        }

        val synthesisFrames = listOf(
            StreamFrame.TextDelta("Amoxicillin is prescribed at 500 mg every 8 hours."),
            StreamFrame.End(finishReason = "stop", metaInfo = meta)
        )
        executor.streamingHandler = { _, _, _ ->
            flow { synthesisFrames.forEach { emit(it) } }
        }

        val result = orchestrator.runTurn(
            question = "What is the amoxicillin dose?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        assertEquals("Amoxicillin is prescribed at 500 mg every 8 hours.", result.content)
        assertTrue(result.validation.passed)
        assertEquals(2, executor.executedPrompts.size)
        assertEquals(1, executor.streamingPrompts.size)
    }

    @Test
    fun testZeroEvidenceRetriesOnceAndAbstainsIfStillEmpty() = runTest {
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 40,
            outputTokensCount = 10
        )

        executor.executeHandler = { _, _, _ ->
            Message.Assistant(parts = emptyList(), metaInfo = meta)
        }

        val result = orchestrator.runTurn(
            question = "Tell me about condition xyz999",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        assertEquals(SystemPrompt.CANONICAL_ABSTENTION, result.content)
        assertTrue(result.validation.passed)
        // Initial retrieval + 1 retry = 2 execute calls
        assertEquals(2, executor.executedPrompts.size)
        // Synthesis streaming never invoked because evidence is empty
        assertEquals(0, executor.streamingPrompts.size)
    }

    @Test
    fun testSafetyValidatorRevisionPassOnUnverifiedQuantities() = runTest {
        val topicId = "topic-202"
        val sectionId = "sec-dose"
        val topicTitle = "Drug Dosing Guide"
        val sectionTitle = "Adult Dosing"
        val verifiedEvidence = "Recommended adult maintenance dose is 250 mg once daily."

        coEvery { medicalDatabaseTools.getTopicSectionsText(topicId, listOf(sectionId)) } returns TopicSectionsResponse(
            topicId = topicId,
            topicTitle = topicTitle,
            sectionTitles = mapOf(sectionId to sectionTitle),
            markdown = verifiedEvidence
        )
        every { medicalDatabaseTools.getTopicTitle(topicId) } returns topicTitle
        every { medicalDatabaseTools.getSectionTitle(topicId, sectionId) } returns sectionTitle

        val toolCall = MessagePart.Tool.Call(
            id = "c1",
            tool = "getTopicSectionsText",
            args = """{"topicId":"$topicId","sectionIds":["$sectionId"]}"""
        )
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 30,
            outputTokensCount = 15
        )

        var execCount = 0
        executor.executeHandler = { _, _, _ ->
            execCount++
            if (execCount == 1) {
                Message.Assistant(parts = listOf(toolCall), metaInfo = meta)
            } else {
                Message.Assistant(parts = emptyList(), metaInfo = meta)
            }
        }

        var streamCount = 0
        executor.streamingHandler = { _, _, _ ->
            streamCount++
            if (streamCount == 1) {
                // First synthesis attempt: Hallucinates 1200 mg (not in verified evidence)
                val hallucinatedFrames = listOf(
                    StreamFrame.TextDelta("Take 1200 mg once daily."),
                    StreamFrame.End(finishReason = "stop", metaInfo = meta)
                )
                flow { hallucinatedFrames.forEach { emit(it) } }
            } else {
                // Second synthesis attempt (Revision pass): Uses verified 250 mg
                val revisedFrames = listOf(
                    StreamFrame.TextDelta("Take 250 mg once daily."),
                    StreamFrame.End(finishReason = "stop", metaInfo = meta)
                )
                flow { revisedFrames.forEach { emit(it) } }
            }
        }

        val result = orchestrator.runTurn(
            question = "What is the adult maintenance dose?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        // Draft was successfully revised to verified figure
        assertEquals("Take 250 mg once daily.", result.content)
        assertTrue(result.validation.passed)
        // 2 streaming synthesis calls (initial + revision pass)
        assertEquals(2, executor.streamingPrompts.size)
    }

    @Test
    fun testRetrievalTerminatesAtMaxRounds() = runTest {
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 20,
            outputTokensCount = 10
        )

        coEvery { medicalDatabaseTools.searchTopics(any()) } returns SearchTopicsResponse(
            query = "loop",
            results = listOf(SearchResultTopicItem(id = "t1", title = "Loop Topic"))
        )

        // Tool call returned on every turn
        val continuousToolCallMsg = Message.Assistant(
            parts = listOf(
                MessagePart.Tool.Call(id = "call_loop", tool = "searchTopics", args = """{"query":"loop"}""")
            ),
            metaInfo = meta
        )

        executor.executeHandler = { _, _, _ -> continuousToolCallMsg }

        val result = orchestrator.runTurn(
            question = "Endless retrieval query",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        // Loop terminated at MAX_RETRIEVAL_ROUNDS (10) + 1 retry = 11 execute calls
        assertEquals(ClinicalOrchestrator.MAX_RETRIEVAL_ROUNDS + 1, executor.executedPrompts.size)
        assertEquals(SystemPrompt.CANONICAL_ABSTENTION, result.content)
    }
}
