package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.clinref.app.domain.ai.AiConfiguration

/**
 * OpenRouter provider implementation.
 *
 * Models: Access to multiple providers through a single API.
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
        return simpleOpenRouterExecutor(apiKey, httpClientFactory)
    }

    override fun getAvailableModels(): List<String> {
        return OpenRouterModels.models.map { it.id }
    }
}
