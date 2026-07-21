package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.clinref.app.domain.ai.AiConfiguration

/**
 * Google/Gemini provider implementation.
 *
 * Models: Gemini 2.5 Flash, Gemini 2.5 Pro, Gemini 3 Pro Preview, etc.
 * API: https://ai.google.dev/gemini-api/docs
 */
class GoogleProvider(
    private val httpClientFactory: OkHttpKoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        if (config.model.isNotBlank()) {
            return LLModel(
                provider = LLMProvider.Google,
                id = config.model,
                capabilities = listOf(
                    ai.koog.prompt.llm.LLMCapability.Completion,
                    ai.koog.prompt.llm.LLMCapability.Tools,
                    ai.koog.prompt.llm.LLMCapability.Temperature
                ),
                contextLength = 128_000
            )
        }
        return GoogleModels.Gemini2_5Flash
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        if (apiKey.isBlank()) return null
        return simpleGoogleAIExecutor(apiKey, httpClientFactory)
    }

    override fun getAvailableModels(): List<String> {
        return GoogleModels.models.map { it.id }
    }
}
