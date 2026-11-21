package com.clinref.app.domain.ai

import ai.koog.http.client.KoogHttpClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

class SafeKoogHttpClientFactoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sseSanitizesGoogleResponseLackingCandidates() = runTest {
        val mockDelegateClient: KoogHttpClient = mockk(relaxed = true)
        val mockFactory: KoogHttpClient.Factory = mockk {
            every {
                create(
                    any(), any(), any(), any(), any(), any(), any(), any()
                )
            } returns mockDelegateClient
        }

        val decodeSlot = slot<(String) -> String>()
        every {
            mockDelegateClient.sse<Any, String, String>(
                path = any(),
                requestBody = any(),
                requestBodyType = any(),
                dataFilter = any(),
                decodeStreamingResponse = capture(decodeSlot),
                processStreamingChunk = any(),
                parameters = any(),
                headers = any()
            )
        } answers {
            // Emulate receiving an SSE chunk containing only usageMetadata without candidates
            val rawGoogleChunkWithoutCandidates = """{"usageMetadata":{"promptTokenCount":42,"totalTokenCount":42}}"""
            val decoded = decodeSlot.captured.invoke(rawGoogleChunkWithoutCandidates)
            flowOf(decoded)
        }

        val factory = SafeKoogHttpClientFactory(mockFactory)
        val client = factory.create(
            clientName = "GoogleLLMClient",
            baseUrl = "https://generativelanguage.googleapis.com",
            headers = emptyMap(),
            queryParameters = emptyMap(),
            requestTimeoutMillis = 30_000L,
            connectTimeoutMillis = 10_000L,
            socketTimeoutMillis = 30_000L,
            json = json
        )

        val flow = client.sse<String, String, String>(
            path = "v1beta/models/gemini-2.5-flash:streamGenerateContent",
            requestBody = "{}",
            requestBodyType = String::class,
            dataFilter = { true },
            decodeStreamingResponse = { it },
            processStreamingChunk = { it },
            parameters = emptyMap(),
            headers = emptyMap()
        )

        val results = flow.toList()
        assertEquals(1, results.size)
        val sanitized = results.first()
        assertTrue(
            "Sanitized Google SSE chunk must contain 'candidates' field to prevent KG-858 SerializationException",
            sanitized.contains("\"candidates\"")
        )
    }

    @Test
    fun ssePreservesGoogleResponseWithExistingCandidates() = runTest {
        val mockDelegateClient: KoogHttpClient = mockk(relaxed = true)
        val mockFactory: KoogHttpClient.Factory = mockk {
            every {
                create(
                    any(), any(), any(), any(), any(), any(), any(), any()
                )
            } returns mockDelegateClient
        }

        val decodeSlot = slot<(String) -> String>()
        every {
            mockDelegateClient.sse<Any, String, String>(
                path = any(),
                requestBody = any(),
                requestBodyType = any(),
                dataFilter = any(),
                decodeStreamingResponse = capture(decodeSlot),
                processStreamingChunk = any(),
                parameters = any(),
                headers = any()
            )
        } answers {
            val rawGoogleChunkWithCandidates = """{"candidates":[{"content":{"parts":[{"text":"Hello"}]}}]}"""
            val decoded = decodeSlot.captured.invoke(rawGoogleChunkWithCandidates)
            flowOf(decoded)
        }

        val factory = SafeKoogHttpClientFactory(mockFactory)
        val client = factory.create(
            clientName = "GoogleLLMClient",
            baseUrl = "https://generativelanguage.googleapis.com",
            headers = emptyMap(),
            queryParameters = emptyMap(),
            requestTimeoutMillis = 30_000L,
            connectTimeoutMillis = 10_000L,
            socketTimeoutMillis = 30_000L,
            json = json
        )

        val flow = client.sse<String, String, String>(
            path = "v1beta/models/gemini-2.5-flash:streamGenerateContent",
            requestBody = "{}",
            requestBodyType = String::class,
            dataFilter = { true },
            decodeStreamingResponse = { it },
            processStreamingChunk = { it },
            parameters = emptyMap(),
            headers = emptyMap()
        )

        val results = flow.toList()
        assertEquals(1, results.size)
        assertTrue(results.first().contains("Hello"))
    }

    @Test
    fun ssePassesThroughNonGoogleSseUnmodified() = runTest {
        val mockDelegateClient: KoogHttpClient = mockk(relaxed = true)
        val mockFactory: KoogHttpClient.Factory = mockk {
            every {
                create(
                    any(), any(), any(), any(), any(), any(), any(), any()
                )
            } returns mockDelegateClient
        }

        val decodeSlot = slot<(String) -> String>()
        every {
            mockDelegateClient.sse<Any, String, String>(
                path = any(),
                requestBody = any(),
                requestBodyType = any(),
                dataFilter = any(),
                decodeStreamingResponse = capture(decodeSlot),
                processStreamingChunk = any(),
                parameters = any(),
                headers = any()
            )
        } answers {
            // Non-Google provider (e.g. OpenAI or Anthropic) payload without candidates
            val rawChunk = """{"id":"chatcmpl-123","choices":[{"delta":{"content":"Hi"}}]}"""
            val decoded = decodeSlot.captured.invoke(rawChunk)
            flowOf(decoded)
        }

        val factory = SafeKoogHttpClientFactory(mockFactory)
        val client = factory.create(
            clientName = "OpenAILLMClient",
            baseUrl = "https://api.openai.com",
            headers = emptyMap(),
            queryParameters = emptyMap(),
            requestTimeoutMillis = 30_000L,
            connectTimeoutMillis = 10_000L,
            socketTimeoutMillis = 30_000L,
            json = json
        )

        val flow = client.sse<String, String, String>(
            path = "v1/chat/completions",
            requestBody = "{}",
            requestBodyType = String::class,
            dataFilter = { true },
            decodeStreamingResponse = { it },
            processStreamingChunk = { it },
            parameters = emptyMap(),
            headers = emptyMap()
        )

        val results = flow.toList()
        assertEquals(1, results.size)
        assertTrue(
            "Non-Google SSE payloads must not be modified or injected with 'candidates'",
            !results.first().contains("\"candidates\"")
        )
    }
}
