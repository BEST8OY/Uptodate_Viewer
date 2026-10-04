package com.clinref.app.domain.ai.providers

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.ProviderSettings
import kotlin.math.roundToInt

/**
 * Ollama provider implementation (local models).
 *
 * Connects via Ollama's native OpenAI-compatible /v1 endpoint without bloated umbrella dependencies.
 * Models: llama3.2, mistral, phi3, etc.
 * API: http://localhost:11434/v1
 */
class OllamaProvider(
    private val httpClientFactory: KoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        val modelId = config.model.ifBlank { "llama3.2" }
        val settings = config.providerSettings as? ProviderSettings.Ollama
        val contextLength = settings?.numCtx ?: 4096
        return LLModel(
            provider = LLMProvider.Ollama,
            id = modelId,
            capabilities = listOf(
                ai.koog.prompt.llm.LLMCapability.Completion,
                ai.koog.prompt.llm.LLMCapability.Tools,
                ai.koog.prompt.llm.LLMCapability.Temperature,
                ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Completions
            ),
            contextLength = contextLength.toLong()
        )
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        val baseUrl = config.baseUrl.trim().removeSuffix("/").removeSuffix("/v1").ifBlank { "http://localhost:11434" }

        val settings = OpenAIClientSettings(
            baseUrl = baseUrl,
            timeoutConfig = ConnectionTimeoutConfig(
                requestTimeoutMillis = 180_000L,
                connectTimeoutMillis = 30_000L,
                socketTimeoutMillis = 180_000L
            )
        )

        val client = OpenAILLMClient(
            apiKey = apiKey.ifBlank { "ollama" },
            settings = settings,
            httpClientFactory = httpClientFactory
        )

        return MultiLLMPromptExecutor(
            mapOf(
                LLMProvider.Ollama to client,
                LLMProvider.OpenAI to client
            )
        )
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        return LLMParams(
            temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0,
            maxTokens = config.maxTokens
        )
    }

    override fun getAvailableModels(): List<String> {
        return listOf("llama3.2", "mistral", "phi3")
    }
}
