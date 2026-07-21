package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.clinref.app.domain.ai.AiConfiguration

/**
 * OpenAI provider implementation.
 *
 * Models: GPT-4o, GPT-4o-mini, o1, o3, etc.
 * API: https://platform.openai.com/docs
 */
class OpenAIProvider(
    private val httpClientFactory: OkHttpKoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        if (config.model.isNotBlank()) {
            return LLModel(
                provider = LLMProvider.OpenAI,
                id = config.model,
                capabilities = listOf(
                    ai.koog.prompt.llm.LLMCapability.Completion,
                    ai.koog.prompt.llm.LLMCapability.Tools,
                    ai.koog.prompt.llm.LLMCapability.Temperature
                ),
                contextLength = 128_000
            )
        }
        return OpenAIModels.models.first()
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        if (apiKey.isBlank()) return null
        return simpleOpenAIExecutor(apiKey, httpClientFactory)
    }

    override fun getAvailableModels(): List<String> {
        return OpenAIModels.models.map { it.id }
    }
}
