package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.ProviderSettings
import kotlin.math.roundToInt

/**
 * OpenRouter provider implementation.
 *
 * Routes requests to multiple LLM providers through a single API.
 * Models: Claude, GPT, Gemini, Llama, Mistral, DeepSeek, etc.
 * API: https://openrouter.ai/docs
 */
class OpenRouterProvider(
    private val httpClientFactory: OkHttpKoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        if (config.model.isNotBlank()) {
            return LLModel(
                provider = LLMProvider.OpenRouter,
                id = config.model,
                capabilities = listOf(
                    ai.koog.prompt.llm.LLMCapability.Completion,
                    ai.koog.prompt.llm.LLMCapability.Tools,
                    ai.koog.prompt.llm.LLMCapability.Temperature
                ),
                contextLength = 128_000
            )
        }
        return OpenRouterModels.models.first()
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        if (apiKey.isBlank()) return null

        val settings = OpenRouterClientSettings(
            timeoutConfig = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(
                requestTimeoutMillis = 120_000L,
                connectTimeoutMillis = 30_000L,
                socketTimeoutMillis = 120_000L
            )
        )

        val client = OpenRouterLLMClient(
            apiKey = apiKey,
            settings = settings,
            httpClientFactory = httpClientFactory
        )

        return MultiLLMPromptExecutor(client)
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        val settings = config.providerSettings as? ProviderSettings.OpenRouter
            ?: return LLMParams(temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0, maxTokens = config.maxTokens)
        val temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0

        return OpenRouterParams(
            temperature = if (settings.topP != null) null else temperature,
            maxTokens = settings.maxTokens,
            topP = settings.topP,
            topK = settings.topK,
            frequencyPenalty = settings.frequencyPenalty,
            presencePenalty = settings.presencePenalty,
            repetitionPenalty = settings.repetitionPenalty,
            minP = settings.minP,
            transforms = settings.transforms
        )
    }

    override fun getAvailableModels(): List<String> {
        return OpenRouterModels.models.map { it.id }
    }
}
