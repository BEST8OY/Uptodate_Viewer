package com.clinref.app.domain.ai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.reflect.KClass

/**
 * Decorating [KoogHttpClient.Factory] that wraps [OkHttpKoogHttpClient] instances
 * to ensure JSON requests carry an explicit `Content-Type: application/json` header.
 *
 * In Koog 1.2.0, [OkHttpKoogHttpClient] defaults String request bodies to `text/plain`
 * if no `Content-Type` is passed in per-request headers. Strict backends (such as
 * Mistral AI's FastAPI/Pydantic gateway) reject `text/plain` payloads with:
 * `422: Input should be a valid dictionary or object to extract fields from`.
 */
class SafeKoogHttpClientFactory(
    private val delegate: KoogHttpClient.Factory = OkHttpKoogHttpClient.Factory()
) : KoogHttpClient.Factory {

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
        val mergedHeaders = if (headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
            headers
        } else {
            headers + ("Content-Type" to "application/json")
        }

        val client = delegate.create(
            clientName = clientName,
            baseUrl = baseUrl,
            headers = mergedHeaders,
            queryParameters = queryParameters,
            requestTimeoutMillis = requestTimeoutMillis,
            connectTimeoutMillis = connectTimeoutMillis,
            socketTimeoutMillis = socketTimeoutMillis,
            json = json
        )
        return SafeKoogHttpClient(client, json)
    }

    private class SafeKoogHttpClient(
        private val delegate: KoogHttpClient,
        private val json: Json
    ) : KoogHttpClient by delegate {

        override suspend fun <R : Any> get(
            path: String,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>
        ): R {
            val fixedHeaders = if (headers.keys.any { it.equals("Accept", ignoreCase = true) }) {
                headers
            } else {
                headers + ("Accept" to "application/json")
            }
            return delegate.get(path, responseType, parameters, fixedHeaders)
        }

        override suspend fun <T : Any, R : Any> post(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            responseType: KClass<R>,
            parameters: Map<String, String>,
            headers: Map<String, String>
        ): R {
            val withContentType = ensureJsonContentType(headers, requestBody)
            val fixedHeaders = if (withContentType.keys.any { it.equals("Accept", ignoreCase = true) }) {
                withContentType
            } else {
                withContentType + ("Accept" to "application/json")
            }
            return delegate.post(path, requestBody, requestBodyType, responseType, parameters, fixedHeaders)
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
        ): Flow<O> {
            val fixedHeaders = ensureJsonContentType(headers, requestBody)
            val isGoogleStream = path.contains("streamGenerateContent")
            val wrappedDecode: (String) -> R = if (isGoogleStream) {
                { rawJson ->
                    val sanitized = sanitizeGoogleSsePayload(rawJson)
                    decodeStreamingResponse(sanitized)
                }
            } else {
                decodeStreamingResponse
            }
            return delegate.sse(
                path,
                requestBody,
                requestBodyType,
                dataFilter,
                wrappedDecode,
                processStreamingChunk,
                parameters,
                fixedHeaders
            )
        }

        /**
         * Upstream workaround for Koog issue KG-858 / pending PR #2114 ("fix(prompt): Handle Gemini safety behavior").
         *
         * In Koog 1.2.0 - 1.3.0, [ai.koog.prompt.executor.clients.google.models.GoogleResponse] declares
         * `val candidates: List<GoogleCandidate>` without a default value (`= emptyList()`). When Google Gemini
         * emits SSE chunks containing only `usageMetadata` or safety ratings (`promptFeedback`) without candidates,
         * kotlinx.serialization throws:
         * `SerializationException: Field 'candidates' is required for type with serial name '...GoogleResponse', but it was missing at path: $`.
         *
         * Pending upstream PR #2114 fixes this by adding default `= emptyList()`, but it is currently unmerged.
         * We intercept incoming Google SSE event JSON strings and inject `"candidates": []` if absent from a root JSON object.
         */
        private fun sanitizeGoogleSsePayload(rawJson: String): String {
            val trimmed = rawJson.trim()
            if (!trimmed.startsWith("{") || !trimmed.endsWith("}") || trimmed.contains("\"candidates\"")) {
                return rawJson
            }
            return try {
                val element = json.parseToJsonElement(trimmed)
                if (element is JsonObject && !element.containsKey("candidates")) {
                    val patched = JsonObject(element + ("candidates" to JsonArray(emptyList())))
                    patched.toString()
                } else {
                    rawJson
                }
            } catch (_: Throwable) {
                rawJson
            }
        }

        override fun <T : Any> lines(
            path: String,
            requestBody: T,
            requestBodyType: KClass<T>,
            parameters: Map<String, String>,
            headers: Map<String, String>
        ): Flow<String> {
            val fixedHeaders = ensureJsonContentType(headers, requestBody)
            return delegate.lines(path, requestBody, requestBodyType, parameters, fixedHeaders)
        }

        private fun ensureJsonContentType(headers: Map<String, String>, body: Any?): Map<String, String> {
            if (headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
                return headers
            }
            val isJson = when (body) {
                is String -> body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
                else -> true
            }
            return if (isJson) {
                headers + ("Content-Type" to "application/json")
            } else {
                headers
            }
        }
    }
}
