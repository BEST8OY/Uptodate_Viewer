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
import ai.koog.prompt.streaming.IncompleteStreamException
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.utils.time.KoogClock
import com.clinref.app.data.GraphicContentResponse
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.OutlineGraphicItem
import com.clinref.app.data.OutlineSectionItem
import com.clinref.app.data.RelatedTopicItem
import com.clinref.app.data.SearchResultTopicItem
import com.clinref.app.data.SearchTopicsResponse
import com.clinref.app.data.TopicOverviewResponse
import com.clinref.app.data.TopicSectionsResponse
import com.clinref.app.data.local.entity.MessageEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClinicalOrchestratorTest {

    private val json = AiJsonUtils.json
    private val medicalDatabaseTools: MedicalDatabaseTools = mockk(relaxed = true)
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
            medicalDatabaseTools = medicalDatabaseTools
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
        assertTrue(result.topicRefs.isEmpty())
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
        assertEquals(1, result.topicRefs.size)
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
        assertTrue(result.topicRefs.isEmpty())
        // Initial retrieval + 1 retry = 2 execute calls
        assertEquals(2, executor.executedPrompts.size)
        // Synthesis streaming never invoked because evidence is empty
        assertEquals(0, executor.streamingPrompts.size)
    }

    @Test
    fun testStage2SynthesisStreamsDirectlyWithSourceAttribution() = runTest {
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

        val synthesisFrames = listOf(
            StreamFrame.TextDelta("Take 250 mg once daily."),
            StreamFrame.End(finishReason = "stop", metaInfo = meta)
        )
        executor.streamingHandler = { _, _, _ ->
            flow { synthesisFrames.forEach { emit(it) } }
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

        assertEquals("Take 250 mg once daily.", result.content)
        assertEquals(1, result.topicRefs.size)
        assertEquals(topicId, result.topicRefs[0].topicId)
        // 1 streaming synthesis call directly (no Gate 2 revision overhead)
        assertEquals(1, executor.streamingPrompts.size)
    }

    @Test
    fun testIncompleteStreamExceptionTriggersNonStreamingFallback() = runTest {
        val topicId = "topic-fallback"
        val sectionId = "sec-fallback"
        val topicTitle = "Guideline Topic"
        val sectionTitle = "Recommended Regimen"
        val verifiedEvidence = "Recommended first-line therapy is Doxycycline 100 mg twice daily for 7 days."

        coEvery { medicalDatabaseTools.getTopicSectionsText(topicId, listOf(sectionId)) } returns TopicSectionsResponse(
            topicId = topicId,
            topicTitle = topicTitle,
            sectionTitles = mapOf(sectionId to sectionTitle),
            markdown = verifiedEvidence
        )
        every { medicalDatabaseTools.getTopicTitle(topicId) } returns topicTitle
        every { medicalDatabaseTools.getSectionTitle(topicId, sectionId) } returns sectionTitle

        val toolCall = MessagePart.Tool.Call(
            id = "c_retrieval",
            tool = "getTopicSectionsText",
            args = """{"topicId":"$topicId","sectionIds":["$sectionId"]}"""
        )
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 40,
            outputTokensCount = 20
        )

        var execCount = 0
        executor.executeHandler = { _, _, _ ->
            execCount++
            if (execCount == 1) {
                // Stage 1 retrieval tool call
                Message.Assistant(parts = listOf(toolCall), metaInfo = meta)
            } else if (execCount == 2) {
                // Stage 1 retrieval finish
                Message.Assistant(parts = emptyList(), metaInfo = meta)
            } else {
                // Non-streaming fallback execution for Stage 2
                Message.Assistant(
                    content = "Doxycycline 100 mg twice daily for 7 days is recommended.",
                    metaInfo = meta
                )
            }
        }

        // Simulate streaming dropping without an End frame (IncompleteStreamException)
        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Partial unfinalized text..."))
                throw IncompleteStreamException("Stream completed without receiving an End frame")
            }
        }

        val result = orchestrator.runTurn(
            question = "What is the recommended therapy?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        assertEquals("Doxycycline 100 mg twice daily for 7 days is recommended.", result.content)
        assertEquals(3, executor.executedPrompts.size) // Round 1 retrieval, Round 2 retrieval finish, Non-streaming synthesis fallback
        assertEquals(1, executor.streamingPrompts.size) // 1 failed streaming attempt
    }

    @Test
    fun testCancellationExceptionDuringFallbackIsNotSwallowed() = runTest {
        val topicId = "topic-cancel"
        val sectionId = "sec-cancel"
        val topicTitle = "Guideline Topic"
        val sectionTitle = "Recommended Regimen"

        coEvery { medicalDatabaseTools.getTopicSectionsText(topicId, listOf(sectionId)) } returns TopicSectionsResponse(
            topicId = topicId,
            topicTitle = topicTitle,
            sectionTitles = mapOf(sectionId to sectionTitle),
            markdown = "Clinical evidence text"
        )
        every { medicalDatabaseTools.getTopicTitle(topicId) } returns topicTitle
        every { medicalDatabaseTools.getSectionTitle(topicId, sectionId) } returns sectionTitle

        val toolCall = MessagePart.Tool.Call(
            id = "c_retrieval",
            tool = "getTopicSectionsText",
            args = """{"topicId":"$topicId","sectionIds":["$sectionId"]}"""
        )
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 40,
            outputTokensCount = 20
        )

        var execCount = 0
        executor.executeHandler = { _, _, _ ->
            execCount++
            if (execCount == 1) {
                Message.Assistant(parts = listOf(toolCall), metaInfo = meta)
            } else if (execCount == 2) {
                Message.Assistant(parts = emptyList(), metaInfo = meta)
            } else {
                throw kotlinx.coroutines.CancellationException("User cancelled synthesis turn")
            }
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Partial text before drop"))
                throw IncompleteStreamException("Stream completed without receiving an End frame")
            }
        }

        try {
            orchestrator.runTurn(
                question = "What is the recommended therapy?",
                patientProfile = PatientProfile(),
                chatHistory = emptyList(),
                executor = executor,
                model = model,
                providerParams = null,
                streamingManager = streamingManager
            )
            org.junit.Assert.fail("Expected CancellationException to be rethrown, but it was swallowed")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("User cancelled synthesis turn", e.message)
        }
    }

    @Test
    fun testStreamEmittingOnlyThinkingTokensTriggersFallbackWhenDropped() = runTest {
        val topicId = "topic-think"
        val sectionId = "sec-think"
        val topicTitle = "Guideline Topic"
        val sectionTitle = "Recommended Regimen"
        val verifiedEvidence = "Azithromycin 500 mg daily for 3 days."

        coEvery { medicalDatabaseTools.getTopicSectionsText(topicId, listOf(sectionId)) } returns TopicSectionsResponse(
            topicId = topicId,
            topicTitle = topicTitle,
            sectionTitles = mapOf(sectionId to sectionTitle),
            markdown = verifiedEvidence
        )
        every { medicalDatabaseTools.getTopicTitle(topicId) } returns topicTitle
        every { medicalDatabaseTools.getSectionTitle(topicId, sectionId) } returns sectionTitle

        val toolCall = MessagePart.Tool.Call(
            id = "c_retrieval",
            tool = "getTopicSectionsText",
            args = """{"topicId":"$topicId","sectionIds":["$sectionId"]}"""
        )
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 40,
            outputTokensCount = 20
        )

        var execCount = 0
        executor.executeHandler = { _, _, _ ->
            execCount++
            if (execCount == 1) {
                Message.Assistant(parts = listOf(toolCall), metaInfo = meta)
            } else if (execCount == 2) {
                Message.Assistant(parts = emptyList(), metaInfo = meta)
            } else {
                Message.Assistant(content = "Azithromycin 500 mg daily for 3 days.", metaInfo = meta)
            }
        }

        // Emits only reasoning tags without End frame
        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("<think>Analyzing dosage parameters...</think>"))
                throw IncompleteStreamException("Stream completed without receiving an End frame")
            }
        }

        val result = orchestrator.runTurn(
            question = "What is the dosage?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        assertEquals("Azithromycin 500 mg daily for 3 days.", result.content)
        assertEquals(3, executor.executedPrompts.size)
    }

    @Test
    fun testStage2BlankDraftFallsBackToAbstention() = runTest {
        val topicId = "topic-blank"
        val sectionId = "sec-b"
        val topicTitle = "Blank Topic"
        val sectionTitle = "Blank Section"

        coEvery { medicalDatabaseTools.getTopicSectionsText(topicId, listOf(sectionId)) } returns TopicSectionsResponse(
            topicId = topicId,
            topicTitle = topicTitle,
            sectionTitles = mapOf(sectionId to sectionTitle),
            markdown = "Some clinical text"
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
            inputTokensCount = 20,
            outputTokensCount = 10
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

        // Simulates thinking budget exhaustion where text accumulator remains blank
        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("<thought>Thinking process...</thought>"))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "Any dose?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        assertEquals(SystemPrompt.CANONICAL_ABSTENTION, result.content)
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

        // Loop terminated at MAX_RETRIEVAL_ROUNDS (10)
        assertEquals(ClinicalOrchestrator.MAX_RETRIEVAL_ROUNDS, executor.executedPrompts.size)
        assertEquals(SystemPrompt.CANONICAL_ABSTENTION, result.content)
    }

    @Test
    fun testSmallTalkMakesZeroDecisionCalls() = runTest {
        var decisionCalls = 0
        val decisionClient = DecisionClient(
            transport = DecisionTransport {
                decisionCalls++
                "{}"
            }
        )

        val result = orchestrator.runTurn(
            question = "Thanks so much!",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals(QuestionRouter.CANNED_FALLBACK_REPLY, result.content)
        assertEquals(0, decisionCalls)
        assertEquals(0, executor.executedPrompts.size)
    }

    @Test
    fun testDecisionsRerankAndAutoPrefetchLoadsSectionsAndTablesIntoSynthesis() = runTest {
        val json = AiJsonUtils.json
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 40,
            outputTokensCount = 15
        )

        coEvery { medicalDatabaseTools.searchTopics("sepsis dosing") } returns SearchTopicsResponse(
            query = "sepsis dosing",
            results = listOf(
                SearchResultTopicItem(id = "Topic-Noise", title = "Differential diagnosis of fever"),
                SearchResultTopicItem(id = "Topic-Sepsis", title = "Evaluation and management of suspected sepsis")
            )
        )
        coEvery { medicalDatabaseTools.getTopicOverview("Topic-Sepsis") } returns TopicOverviewResponse(
            topicId = "Topic-Sepsis",
            title = "Evaluation and management of suspected sepsis",
            topicType = "medical",
            sections = listOf(
                OutlineSectionItem(id = "S1", title = "Empiric antimicrobial therapy", level = 1),
                OutlineSectionItem(id = "S2", title = "Pathogenesis", level = 1)
            ),
            graphics = listOf(
                OutlineGraphicItem(id = "G_TBL1", title = "Empiric IV Antimicrobial Dosing", isTable = true)
            )
        )
        coEvery { medicalDatabaseTools.getTopicSectionsText("Topic-Sepsis", listOf("S1")) } returns TopicSectionsResponse(
            topicId = "Topic-Sepsis",
            topicTitle = "Evaluation and management of suspected sepsis",
            sectionTitles = mapOf("S1" to "Empiric antimicrobial therapy"),
            markdown = "=== Section: S1 ===\nGive broad-spectrum empiric therapy within 1 hour."
        )
        coEvery { medicalDatabaseTools.getGraphicContent("G_TBL1") } returns GraphicContentResponse(
            graphicId = "G_TBL1",
            title = "Empiric IV Antimicrobial Dosing",
            markdown = "| Agent | Dose |\n|---|---|\n| Vancomycin | 15 mg/kg IV |"
        )
        every { medicalDatabaseTools.getTopicTitle("Topic-Sepsis") } returns "Evaluation and management of suspected sepsis"
        every { medicalDatabaseTools.getSectionTitle("Topic-Sepsis", "S1") } returns "Empiric antimicrobial therapy"

        val decisionClient = DecisionClient(
            transport = DecisionTransport { reqJson ->
                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                val answers = buildJsonObject {
                    qs.keys.forEach { k ->
                        when (k) {
                            "requires_drug_monograph", "requires_table_data", "needs_drug_monograph" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.85); put("confidence", 0.9) })
                            else -> {
                                val score = if (k.contains("Topic-Sepsis") || k.contains("S1") || k.contains("G_TBL1")) 2.8 else 0.4
                                put(
                                    k,
                                    buildJsonObject {
                                        put("type", "score")
                                        put("score", score)
                                        put("legend", buildJsonObject { put("0", "0"); put("1", "1"); put("2", "2"); put("3", "3") })
                                        put("confidence", 0.9)
                                    }
                                )
                            }
                        }
                    }
                }
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("answers", answers)
                        put("usage", buildJsonObject { put("total_input_tokens", 200); put("total_tokens", 200) })
                    }
                )
            }
        )

        var step = 0
        executor.executeHandler = { _, _, _ ->
            step++
            when (step) {
                1 -> Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "searchTopics", args = """{"query":"sepsis dosing"}""")),
                    metaInfo = meta
                )
                2 -> Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "c2", tool = "getTopicOverview", args = """{"topicId":"Topic-Sepsis"}""")),
                    metaInfo = meta
                )
                else -> Message.Assistant(parts = listOf(MessagePart.Text("Retrieval complete.")), metaInfo = meta)
            }
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Give vancomycin 15 mg/kg IV within 1 hour."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "Empiric vancomycin dose in sepsis?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Give vancomycin 15 mg/kg IV within 1 hour.", result.content)
        assertEquals(1, result.topicRefs.size)

        // Stage-1 LLM saw decisionRerank in searchTopics and autoPrefetched in getTopicOverview
        val round2PromptText = executor.executedPrompts[1].messages.joinToString("\n") { msg ->
            msg.parts.joinToString("\n") { it.toString() }
        }
        assertTrue(round2PromptText.contains("decisionRerank"))
        assertTrue(round2PromptText.contains("Topic-Sepsis"))

        val round3PromptText = executor.executedPrompts[2].messages.joinToString("\n") { msg ->
            msg.parts.joinToString("\n") { it.toString() }
        }
        assertTrue(round3PromptText.contains("autoPrefetched"))

        // Stage-2 synthesis prompt received both prefetched section prose AND table markdown
        val synthesisUserMsg = executor.streamingPrompts.first().messages.last().parts.joinToString("\n") { it.toString() }
        assertTrue(synthesisUserMsg.contains("within 1 hour"))
        assertTrue(synthesisUserMsg.contains("| Vancomycin | 15 mg/kg IV |"))

        // Decision token usage (2 decision calls x 200 input tokens = 400) decoupled into toolTokens
        // Stage 1: 3 * 40 = 120, Stage 2: 40 -> promptTokens = 160, toolTokens = 400
        assertEquals(160, result.tokenUsage.promptTokens)
        assertEquals(400, result.tokenUsage.toolTokens)
    }

    @Test
    fun testDecisionsRerankAndAutoPrefetchShortCircuitsRound3WhenNoDrugMonographNeeded() = runTest {
        val json = AiJsonUtils.json
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 40,
            outputTokensCount = 15
        )

        coEvery { medicalDatabaseTools.searchTopics("atrial fibrillation management") } returns SearchTopicsResponse(
            query = "atrial fibrillation management",
            results = listOf(
                SearchResultTopicItem(id = "Topic-AFib", title = "Atrial fibrillation: Overview of management", type = "medical")
            )
        )
        coEvery { medicalDatabaseTools.getTopicOverview("Topic-AFib") } returns TopicOverviewResponse(
            topicId = "Topic-AFib",
            title = "Atrial fibrillation: Overview of management",
            topicType = "medical",
            sections = listOf(
                OutlineSectionItem(id = "S1", title = "Rate control versus rhythm control", level = 1),
                OutlineSectionItem(id = "S2", title = "Anticoagulation", level = 1)
            )
        )
        coEvery { medicalDatabaseTools.getTopicSectionsText("Topic-AFib", listOf("S1")) } returns TopicSectionsResponse(
            topicId = "Topic-AFib",
            topicTitle = "Atrial fibrillation: Overview of management",
            sectionTitles = mapOf("S1" to "Rate control versus rhythm control"),
            markdown = "=== Section: S1 ===\nRate control with beta blockers or diltiazem is preferred for initial therapy."
        )
        every { medicalDatabaseTools.getTopicTitle("Topic-AFib") } returns "Atrial fibrillation: Overview of management"
        every { medicalDatabaseTools.getSectionTitle("Topic-AFib", "S1") } returns "Rate control versus rhythm control"

        val decisionClient = DecisionClient(
            transport = DecisionTransport { reqJson ->
                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                val answers = buildJsonObject {
                    qs.keys.forEach { k ->
                        when (k) {
                            "requires_drug_monograph", "needs_drug_monograph" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.10); put("confidence", 0.95) })
                            "requires_table_data" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.20); put("confidence", 0.90) })
                            else -> {
                                val score = if (k.contains("Topic-AFib") || k.contains("S1")) 2.9 else 0.3
                                put(
                                    k,
                                    buildJsonObject {
                                        put("type", "score")
                                        put("score", score)
                                        put("legend", buildJsonObject { put("0", "0"); put("1", "1"); put("2", "2"); put("3", "3") })
                                        put("confidence", 0.9)
                                    }
                                )
                            }
                        }
                    }
                }
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("answers", answers)
                        put("usage", buildJsonObject { put("total_input_tokens", 150); put("total_tokens", 150) })
                    }
                )
            }
        )

        var step = 0
        executor.executeHandler = { _, _, _ ->
            step++
            when (step) {
                1 -> Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "searchTopics", args = """{"query":"atrial fibrillation management"}""")),
                    metaInfo = meta
                )
                else -> error("Round 2 and 3 should have been short-circuited directly to Stage 2 by Round 1 high-confidence chaining!")
            }
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Rate control with beta blockers is preferred."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "Initial rate control in AFib?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Rate control with beta blockers is preferred.", result.content)
        // High-confidence Round 1 chaining collapsed the retrieval loop to just 1 Stage 1 round!
        assertEquals(1, executor.executedPrompts.size)
        assertEquals(1, executor.streamingPrompts.size)
        val synthesisMsg = executor.streamingPrompts.first().messages.last().parts.joinToString("\n") { it.toString() }
        assertTrue(synthesisMsg.contains("Rate control with beta blockers or diltiazem"))
    }

    @Test
    fun testDecisionsRerankAndAutoPrefetchShortCircuitsRound3WhenRound1Ambiguous() = runTest {
        val json = AiJsonUtils.json
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 40,
            outputTokensCount = 15
        )

        // Multiple close candidates prevent Round 1 auto-chaining
        coEvery { medicalDatabaseTools.searchTopics("atrial fibrillation management") } returns SearchTopicsResponse(
            query = "atrial fibrillation management",
            results = listOf(
                SearchResultTopicItem(id = "Topic-AFib", title = "Atrial fibrillation: Overview of management", type = "medical"),
                SearchResultTopicItem(id = "Topic-Flutter", title = "Atrial flutter: Overview of management", type = "medical")
            )
        )
        coEvery { medicalDatabaseTools.getTopicOverview("Topic-AFib") } returns TopicOverviewResponse(
            topicId = "Topic-AFib",
            title = "Atrial fibrillation: Overview of management",
            topicType = "medical",
            sections = listOf(
                OutlineSectionItem(id = "S1", title = "Rate control versus rhythm control", level = 1),
                OutlineSectionItem(id = "S2", title = "Anticoagulation", level = 1)
            )
        )
        coEvery { medicalDatabaseTools.getTopicSectionsText("Topic-AFib", listOf("S1")) } returns TopicSectionsResponse(
            topicId = "Topic-AFib",
            topicTitle = "Atrial fibrillation: Overview of management",
            sectionTitles = mapOf("S1" to "Rate control versus rhythm control"),
            markdown = "=== Section: S1 ===\nRate control with beta blockers or diltiazem is preferred for initial therapy."
        )
        every { medicalDatabaseTools.getTopicTitle("Topic-AFib") } returns "Atrial fibrillation: Overview of management"
        every { medicalDatabaseTools.getSectionTitle("Topic-AFib", "S1") } returns "Rate control versus rhythm control"

        val decisionClient = DecisionClient(
            transport = DecisionTransport { reqJson ->
                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                val answers = buildJsonObject {
                    qs.keys.forEach { k ->
                        when (k) {
                            "requires_drug_monograph", "needs_drug_monograph" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.10); put("confidence", 0.95) })
                            "requires_table_data" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.20); put("confidence", 0.90) })
                            else -> {
                                val score = when {
                                    k.contains("Topic-AFib") -> 2.2 // close to Flutter -> no Round 1 chain
                                    k.contains("Topic-Flutter") -> 2.1
                                    k.contains("S1") -> 2.9
                                    else -> 0.3
                                }
                                put(
                                    k,
                                    buildJsonObject {
                                        put("type", "score")
                                        put("score", score)
                                        put("legend", buildJsonObject { put("0", "0"); put("1", "1"); put("2", "2"); put("3", "3") })
                                        put("confidence", 0.9)
                                    }
                                )
                            }
                        }
                    }
                }
                json.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("answers", answers)
                        put("usage", buildJsonObject { put("total_input_tokens", 150); put("total_tokens", 150) })
                    }
                )
            }
        )

        var step = 0
        executor.executeHandler = { _, _, _ ->
            step++
            when (step) {
                1 -> Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "searchTopics", args = """{"query":"atrial fibrillation management"}""")),
                    metaInfo = meta
                )
                2 -> Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "c2", tool = "getTopicOverview", args = """{"topicId":"Topic-AFib"}""")),
                    metaInfo = meta
                )
                else -> error("Round 3 should have been short-circuited directly to Stage 2 by Round 2 auto-prefetch!")
            }
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Rate control with beta blockers is preferred."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "Initial rate control in AFib?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Rate control with beta blockers is preferred.", result.content)
        // Stage 1 executed 2 rounds (searchTopics and getTopicOverview), skipping Round 3!
        assertEquals(2, executor.executedPrompts.size)
        assertEquals(1, executor.streamingPrompts.size)
        val synthesisMsg = executor.streamingPrompts.first().messages.last().parts.joinToString("\n") { it.toString() }
        assertTrue(synthesisMsg.contains("Rate control with beta blockers or diltiazem"))
    }

    @Test
    fun testDecisionsFailOpenStillCompletesRetrieval() = runTest {
        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 30,
            outputTokensCount = 10
        )

        coEvery { medicalDatabaseTools.searchTopics("apixaban") } returns SearchTopicsResponse(
            query = "apixaban",
            results = listOf(SearchResultTopicItem(id = "Topic-Apix", title = "Apixaban"))
        )
        coEvery { medicalDatabaseTools.getTopicSectionsText("Topic-Apix", listOf("S1")) } returns TopicSectionsResponse(
            topicId = "Topic-Apix",
            topicTitle = "Apixaban",
            sectionTitles = mapOf("S1" to "Dosing"),
            markdown = "=== Section: S1 ===\nStandard dose is 5 mg twice daily."
        )
        every { medicalDatabaseTools.getTopicTitle("Topic-Apix") } returns "Apixaban"
        every { medicalDatabaseTools.getSectionTitle("Topic-Apix", "S1") } returns "Dosing"

        val failingDecisionClient = DecisionClient(
            transport = DecisionTransport { throw IllegalStateException("503 Service Unavailable") },
            failOpen = true
        )

        var step = 0
        executor.executeHandler = { _, _, _ ->
            step++
            when (step) {
                1 -> Message.Assistant(
                    parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "searchTopics", args = """{"query":"apixaban"}""")),
                    metaInfo = meta
                )
                2 -> Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call(
                            id = "c2",
                            tool = "getTopicSectionsText",
                            args = """{"topicId":"Topic-Apix","sectionIds":["S1"]}"""
                        )
                    ),
                    metaInfo = meta
                )
                else -> Message.Assistant(parts = listOf(MessagePart.Text("Done")), metaInfo = meta)
            }
        }
        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Standard dose is 5 mg twice daily."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "Standard apixaban dose?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = failingDecisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true)
        )

        assertEquals("Standard dose is 5 mg twice daily.", result.content)
        assertEquals(1, result.topicRefs.size)
        assertEquals(1, failingDecisionClient.failedCalls)
    }

    @Test
    fun testChatHistoryFiltersOutErrorsAndCancelledMessages() = runTest {
        val history = listOf(
            MessageEntity(id = "m1", conversationId = "c1", role = "user", content = "First question", timestamp = 1000L),
            MessageEntity(id = "m2", conversationId = "c1", role = "assistant", content = "First answer", timestamp = 2000L),
            MessageEntity(id = "m3_user", conversationId = "c1", role = "user", content = "Failed question", timestamp = 2500L),
            MessageEntity(id = "m3", conversationId = "c1", role = "assistant", content = "An error occurred during response generation", timestamp = 3000L, isError = true),
            MessageEntity(id = "m4_user", conversationId = "c1", role = "user", content = "Cancelled question", timestamp = 3500L),
            MessageEntity(id = "m4", conversationId = "c1", role = "cancelled", content = "Cancelled generation", timestamp = 4000L)
        )

        orchestrator.runTurn(
            question = "Next clinical query",
            patientProfile = PatientProfile(),
            chatHistory = history,
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        val firstPrompt = executor.executedPrompts.firstOrNull()
        assertNotNull("Stage 1 prompt must be executed", firstPrompt)

        val historyContents = firstPrompt!!.messages.map { msg ->
            msg.parts.joinToString("\n") { it.toString() }
        }
        assertTrue("Prompt should include valid user message", historyContents.any { it.contains("First question") })
        assertTrue("Prompt should include valid assistant message", historyContents.any { it.contains("First answer") })
        assertFalse("Prompt MUST NOT include orphan user message from failed turn", historyContents.any { it.contains("Failed question") })
        assertFalse("Prompt MUST NOT include orphan user message from cancelled turn", historyContents.any { it.contains("Cancelled question") })
        assertFalse("Prompt MUST NOT include error message", historyContents.any { it.contains("An error occurred") })
        assertFalse("Prompt MUST NOT include cancelled message", historyContents.any { it.contains("Cancelled generation") })

        firstPrompt.messages.zipWithNext { a, b ->
            assertFalse("Prompt MUST NOT contain consecutive Message.User messages", a is Message.User && b is Message.User)
        }
    }

    @Test
    fun testToolCallWithNullOrBlankIdSynchronizesAcrossAssistantAndUserToolMessages() = runTest {
        val topicId = "100"
        val sectionId = "sec_1"
        coEvery { medicalDatabaseTools.getTopicSectionsText(topicId, listOf(sectionId)) } returns TopicSectionsResponse(
            topicId = topicId,
            topicTitle = "CAP",
            sectionTitles = mapOf(sectionId to "Treatment"),
            markdown = "Dose: Amoxicillin 1 g PO TID."
        )
        every { medicalDatabaseTools.getTopicTitle(topicId) } returns "CAP"
        every { medicalDatabaseTools.getSectionTitle(topicId, sectionId) } returns "Treatment"

        // Tool call with null id
        val blankIdToolCall = MessagePart.Tool.Call(
            id = null,
            tool = "getTopicSectionsText",
            args = """{"topicId":"$topicId","sectionIds":["$sectionId"]}"""
        )

        val meta = ResponseMetaInfo(KoogClock.System.now())
        var round2Prompt: Prompt? = null
        var executeCount = 0
        executor.executeHandler = { prompt, _, _ ->
            executeCount++
            if (executeCount == 1) {
                Message.Assistant(parts = listOf(blankIdToolCall), metaInfo = meta)
            } else {
                round2Prompt = prompt
                Message.Assistant(parts = emptyList(), metaInfo = meta)
            }
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Amoxicillin 1 g PO TID."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        orchestrator.runTurn(
            question = "What is the dose?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager
        )

        val capturedPrompt = round2Prompt
        assertNotNull("Round 2 prompt must be captured", capturedPrompt)
        requireNotNull(capturedPrompt)
        val assistantMsg = capturedPrompt.messages.filterIsInstance<Message.Assistant>().lastOrNull()
        val userToolMsg = capturedPrompt.messages.filterIsInstance<Message.User>().lastOrNull()

        assertNotNull("Assistant message must exist", assistantMsg)
        assertNotNull("User tool result message must exist", userToolMsg)
        requireNotNull(assistantMsg)
        requireNotNull(userToolMsg)

        val assistantToolCall = assistantMsg.parts.filterIsInstance<MessagePart.Tool.Call>().firstOrNull()
        val userToolResult = userToolMsg.parts.filterIsInstance<MessagePart.Tool.Result>().firstOrNull()

        assertNotNull("Assistant tool call must exist", assistantToolCall)
        assertNotNull("User tool result must exist", userToolResult)
        requireNotNull(assistantToolCall)
        requireNotNull(userToolResult)

        assertNotNull("Assistant tool call ID must not be null", assistantToolCall.id)
        assertTrue("Assistant tool call ID must not be blank", assistantToolCall.id.orEmpty().isNotBlank())
        assertEquals("Assistant tool call ID must match User tool result ID", assistantToolCall.id, userToolResult.id)
    }

    @Test
    fun testParallelRound1CallsWithOneAmbiguousDoesNotShortCircuit() = runTest {
        val json = AiJsonUtils.json
        val meta = ResponseMetaInfo(KoogClock.System.now(), inputTokensCount = 40, outputTokensCount = 15)

        coEvery { medicalDatabaseTools.searchTopics("apixaban") } returns SearchTopicsResponse(
            query = "apixaban",
            results = listOf(
                SearchResultTopicItem(id = "Topic-Apix", title = "Apixaban: Drug information", type = "drug")
            )
        )
        coEvery { medicalDatabaseTools.searchTopics("warfarin") } returns SearchTopicsResponse(
            query = "warfarin",
            results = listOf(
                SearchResultTopicItem(id = "Topic-Warf", title = "Warfarin: Drug information", type = "drug"),
                SearchResultTopicItem(id = "Topic-Coum", title = "Coumadin dosing", type = "drug")
            )
        )
        coEvery { medicalDatabaseTools.getTopicOverview("Topic-Apix") } returns TopicOverviewResponse(
            topicId = "Topic-Apix",
            title = "Apixaban: Drug information",
            topicType = "drug",
            sections = listOf(OutlineSectionItem(id = "S1", title = "Dosing", level = 1))
        )
        coEvery { medicalDatabaseTools.getTopicSectionsText("Topic-Apix", listOf("S1")) } returns TopicSectionsResponse(
            topicId = "Topic-Apix",
            topicTitle = "Apixaban: Drug information",
            sectionTitles = mapOf("S1" to "Dosing"),
            markdown = "=== Section: S1 ===\nApixaban 5 mg BID."
        )
        every { medicalDatabaseTools.getTopicTitle("Topic-Apix") } returns "Apixaban: Drug information"
        every { medicalDatabaseTools.getSectionTitle("Topic-Apix", "S1") } returns "Dosing"

        val decisionClient = DecisionClient(
            transport = DecisionTransport { reqJson ->
                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                val answers = buildJsonObject {
                    qs.keys.forEach { k ->
                        when {
                            k == "requires_drug_monograph" || k == "needs_drug_monograph" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.10); put("confidence", 0.95) })
                            k.contains("Topic-Apix") || k.contains("S1") ->
                                put(k, buildJsonObject { put("type", "score"); put("score", 2.9); put("legend", buildJsonObject { put("0", "0"); put("3", "3") }); put("confidence", 0.9) })
                            k.contains("Topic-Warf") ->
                                put(k, buildJsonObject { put("type", "score"); put("score", 2.2); put("legend", buildJsonObject { put("0", "0"); put("3", "3") }); put("confidence", 0.9) })
                            k.contains("Topic-Coum") ->
                                put(k, buildJsonObject { put("type", "score"); put("score", 2.1); put("legend", buildJsonObject { put("0", "0"); put("3", "3") }); put("confidence", 0.9) })
                            else ->
                                put(k, buildJsonObject { put("type", "score"); put("score", 0.5); put("legend", buildJsonObject { put("0", "0"); put("3", "3") }); put("confidence", 0.9) })
                        }
                    }
                }
                json.encodeToString(JsonObject.serializer(), buildJsonObject { put("answers", answers); put("usage", buildJsonObject { put("total_input_tokens", 100); put("total_tokens", 100) }) })
            }
        )

        var step = 0
        executor.executeHandler = { _, _, _ ->
            step++
            when (step) {
                1 -> Message.Assistant(
                    parts = listOf(
                        MessagePart.Tool.Call(id = "c1", tool = "searchTopics", args = """{"query":"apixaban"}"""),
                        MessagePart.Tool.Call(id = "c2", tool = "searchTopics", args = """{"query":"warfarin"}""")
                    ),
                    metaInfo = meta
                )
                2 -> Message.Assistant(
                    parts = listOf(MessagePart.Text("Round 2 proceeds because warfarin was ambiguous.")),
                    metaInfo = meta
                )
                else -> Message.Assistant(parts = listOf(MessagePart.Text("Done")), metaInfo = meta)
            }
        }
        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Both apixaban and warfarin compared."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "Compare apixaban vs warfarin dosing",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Both apixaban and warfarin compared.", result.content)
        // Must NOT short-circuit on Round 1 because warfarin was ambiguous
        assertEquals(2, executor.executedPrompts.size)
    }

    @Test
    fun testDrugDosingQueryChainingIntoDrugTopicShortCircuits() = runTest {
        val json = AiJsonUtils.json
        val meta = ResponseMetaInfo(KoogClock.System.now(), inputTokensCount = 40, outputTokensCount = 15)

        coEvery { medicalDatabaseTools.searchTopics("apixaban renal dosing") } returns SearchTopicsResponse(
            query = "apixaban renal dosing",
            results = listOf(
                SearchResultTopicItem(id = "Topic-Apix-Drug", title = "Apixaban: Drug information", type = "drug")
            )
        )
        coEvery { medicalDatabaseTools.getTopicOverview("Topic-Apix-Drug") } returns TopicOverviewResponse(
            topicId = "Topic-Apix-Drug",
            title = "Apixaban: Drug information",
            topicType = "drug",
            sections = listOf(OutlineSectionItem(id = "S_Renal", title = "Renal Impairment Dosing", level = 1))
        )
        coEvery { medicalDatabaseTools.getTopicSectionsText("Topic-Apix-Drug", listOf("S_Renal")) } returns TopicSectionsResponse(
            topicId = "Topic-Apix-Drug",
            topicTitle = "Apixaban: Drug information",
            sectionTitles = mapOf("S_Renal" to "Renal Impairment Dosing"),
            markdown = "=== Section: S_Renal ===\nReduce dose to 2.5 mg BID if serum creatinine >= 1.5 mg/dL."
        )
        every { medicalDatabaseTools.getTopicTitle("Topic-Apix-Drug") } returns "Apixaban: Drug information"
        every { medicalDatabaseTools.getSectionTitle("Topic-Apix-Drug", "S_Renal") } returns "Renal Impairment Dosing"

        val decisionClient = DecisionClient(
            transport = DecisionTransport { reqJson ->
                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                val answers = buildJsonObject {
                    qs.keys.forEach { k ->
                        when {
                            k == "requires_drug_monograph" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.95); put("confidence", 0.95) })
                            k == "needs_drug_monograph" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.05); put("confidence", 0.95) })
                            k.contains("Topic-Apix-Drug") || k.contains("S_Renal") ->
                                put(k, buildJsonObject { put("type", "score"); put("score", 2.9); put("legend", buildJsonObject { put("0", "0"); put("3", "3") }); put("confidence", 0.9) })
                            else ->
                                put(k, buildJsonObject { put("type", "score"); put("score", 0.3); put("legend", buildJsonObject { put("0", "0"); put("3", "3") }); put("confidence", 0.9) })
                        }
                    }
                }
                json.encodeToString(JsonObject.serializer(), buildJsonObject { put("answers", answers); put("usage", buildJsonObject { put("total_input_tokens", 100); put("total_tokens", 100) }) })
            }
        )

        executor.executeHandler = { _, _, _ ->
            Message.Assistant(
                parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "searchTopics", args = """{"query":"apixaban renal dosing"}""")),
                metaInfo = meta
            )
        }
        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Reduce to 2.5 mg BID."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "Apixaban renal dosing?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Reduce to 2.5 mg BID.", result.content)
        // Chained directly into drug monograph, satisfies monograph requirement and short-circuits to Stage 2 in 1 round!
        assertEquals(1, executor.executedPrompts.size)
        assertEquals(1, executor.streamingPrompts.size)
    }

    @Test
    fun testTableCandidateHitChainsGraphicContentNotTopicOverview() = runTest {
        val json = AiJsonUtils.json
        val meta = ResponseMetaInfo(KoogClock.System.now(), inputTokensCount = 40, outputTokensCount = 15)

        coEvery { medicalDatabaseTools.searchTopics("ckd staging") } returns SearchTopicsResponse(
            query = "ckd staging",
            results = listOf(
                SearchResultTopicItem(id = "Table-CKD-99", title = "CKD staging criteria", type = "table")
            )
        )
        coEvery { medicalDatabaseTools.getGraphicContent("Table-CKD-99") } returns GraphicContentResponse(
            graphicId = "Table-CKD-99",
            title = "CKD staging criteria",
            markdown = "| GFR | Stage |\n| --- | --- |\n| >= 90 | G1 |"
        )
        every { medicalDatabaseTools.getTopicTitle(any()) } returns "CKD Staging"

        val decisionClient = DecisionClient(
            transport = DecisionTransport { reqJson ->
                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                val answers = buildJsonObject {
                    qs.keys.forEach { k ->
                        when {
                            k == "requires_table_data" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.95); put("confidence", 0.95) })
                            k == "requires_drug_monograph" || k == "needs_drug_monograph" ->
                                put(k, buildJsonObject { put("type", "noul"); put("noul", 0.10); put("confidence", 0.95) })
                            k.contains("Table-CKD-99") ->
                                put(k, buildJsonObject { put("type", "score"); put("score", 2.9); put("legend", buildJsonObject { put("0", "0"); put("3", "3") }); put("confidence", 0.9) })
                            else ->
                                put(k, buildJsonObject { put("type", "score"); put("score", 0.2); put("legend", buildJsonObject { put("0", "0"); put("3", "3") }); put("confidence", 0.9) })
                        }
                    }
                }
                json.encodeToString(JsonObject.serializer(), buildJsonObject { put("answers", answers); put("usage", buildJsonObject { put("total_input_tokens", 100); put("total_tokens", 100) }) })
            }
        )

        executor.executeHandler = { _, _, _ ->
            Message.Assistant(
                parts = listOf(MessagePart.Tool.Call(id = "c1", tool = "searchTopics", args = """{"query":"ckd staging"}""")),
                metaInfo = meta
            )
        }
        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Stage G1 is GFR >= 90."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "CKD staging table?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Stage G1 is GFR >= 90.", result.content)
        // Must chain getGraphicContent directly, NEVER calling getTopicOverview on a table ID
        coVerify(exactly = 1) { medicalDatabaseTools.getGraphicContent("Table-CKD-99") }
        coVerify(exactly = 0) { medicalDatabaseTools.getTopicOverview("Table-CKD-99") }
        assertEquals(1, executor.executedPrompts.size)
    }

    @Test
    fun testDecisionModeOffDoesNotShortCircuitOnCalcTopic() = runTest {
        val calcTopicId = "calc-cha2ds2"
        val calcTitle = "CHA2DS2-VASc Score for AFib Stroke Risk"
        val calcText = "Total score: 2 points (moderate-high risk)."

        coEvery { medicalDatabaseTools.getTopicOverview(calcTopicId) } returns TopicOverviewResponse(
            topicId = calcTopicId,
            title = calcTitle,
            topicType = "calc",
            sections = listOf(OutlineSectionItem(id = "FULL", title = "Calculator Content", level = 1)),
            markdown = calcText
        )
        every { medicalDatabaseTools.getTopicTitle(calcTopicId) } returns calcTitle

        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 50,
            outputTokensCount = 20
        )

        val calcCall = MessagePart.Tool.Call(
            id = "call_calc_1",
            tool = "getTopicOverview",
            args = """{"topicId":"$calcTopicId"}"""
        )

        var roundCount = 0
        executor.executeHandler = { _, _, _ ->
            roundCount++
            if (roundCount == 1) {
                // Round 1: Model requests calculator
                Message.Assistant(parts = listOf(calcCall), metaInfo = meta)
            } else {
                // Round 2: Under DecisionMode.OFF, model is called again and can perform drug monograph lookup or finish
                Message.Assistant(parts = emptyList(), metaInfo = meta)
            }
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Score is 2 points."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        // Run turn with decisionClient = null (DecisionMode.OFF)
        val result = orchestrator.runTurn(
            question = "Calculate CHA2DS2-VASc and tell me apixaban dosing",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = null,
            decisionConfig = null
        )

        assertEquals("Score is 2 points.", result.content)
        // Under DecisionMode.OFF, must NOT short circuit! It must execute Round 1 and Round 2
        assertEquals(2, executor.executedPrompts.size)
        assertEquals(1, executor.streamingPrompts.size)
    }

    @Test
    fun testTier2CoWinnersChainsBothTopicsInParallelAndShortCircuits() = runTest {
        val topic1Id = "topic-diag"
        val topic1Title = "Pulmonary Embolism: Diagnostic Evaluation"
        val topic2Id = "topic-treat"
        val topic2Title = "Pulmonary Embolism: Treatment Overview"

        coEvery { medicalDatabaseTools.searchTopics("PE evaluation and treatment") } returns SearchTopicsResponse(
            query = "PE evaluation and treatment",
            results = listOf(
                SearchResultTopicItem(id = topic1Id, title = topic1Title, type = "medical"),
                SearchResultTopicItem(id = topic2Id, title = topic2Title, type = "medical")
            )
        )

        coEvery { medicalDatabaseTools.getTopicOverview(topic1Id) } returns TopicOverviewResponse(
            topicId = topic1Id,
            title = topic1Title,
            topicType = "medical",
            sections = listOf(OutlineSectionItem(id = "sec-diag", title = "Diagnostic Algorithms", level = 1))
        )
        coEvery { medicalDatabaseTools.getTopicOverview(topic2Id) } returns TopicOverviewResponse(
            topicId = topic2Id,
            title = topic2Title,
            topicType = "medical",
            sections = listOf(OutlineSectionItem(id = "sec-treat", title = "Anticoagulation Regimen", level = 1))
        )

        coEvery { medicalDatabaseTools.getTopicSectionsText(topic1Id, listOf("sec-diag")) } returns TopicSectionsResponse(
            topicId = topic1Id,
            topicTitle = topic1Title,
            sectionTitles = mapOf("sec-diag" to "Diagnostic Algorithms"),
            markdown = "CTA is the diagnostic test of choice."
        )
        coEvery { medicalDatabaseTools.getTopicSectionsText(topic2Id, listOf("sec-treat")) } returns TopicSectionsResponse(
            topicId = topic2Id,
            topicTitle = topic2Title,
            sectionTitles = mapOf("sec-treat" to "Anticoagulation Regimen"),
            markdown = "Immediate anticoagulation with LMWH or DOAC is indicated."
        )

        every { medicalDatabaseTools.getTopicTitle(topic1Id) } returns topic1Title
        every { medicalDatabaseTools.getTopicTitle(topic2Id) } returns topic2Title
        every { medicalDatabaseTools.getSectionTitle(topic1Id, "sec-diag") } returns "Diagnostic Algorithms"
        every { medicalDatabaseTools.getSectionTitle(topic2Id, "sec-treat") } returns "Anticoagulation Regimen"

        val transport = DecisionTransport { reqJson ->
            val answers = buildJsonObject {
                put("requires_drug_monograph", buildJsonObject { put("type", "noul"); put("noul", 0.1); put("confidence", 0.9) })
                put("requires_table_data", buildJsonObject { put("type", "noul"); put("noul", 0.1); put("confidence", 0.9) })
                put("is_topic_relevant", buildJsonObject { put("type", "noul"); put("noul", 0.95); put("confidence", 0.9) })

                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                qs.keys.forEach { key ->
                    when {
                        key.contains("topic-diag") || key.contains("topic_diag") || key.contains("diag") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.9); put("confidence", 0.95) })
                        }
                        key.contains("topic-treat") || key.contains("topic_treat") || key.contains("treat") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.85); put("confidence", 0.95) })
                        }
                        key.startsWith("sec_") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.7); put("confidence", 0.9) })
                        }
                    }
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 80) })
                }
            )
        }
        val decisionClient = DecisionClient(transport)

        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 50,
            outputTokensCount = 20
        )

        val searchCall = MessagePart.Tool.Call(
            id = "call_pe_search",
            tool = "searchTopics",
            args = """{"query":"PE evaluation and treatment"}"""
        )

        executor.executeHandler = { _, _, _ ->
            // Round 1: Model issues search
            Message.Assistant(parts = listOf(searchCall), metaInfo = meta)
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Diagnose with CTA; treat with DOAC."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "How to evaluate and treat acute PE?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Diagnose with CTA; treat with DOAC.", result.content)
        // Both topic 1 and topic 2 must be chained in parallel!
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicOverview(topic1Id) }
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicOverview(topic2Id) }
        // Both topics' sections must be prefetched
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicSectionsText(topic1Id, listOf("sec-diag")) }
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicSectionsText(topic2Id, listOf("sec-treat")) }
        // And short-circuits directly to Stage 2 (only 1 retrieval round executed by Primary LLM!)
        assertEquals(1, executor.executedPrompts.size)
        assertEquals(2, result.topicRefs.size)
    }

    @Test
    fun testTier2CoWinnersWithMedicalAndDrugMonographSatisfiesDrugRequirement() = runTest {
        val topicMedId = "topic-afib-med"
        val topicMedTitle = "Atrial Fibrillation: Anticoagulation Overview"
        val topicDrugId = "topic-apixaban-drug"
        val topicDrugTitle = "Apixaban: Drug Information"

        coEvery { medicalDatabaseTools.searchTopics("afib apixaban") } returns SearchTopicsResponse(
            query = "afib apixaban",
            results = listOf(
                SearchResultTopicItem(id = topicMedId, title = topicMedTitle, type = "medical"),
                SearchResultTopicItem(id = topicDrugId, title = topicDrugTitle, type = "drug")
            )
        )

        coEvery { medicalDatabaseTools.getTopicOverview(topicMedId) } returns TopicOverviewResponse(
            topicId = topicMedId,
            title = topicMedTitle,
            topicType = "medical",
            sections = listOf(OutlineSectionItem(id = "sec-afib-risk", title = "Risk Stratification", level = 1))
        )
        coEvery { medicalDatabaseTools.getTopicOverview(topicDrugId) } returns TopicOverviewResponse(
            topicId = topicDrugId,
            title = topicDrugTitle,
            topicType = "drug",
            sections = listOf(OutlineSectionItem(id = "sec-apix-dose", title = "Dosing in AFib", level = 1))
        )

        coEvery { medicalDatabaseTools.getTopicSectionsText(topicMedId, listOf("sec-afib-risk")) } returns TopicSectionsResponse(
            topicId = topicMedId,
            topicTitle = topicMedTitle,
            sectionTitles = mapOf("sec-afib-risk" to "Risk Stratification"),
            markdown = "Oral anticoagulation is recommended for CHA2DS2-VASc >= 2."
        )
        coEvery { medicalDatabaseTools.getTopicSectionsText(topicDrugId, listOf("sec-apix-dose")) } returns TopicSectionsResponse(
            topicId = topicDrugId,
            topicTitle = topicDrugTitle,
            sectionTitles = mapOf("sec-apix-dose" to "Dosing in AFib"),
            markdown = "Apixaban standard dose is 5 mg twice daily; 2.5 mg twice daily if dose reduction criteria met."
        )

        every { medicalDatabaseTools.getTopicTitle(topicMedId) } returns topicMedTitle
        every { medicalDatabaseTools.getTopicTitle(topicDrugId) } returns topicDrugTitle
        every { medicalDatabaseTools.getSectionTitle(topicMedId, "sec-afib-risk") } returns "Risk Stratification"
        every { medicalDatabaseTools.getSectionTitle(topicDrugId, "sec-apix-dose") } returns "Dosing in AFib"

        val transport = DecisionTransport { reqJson ->
            val answers = buildJsonObject {
                // Initial search says drug monograph needed
                put("requires_drug_monograph", buildJsonObject { put("type", "noul"); put("noul", 0.85); put("confidence", 0.9) })
                put("requires_table_data", buildJsonObject { put("type", "noul"); put("noul", 0.1); put("confidence", 0.9) })
                put("is_topic_relevant", buildJsonObject { put("type", "noul"); put("noul", 0.95); put("confidence", 0.9) })

                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                qs.keys.forEach { key ->
                    when {
                        key.contains("afib") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.85); put("confidence", 0.95) })
                        }
                        key.contains("apixaban") || key.contains("apix") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.70); put("confidence", 0.95) })
                        }
                        key.startsWith("sec_") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.8); put("confidence", 0.9) })
                        }
                    }
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 80) })
                }
            )
        }
        val decisionClient = DecisionClient(transport)

        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 50,
            outputTokensCount = 20
        )

        val searchCall = MessagePart.Tool.Call(
            id = "call_afib_search",
            tool = "searchTopics",
            args = """{"query":"afib apixaban"}"""
        )

        executor.executeHandler = { _, _, _ ->
            Message.Assistant(parts = listOf(searchCall), metaInfo = meta)
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Apixaban 5 mg twice daily is recommended for AFib."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "Apixaban dosing in AFib?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Apixaban 5 mg twice daily is recommended for AFib.", result.content)
        // Both topic 1 (medical) and topic 2 (drug) chained in parallel
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicOverview(topicMedId) }
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicOverview(topicDrugId) }
        // Because topic 2 is a drug monograph, effectiveDrugNeeded becomes false and short-circuits directly in Round 1!
        assertEquals(1, executor.executedPrompts.size)
    }

    @Test
    fun testUnknownDrugQueryProvidesPrefetchedPreviewWhenDrugMonographNeeded() = runTest {
        val capTopicId = "topic-cap-outpatient"
        val capTitle = "Community-Acquired Pneumonia: Outpatient Treatment"
        val capRegimenText = "For outpatient empiric therapy of CAP without comorbidities, amoxicillin 1 g orally three times daily is recommended."

        coEvery { medicalDatabaseTools.searchTopics("community acquired pneumonia treatment") } returns SearchTopicsResponse(
            query = "community acquired pneumonia treatment",
            results = listOf(
                SearchResultTopicItem(id = capTopicId, title = capTitle, type = "medical")
            )
        )

        coEvery { medicalDatabaseTools.getTopicOverview(capTopicId) } returns TopicOverviewResponse(
            topicId = capTopicId,
            title = capTitle,
            topicType = "medical",
            sections = listOf(OutlineSectionItem(id = "sec-cap-regimen", title = "Empiric Outpatient Regimens", level = 1))
        )

        coEvery { medicalDatabaseTools.getTopicSectionsText(capTopicId, listOf("sec-cap-regimen")) } returns TopicSectionsResponse(
            topicId = capTopicId,
            topicTitle = capTitle,
            sectionTitles = mapOf("sec-cap-regimen" to "Empiric Outpatient Regimens"),
            markdown = capRegimenText
        )

        every { medicalDatabaseTools.getTopicTitle(capTopicId) } returns capTitle
        every { medicalDatabaseTools.getSectionTitle(capTopicId, "sec-cap-regimen") } returns "Empiric Outpatient Regimens"

        val transport = DecisionTransport { reqJson ->
            val answers = buildJsonObject {
                // Dosing question without naming the drug -> requires_drug_monograph is true
                put("requires_drug_monograph", buildJsonObject { put("type", "noul"); put("noul", 0.90); put("confidence", 0.95) })
                put("needs_drug_monograph", buildJsonObject { put("type", "noul"); put("noul", 0.90); put("confidence", 0.95) })
                put("requires_table_data", buildJsonObject { put("type", "noul"); put("noul", 0.1); put("confidence", 0.9) })
                put("is_topic_relevant", buildJsonObject { put("type", "noul"); put("noul", 0.95); put("confidence", 0.9) })

                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                qs.keys.forEach { key ->
                    when {
                        key.contains("cap") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.85); put("confidence", 0.95) })
                        }
                        key.startsWith("sec_") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.8); put("confidence", 0.9) })
                        }
                    }
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 80) })
                }
            )
        }
        val decisionClient = DecisionClient(transport)

        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 50,
            outputTokensCount = 20
        )

        val searchCall = MessagePart.Tool.Call(
            id = "call_cap_search",
            tool = "searchTopics",
            args = """{"query":"community acquired pneumonia treatment"}"""
        )

        var roundCount = 0
        var receivedPreviewInRound2: String? = null
        var lastToolResultJson: String? = null
        executor.executeHandler = { prompt, _, _ ->
            roundCount++
            if (roundCount == 1) {
                // Round 1: Model searches CAP
                Message.Assistant(parts = listOf(searchCall), metaInfo = meta)
            } else {
                // Round 2: Model receives tool result containing prefetchedPreview
                val userToolMsg = prompt.messages.lastOrNull { it is Message.User } as? Message.User
                val toolResultPart = userToolMsg?.parts?.filterIsInstance<MessagePart.Tool.Result>()?.firstOrNull()
                val toolResultJson = toolResultPart?.output.orEmpty()
                lastToolResultJson = toolResultJson
                if (toolResultJson.contains("prefetchedPreview")) {
                    val parsed = json.parseToJsonElement(toolResultJson).jsonObject
                    receivedPreviewInRound2 = parsed["decisionRerank"]?.jsonObject?.get("prefetchedPreview")?.jsonPrimitive?.contentOrNull
                }
                // Model finishes
                Message.Assistant(parts = emptyList(), metaInfo = meta)
            }
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Amoxicillin 1 g three times daily is recommended."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "First-line antibiotic and renal dosing for CAP?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Amoxicillin 1 g three times daily is recommended.", result.content)
        // Must NOT short circuit because effectiveDrugNeeded is true!
        assertEquals(2, executor.executedPrompts.size)
        // Must provide prefetchedPreview in Round 2 so Primary LLM knows which antibiotic UpToDate recommended
        assertNotNull(receivedPreviewInRound2)
        assertTrue(receivedPreviewInRound2!!.contains("amoxicillin 1 g orally three times daily"))
        // Must NOT serialize prefetchedSectionsMarkdown to prevent duplicating the 2,500 chars in JSON
        val parsedOverview = json.parseToJsonElement(lastToolResultJson!!).jsonObject
        assertNull(parsedOverview["decisionRerank"]?.jsonObject?.get("prefetchedSectionsMarkdown"))
    }

    @Test
    fun testTier2CoWinnersWithDrugFailingPrefetchDoesNotSatisfyDrugRequirement() = runTest {
        val topicMedId = "topic-afib-mgmt"
        val topicMedTitle = "Atrial Fibrillation Management"
        val topicDrugId = "topic-apixaban-drug"
        val topicDrugTitle = "Apixaban: Drug Information"

        coEvery { medicalDatabaseTools.searchTopics("afib apixaban") } returns SearchTopicsResponse(
            query = "afib apixaban",
            results = listOf(
                SearchResultTopicItem(id = topicMedId, title = topicMedTitle, type = "medical"),
                SearchResultTopicItem(id = topicDrugId, title = topicDrugTitle, type = "drug")
            )
        )

        coEvery { medicalDatabaseTools.getTopicOverview(topicMedId) } returns TopicOverviewResponse(
            topicId = topicMedId,
            title = topicMedTitle,
            topicType = "medical",
            sections = listOf(OutlineSectionItem(id = "sec-afib-1", title = "Rate Control", level = 1))
        )

        coEvery { medicalDatabaseTools.getTopicOverview(topicDrugId) } returns TopicOverviewResponse(
            topicId = topicDrugId,
            title = topicDrugTitle,
            topicType = "drug",
            sections = listOf(OutlineSectionItem(id = "sec-drug-1", title = "Dosing", level = 1))
        )

        coEvery { medicalDatabaseTools.getTopicSectionsText(topicMedId, listOf("sec-afib-1")) } returns TopicSectionsResponse(
            topicId = topicMedId,
            topicTitle = topicMedTitle,
            sectionTitles = mapOf("sec-afib-1" to "Rate Control"),
            markdown = "Rate control is first line."
        )

        // Drug topic sections fail/return blank, so didPrefetch is false for drug candidate
        coEvery { medicalDatabaseTools.getTopicSectionsText(topicDrugId, listOf("sec-drug-1")) } returns TopicSectionsResponse(
            topicId = topicDrugId,
            topicTitle = topicDrugTitle,
            sectionTitles = mapOf("sec-drug-1" to "Dosing"),
            markdown = ""
        )

        every { medicalDatabaseTools.getTopicTitle(topicMedId) } returns topicMedTitle
        every { medicalDatabaseTools.getTopicTitle(topicDrugId) } returns topicDrugTitle
        every { medicalDatabaseTools.getSectionTitle(topicMedId, "sec-afib-1") } returns "Rate Control"
        every { medicalDatabaseTools.getSectionTitle(topicDrugId, "sec-drug-1") } returns "Dosing"

        val transport = DecisionTransport { reqJson ->
            val answers = buildJsonObject {
                put("requires_drug_monograph", buildJsonObject { put("type", "noul"); put("noul", 0.95); put("confidence", 0.95) })
                put("requires_table_data", buildJsonObject { put("type", "noul"); put("noul", 0.1); put("confidence", 0.9) })
                put("is_topic_relevant", buildJsonObject { put("type", "noul"); put("noul", 0.95); put("confidence", 0.9) })

                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                qs.keys.forEach { key ->
                    when {
                        key.contains("afib") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.85); put("confidence", 0.95) })
                        }
                        key.contains("apixaban") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.75); put("confidence", 0.95) })
                        }
                        key.startsWith("sec_") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.8); put("confidence", 0.9) })
                        }
                    }
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 80) })
                }
            )
        }
        val decisionClient = DecisionClient(transport)

        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 50,
            outputTokensCount = 20
        )

        val searchCall = MessagePart.Tool.Call(
            id = "call_afib_search",
            tool = "searchTopics",
            args = """{"query":"afib apixaban"}"""
        )

        var roundCount = 0
        executor.executeHandler = { prompt, _, _ ->
            roundCount++
            if (roundCount == 1) {
                Message.Assistant(parts = listOf(searchCall), metaInfo = meta)
            } else {
                // Round 2
                Message.Assistant(parts = emptyList(), metaInfo = meta)
            }
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Apixaban 5 mg twice daily."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "Apixaban dosing in AFib?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Apixaban 5 mg twice daily.", result.content)
        // Both topic 1 (medical) and topic 2 (drug) were chained
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicOverview(topicMedId) }
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicOverview(topicDrugId) }
        // Because drug prefetch failed, anyDrugSatisfied is false -> must NOT short circuit!
        assertEquals(2, executor.executedPrompts.size)
    }

    @Test
    fun testOptionADominantRelatedTopicBlocksShortCircuitAndAllowsPrimaryLlmToInspectIt() = runTest {
        val genTopicId = "topic-afib-gen"
        val genTitle = "Atrial Fibrillation: Rate Control in Adults"
        val pregTopicId = "topic-afib-preg"
        val pregTitle = "Management of Atrial Fibrillation During Pregnancy"

        coEvery { medicalDatabaseTools.searchTopics("afib pregnancy") } returns SearchTopicsResponse(
            query = "afib pregnancy",
            results = listOf(
                SearchResultTopicItem(id = genTopicId, title = genTitle, type = "medical")
            )
        )

        // General topic overview has a section scoring 1.9, but related topic scoring 2.9
        coEvery { medicalDatabaseTools.getTopicOverview(genTopicId) } returns TopicOverviewResponse(
            topicId = genTopicId,
            title = genTitle,
            topicType = "medical",
            sections = listOf(OutlineSectionItem(id = "sec-gen-1", title = "Adult Rate Control", level = 1)),
            relatedTopics = listOf(RelatedTopicItem(id = pregTopicId, title = pregTitle))
        )

        coEvery { medicalDatabaseTools.getTopicSectionsText(genTopicId, listOf("sec-gen-1")) } returns TopicSectionsResponse(
            topicId = genTopicId,
            topicTitle = genTitle,
            sectionTitles = mapOf("sec-gen-1" to "Adult Rate Control"),
            markdown = "Standard rate control in non-pregnant adults."
        )

        // Pregnancy topic overview has specialized section scoring 2.9
        coEvery { medicalDatabaseTools.getTopicOverview(pregTopicId) } returns TopicOverviewResponse(
            topicId = pregTopicId,
            title = pregTitle,
            topicType = "medical",
            sections = listOf(OutlineSectionItem(id = "sec-preg-1", title = "Rate Control During Pregnancy", level = 1))
        )

        coEvery { medicalDatabaseTools.getTopicSectionsText(pregTopicId, listOf("sec-preg-1")) } returns TopicSectionsResponse(
            topicId = pregTopicId,
            topicTitle = pregTitle,
            sectionTitles = mapOf("sec-preg-1" to "Rate Control During Pregnancy"),
            markdown = "Metoprolol or digoxin are preferred for rate control during pregnancy."
        )

        every { medicalDatabaseTools.getTopicTitle(genTopicId) } returns genTitle
        every { medicalDatabaseTools.getTopicTitle(pregTopicId) } returns pregTitle
        every { medicalDatabaseTools.getSectionTitle(genTopicId, "sec-gen-1") } returns "Adult Rate Control"
        every { medicalDatabaseTools.getSectionTitle(pregTopicId, "sec-preg-1") } returns "Rate Control During Pregnancy"

        val transport = DecisionTransport { reqJson ->
            val answers = buildJsonObject {
                put("requires_drug_monograph", buildJsonObject { put("type", "noul"); put("noul", 0.1); put("confidence", 0.95) })
                put("requires_table_data", buildJsonObject { put("type", "noul"); put("noul", 0.1); put("confidence", 0.9) })
                put("is_topic_relevant", buildJsonObject { put("type", "noul"); put("noul", 0.95); put("confidence", 0.9) })

                val qs = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
                qs.keys.forEach { key ->
                    when {
                        key.contains("afib_gen") || key.contains("topic-afib-gen") || key == "cand_1" -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.85); put("confidence", 0.95) })
                        }
                        key.contains("sec-gen-1") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 1.9); put("confidence", 0.9) })
                        }
                        key.contains("rel_") || key.contains("afib_preg") || key.contains("topic-afib-preg") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.9); put("confidence", 0.95) })
                        }
                        key.contains("sec-preg-1") -> {
                            put(key, buildJsonObject { put("type", "score"); put("score", 2.9); put("confidence", 0.95) })
                        }
                    }
                }
            }
            json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("answers", answers)
                    put("usage", buildJsonObject { put("total_input_tokens", 80) })
                }
            )
        }
        val decisionClient = DecisionClient(transport)

        val meta = ResponseMetaInfo(
            timestamp = KoogClock.System.now(),
            inputTokensCount = 50,
            outputTokensCount = 20
        )

        val searchCall = MessagePart.Tool.Call(
            id = "call_search_preg",
            tool = "searchTopics",
            args = """{"query":"afib pregnancy"}"""
        )
        val overviewCallPreg = MessagePart.Tool.Call(
            id = "call_overview_preg",
            tool = "getTopicOverview",
            args = """{"topicId":"$pregTopicId"}"""
        )

        var roundCount = 0
        var receivedNeedsRelatedTopicInRound2 = false
        executor.executeHandler = { prompt, _, _ ->
            roundCount++
            if (roundCount == 1) {
                // Round 1: Model searches
                Message.Assistant(parts = listOf(searchCall), metaInfo = meta)
            } else {
                // Round 2: Model receives tool result indicating dedicated related topic needed
                val userToolMsg = prompt.messages.lastOrNull { it is Message.User } as? Message.User
                val toolResultPart = userToolMsg?.parts?.filterIsInstance<MessagePart.Tool.Result>()?.firstOrNull()
                val toolResultJson = toolResultPart?.output.orEmpty()
                if (toolResultJson.contains("needsRelatedTopic")) {
                    val parsed = json.parseToJsonElement(toolResultJson).jsonObject
                    receivedNeedsRelatedTopicInRound2 = parsed["decisionRerank"]?.jsonObject?.get("needsRelatedTopic")?.jsonPrimitive?.contentOrNull == "true"
                }
                // Model calls getTopicOverview for the specialized pregnancy topic
                Message.Assistant(parts = listOf(overviewCallPreg), metaInfo = meta)
            }
        }

        executor.streamingHandler = { _, _, _ ->
            flow {
                emit(StreamFrame.TextDelta("Metoprolol is preferred for AFib rate control in pregnancy."))
                emit(StreamFrame.End(finishReason = "stop", metaInfo = meta))
            }
        }

        val result = orchestrator.runTurn(
            question = "What is preferred for AFib rate control during pregnancy?",
            patientProfile = PatientProfile(),
            chatHistory = emptyList(),
            executor = executor,
            model = model,
            providerParams = null,
            streamingManager = streamingManager,
            decisionClient = decisionClient,
            decisionConfig = DecisionConfig(enableRetrievalRerank = true, autoPrefetch = true)
        )

        assertEquals("Metoprolol is preferred for AFib rate control in pregnancy.", result.content)
        // General topic was chained in Round 1
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicOverview(genTopicId) }
        // Because specialized related topic scored 2.9 > 1.9, short-circuit was blocked in Round 1!
        assertTrue("Round 2 must receive needsRelatedTopic=true hint", receivedNeedsRelatedTopicInRound2)
        // Primary LLM opened the specialized topic in Round 2
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicOverview(pregTopicId) }
        // Specialized topic's sections were auto-prefetched
        coVerify(exactly = 1) { medicalDatabaseTools.getTopicSectionsText(pregTopicId, listOf("sec-preg-1")) }
        // After Round 2, all required topics were inspected, so it short-circuited to Stage 2 (exactly 2 rounds)
        assertEquals(2, executor.executedPrompts.size)
    }
}
