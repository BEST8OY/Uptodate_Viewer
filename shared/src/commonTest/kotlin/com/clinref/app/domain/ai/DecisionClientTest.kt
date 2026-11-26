package com.clinref.app.domain.ai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.reflect.KClass

class DecisionClientTest {

    private val json = AiJsonUtils.json

    @Test
    fun `sanitizeQuestionKey produces valid ASCII keys within 64 chars`() {
        assertEquals("sec_123_45", sanitizeQuestionKey("sec:123/45"))
        assertEquals("q_---leading-dash", sanitizeQuestionKey("---leading-dash", prefix = "q"))
        assertEquals("topic_7", sanitizeQuestionKey("!!!", prefix = "topic", index = 7))

        val longKey = sanitizeQuestionKey("a".repeat(120))
        assertEquals(64, longKey.length)
        assertTrue(Regex("^[A-Za-z0-9_-]{1,64}$").matches(longKey))
    }

    @Test
    fun `makeTextBlocks appends blank-line delimiter only when missing trailing newline`() {
        val blocks = makeTextBlocks(
            listOf(
                "",
                "QUESTION:\nDose of amoxicillin?",
                "PATIENT:\nAge 5y\n",
                "FINAL BLOCK"
            )
        )
        assertEquals(3, blocks.size)
        assertEquals("QUESTION:\nDose of amoxicillin?\n\n", blocks[0].text)
        assertEquals("PATIENT:\nAge 5y\n", blocks[1].text)
        assertEquals("FINAL BLOCK", blocks[2].text)
    }

    @Test
    fun `question builders enforce official API validation constraints`() {
        try {
            choiceQuestion("Pick one", mapOf("only_one" to "Only option"))
            fail("Expected IllegalArgumentException for < 2 choice options")
        } catch (_: IllegalArgumentException) {
        }

        try {
            choiceQuestion("Pick one", mapOf("a".repeat(129) to "Desc1", "b" to "Desc2"))
            fail("Expected IllegalArgumentException for choice option key > 128 chars")
        } catch (_: IllegalArgumentException) {
        }

        try {
            scoreQuestion("Rate", listOf("Single level"))
            fail("Expected IllegalArgumentException for < 2 score levels")
        } catch (_: IllegalArgumentException) {
        }

        try {
            scoreQuestion("Rate", listOf("Low", "   "))
            fail("Expected IllegalArgumentException for blank score level")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `decide serializes typed questions and decodes noul choice score answers plus usage`() = runTest {
        val capturedBodies = mutableListOf<JsonObject>()
        val transport = DecisionTransport { reqJson ->
            val reqObj = json.parseToJsonElement(reqJson).jsonObject
            capturedBodies.add(reqObj)
            """
            {
              "answers": {
                "requires_drug": { "type": "noul", "noul": 0.88, "confidence": 0.91 },
                "route": {
                  "type": "choice",
                  "choice": "iv",
                  "probabilities": { "po": 0.12, "iv": 0.88 },
                  "confidence": 0.86
                },
                "relevance": {
                  "type": "score",
                  "score": 2.45,
                  "legend": { "0": "Irrelevant", "1": "Tangential", "2": "Helpful", "3": "Essential" },
                  "probabilities": { "0": 0.02, "1": 0.08, "2": 0.33, "3": 0.57 },
                  "confidence": 0.78
                }
              },
              "usage": {
                "total_input_tokens": 1500,
                "input_tokens_by_modality": [{ "modality": "TEXT", "tokens": 1500 }],
                "total_output_tokens": 0,
                "total_tokens": 1500
              }
            }
            """.trimIndent()
        }

        var callbackTokens = 0
        val client = DecisionClient(
            transport = transport,
            failOpen = true,
            onUsage = { usage, _ -> callbackTokens += usage.totalInputTokens }
        )

        val res = client.decide(
            input = makeTextBlocks(listOf("Question: IV vancomycin dosing?", "Context: MRSA bacteremia")),
            questions = linkedMapOf(
                "requires_drug" to noulQuestion(
                    instructions = "Requires drug monograph?",
                    trueDescription = "Yes",
                    falseDescription = "No"
                ),
                "route" to choiceQuestion(
                    instructions = "Preferred route",
                    criteria = mapOf("po" to "Oral", "iv" to "Intravenous")
                ),
                "relevance" to scoreQuestion(
                    instructions = "Rate relevance",
                    criteria = listOf("Irrelevant", "Tangential", "Helpful", "Essential")
                )
            )
        )

        assertTrue(res.ok)
        assertNull(res.error)
        assertEquals(1, capturedBodies.size)
        val reqObj = capturedBodies.first()
        assertEquals("gemini-decision-1", reqObj["model"]?.jsonPrimitive?.contentOrNull)
        assertTrue(reqObj["input"] is JsonArray)
        val qs = reqObj["questions"]!!.jsonObject
        assertEquals("noul", qs["requires_drug"]!!.jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Yes", qs["requires_drug"]!!.jsonObject["criteria"]!!.jsonObject["true_description"]?.jsonPrimitive?.contentOrNull)
        assertEquals("choice", qs["route"]!!.jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals(4, qs["relevance"]!!.jsonObject["criteria"]!!.jsonArray.size)

        val noul = res.getNoul("requires_drug")
        assertNotNull(noul)
        assertEquals(0.88, noul!!.noul, 1e-6)
        assertEquals(0.12, noul.pFalse, 1e-6)
        assertTrue(noul.isTrue(0.75))

        val choice = res.getChoice("route")
        assertNotNull(choice)
        assertEquals("iv", choice!!.choice)
        assertEquals(0.88, choice.probabilities["iv"] ?: 0.0, 1e-6)

        val score = res.getScore("relevance")
        assertNotNull(score)
        assertEquals(2.45, score!!.score, 1e-6)
        assertEquals(3, score.maxScore)
        assertEquals(2.45 / 3.0, score.normalizedScore, 1e-6)
        assertEquals(3 to "Essential", score.topLevel)

        assertEquals(1500, res.usage.totalInputTokens)
        assertEquals(1500, callbackTokens)
        assertEquals(1, client.totalCalls)
        assertEquals(0, client.failedCalls)
        assertEquals(0.00015, res.usage.estimatedCostUsd, 1e-9)
    }

    @Test
    fun `decide automatically chunks more than 1000 questions across multiple requests`() = runTest {
        val chunkSizes = mutableListOf<Int>()
        val transport = DecisionTransport { reqJson ->
            val questionsObj = json.parseToJsonElement(reqJson).jsonObject["questions"]!!.jsonObject
            chunkSizes.add(questionsObj.size)
            val answers = buildJsonObject {
                questionsObj.keys.forEachIndexed { i, k ->
                    put(
                        k,
                        buildJsonObject {
                            put("type", "noul")
                            put("noul", 0.5 + ((i % 4) * 0.1))
                            put("confidence", 0.9)
                        }
                    )
                }
            }
            val resp = buildJsonObject {
                put("answers", answers)
                put(
                    "usage",
                    buildJsonObject {
                        put("total_input_tokens", 100)
                        put("total_output_tokens", 0)
                        put("total_tokens", 100)
                    }
                )
            }
            json.encodeToString(JsonObject.serializer(), resp)
        }

        val client = DecisionClient(transport = transport)
        val manyQuestions = LinkedHashMap<String, DecisionQuestion>(1050)
        repeat(1050) { idx ->
            manyQuestions["q_$idx"] = noulQuestion("Question $idx?")
        }

        val res = client.decide("Shared context", manyQuestions)
        assertTrue(res.ok)
        assertEquals(listOf(1000, 50), chunkSizes)
        assertEquals(1050, res.answers.size)
        assertEquals(200, res.usage.totalInputTokens)
        assertEquals(2, client.totalCalls)
        assertEquals(200, client.totalInputTokens)
    }

    @Test
    fun `decide handles API error payload in failOpen and strict modes`() = runTest {
        val errorTransport = DecisionTransport {
            """{"error":{"code":"INVALID_ARGUMENT","message":"Too many questions"}}"""
        }

        val openClient = DecisionClient(transport = errorTransport, failOpen = true)
        val res = openClient.decide("Ctx", mapOf("q1" to noulQuestion("Valid?")))
        assertFalse(res.ok)
        assertTrue(res.error?.contains("INVALID_ARGUMENT") == true)
        assertEquals(1, openClient.failedCalls)
        assertTrue(openClient.lastError?.contains("Too many questions") == true)

        val strictClient = DecisionClient(transport = errorTransport, failOpen = false)
        try {
            strictClient.decide("Ctx", mapOf("q1" to noulQuestion("Valid?")))
            fail("Expected exception when failOpen=false")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Too many questions") == true)
        }
        assertEquals(1, strictClient.failedCalls)
    }

    @Test
    fun `KoogDecisionTransport sends x-goog-api-key header and surfaces HTTP error body`() = runTest {
        var capturedBaseUrl: String? = null
        var capturedHeaders: Map<String, String> = emptyMap()
        var capturedPath: String? = null
        var shouldThrowHttp = false

        val fakeFactory = object : KoogHttpClient.Factory {
            override fun create(
                clientName: String,
                baseUrl: String,
                headers: Map<String, String>,
                queryParameters: Map<String, String>,
                requestTimeoutMillis: Long,
                connectTimeoutMillis: Long,
                socketTimeoutMillis: Long,
                json: Json
            ): KoogHttpClient {
                capturedBaseUrl = baseUrl
                capturedHeaders = headers
                return object : KoogHttpClient {
                    override val clientName: String = clientName
                    override suspend fun <R : Any> get(
                        path: String,
                        responseType: KClass<R>,
                        parameters: Map<String, String>,
                        headers: Map<String, String>
                    ): R = error("unused")

                    @Suppress("UNCHECKED_CAST")
                    override suspend fun <T : Any, R : Any> post(
                        path: String,
                        requestBody: T,
                        requestBodyType: KClass<T>,
                        responseType: KClass<R>,
                        parameters: Map<String, String>,
                        headers: Map<String, String>
                    ): R {
                        capturedPath = path
                        if (shouldThrowHttp) {
                            throw KoogHttpClientException(
                                clientName = clientName,
                                statusCode = 400,
                                errorBody = """{"error":{"code":"BAD_REQUEST","message":"Invalid input block"}}"""
                            )
                        }
                        return """{"answers":{"q1":{"type":"noul","noul":0.91,"confidence":0.95}},"usage":{"total_input_tokens":42}}""" as R
                    }

                    override fun <T : Any, R : Any, O : Any> sse(
                        path: String,
                        requestBody: T,
                        requestBodyType: KClass<T>,
                        dataFilter: (String?) -> Boolean,
                        decodeStreamingResponse: (String) -> R,
                        processStreamingChunk: (R) -> O?,
                        parameters: Map<String, String>,
                        headers: Map<String, String>
                    ): Flow<O> = emptyFlow()

                    override fun <T : Any> lines(
                        path: String,
                        requestBody: T,
                        requestBodyType: KClass<T>,
                        parameters: Map<String, String>,
                        headers: Map<String, String>
                    ): Flow<String> = emptyFlow()

                    override fun close() {}
                }
            }
        }

        val client = DecisionClient(apiKey = "test-gemini-key", httpClientFactory = fakeFactory)
        val okRes = client.decide("Hello", mapOf("q1" to noulQuestion("Is clinical?")))
        assertTrue(okRes.ok)
        assertEquals(DecisionClient.DECISIONS_BASE_URL, capturedBaseUrl)
        assertEquals(DecisionClient.DECISIONS_PATH, capturedPath)
        assertEquals("test-gemini-key", capturedHeaders["x-goog-api-key"])
        assertEquals(0.91, okRes.getNoul("q1")!!.noul, 1e-6)

        shouldThrowHttp = true
        val errRes = client.decide("Hello", mapOf("q1" to noulQuestion("Is clinical?")))
        assertFalse(errRes.ok)
        assertTrue(errRes.error?.contains("400") == true)
        assertTrue(errRes.error?.contains("Invalid input block") == true)
        client.close()
    }
}
