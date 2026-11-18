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
import com.clinref.app.data.GraphicContentResponse
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.OutlineGraphicItem
import com.clinref.app.data.OutlineSectionItem
import com.clinref.app.data.SearchResultTopicItem
import com.clinref.app.data.SearchTopicsResponse
import com.clinref.app.data.TopicOverviewResponse
import com.clinref.app.data.TopicSectionsResponse
import com.clinref.app.data.local.entity.MessageEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertTrue(result.validation.passed)

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
        assertTrue(result.validation.passed)
        assertEquals(1, failingDecisionClient.failedCalls)
    }

    @Test
    fun testChatHistoryFiltersOutErrorsAndCancelledMessages() = runTest {
        val history = listOf(
            MessageEntity(id = "m1", conversationId = "c1", role = "user", content = "First question", timestamp = 1000L),
            MessageEntity(id = "m2", conversationId = "c1", role = "assistant", content = "First answer", timestamp = 2000L),
            MessageEntity(id = "m3", conversationId = "c1", role = "assistant", content = "An error occurred during response generation", timestamp = 3000L, isError = true),
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
        assertFalse("Prompt MUST NOT include error message", historyContents.any { it.contains("An error occurred") })
        assertFalse("Prompt MUST NOT include cancelled message", historyContents.any { it.contains("Cancelled generation") })
    }
}
