package com.clinref.app.domain.ai.providers

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
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
 * Mistral AI provider implementation using OpenAI-compatible client.
 *
 * Connects to Mistral's OpenAI-compatible /v1 endpoint (https://api.mistral.ai/v1)
 * via [OpenAILLMClient] for maximum stability and unified schema handling.
 */
class MistralAIProvider(
    private val httpClientFactory: KoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        val modelId = config.model.ifBlank { "mistral-large-latest" }
        return LLModel(
            provider = LLMProvider.MistralAI,
            id = modelId,
            capabilities = listOf(
                ai.koog.prompt.llm.LLMCapability.Completion,
                ai.koog.prompt.llm.LLMCapability.Tools,
                ai.koog.prompt.llm.LLMCapability.Temperature,
                ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Completions
            ),
            contextLength = 128_000
        )
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        if (apiKey.isBlank()) return null

        val baseUrl = config.baseUrl.trim().removeSuffix("/").removeSuffix("/v1").ifBlank { "https://api.mistral.ai" }

        val settings = OpenAIClientSettings(
            baseUrl = baseUrl,
            timeoutConfig = ConnectionTimeoutConfig(
                requestTimeoutMillis = 120_000L,
                connectTimeoutMillis = 30_000L,
                socketTimeoutMillis = 120_000L
            )
        )

        val client = OpenAILLMClient(
            apiKey = apiKey,
            settings = settings,
            httpClientFactory = httpClientFactory
        )

        return MultiLLMPromptExecutor(
            mapOf(
                LLMProvider.MistralAI to client,
                LLMProvider.OpenAI to client
            )
        )
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        val settings = config.providerSettings as? ProviderSettings.MistralAI
            ?: return LLMParams(
                temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0,
                maxTokens = config.maxTokens
            )
        val temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0

        return OpenAIChatParams(
            temperature = if (settings.topP != null) null else temperature,
            maxTokens = settings.maxTokens,
            topP = settings.topP,
            frequencyPenalty = settings.frequencyPenalty,
            presencePenalty = settings.presencePenalty
        )
    }

    override fun getAvailableModels(): List<String> {
        return listOf(
            "mistral-large-latest",
            "mistral-small-latest",
            "codestral-latest",
            "open-mistral-nemo",
            "ministral-8b-latest",
            "ministral-3b-latest",
            "open-mixtral-8x7b",
            "open-mixtral-8x22b"
        )
    }
}
