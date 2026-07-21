package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.clinref.app.domain.ai.AiConfiguration

/**
 * Mistral AI provider implementation.
 *
 * Models: Mistral Large, Mistral Small, Codestral, Pixtral, etc.
 * API: https://docs.mistral.ai/api
 */
class MistralAIProvider(
    private val httpClientFactory: OkHttpKoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        if (config.model.isNotBlank()) {
            return LLModel(
                provider = LLMProvider.MistralAI,
                id = config.model,
                capabilities = listOf(
                    ai.koog.prompt.llm.LLMCapability.Completion,
                    ai.koog.prompt.llm.LLMCapability.Tools,
                    ai.koog.prompt.llm.LLMCapability.Temperature
                ),
                contextLength = 128_000
            )
        }
        return MistralAIModels.models.first()
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        if (apiKey.isBlank()) return null

        val settings = MistralAIClientSettings(
            timeoutConfig = ConnectionTimeoutConfig(
                requestTimeoutMillis = 120_000L,
                connectTimeoutMillis = 30_000L,
                socketTimeoutMillis = 120_000L
            )
        )

        val client = MistralAILLMClient(
            apiKey = apiKey,
            settings = settings,
            httpClientFactory = httpClientFactory
        )

        return MultiLLMPromptExecutor(client)
    }

    override fun getAvailableModels(): List<String> {
        return MistralAIModels.models.map { it.id }
    }
}
