package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.clinref.app.domain.ai.AiConfiguration

/**
 * Anthropic provider implementation.
 *
 * Models: Claude 3.5 Sonnet, Claude 3 Opus, Claude 3 Haiku, etc.
 * API: https://docs.anthropic.com
 */
class AnthropicProvider(
    private val httpClientFactory: OkHttpKoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        if (config.model.isNotBlank()) {
            return LLModel(
                provider = LLMProvider.Anthropic,
                id = config.model,
                capabilities = listOf(
                    ai.koog.prompt.llm.LLMCapability.Completion,
                    ai.koog.prompt.llm.LLMCapability.Tools,
                    ai.koog.prompt.llm.LLMCapability.Temperature
                ),
                contextLength = 200_000
            )
        }
        return AnthropicModels.models.first()
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        if (apiKey.isBlank()) return null
        return simpleAnthropicExecutor(apiKey, httpClientFactory)
    }

    override fun getAvailableModels(): List<String> {
        return AnthropicModels.models.map { it.id }
    }
}
