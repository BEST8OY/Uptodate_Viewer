package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.AiConfiguration
import kotlin.math.roundToInt

/**
 * Ollama provider implementation (local models).
 *
 * Models: llama3.2, mistral, phi3, etc.
 * API: http://localhost:11434
 */
class OllamaProvider(
    private val httpClientFactory: OkHttpKoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        val modelId = config.model.ifBlank { "llama3.2" }
        return LLModel(
            provider = LLMProvider.Ollama,
            id = modelId,
            capabilities = listOf(
                ai.koog.prompt.llm.LLMCapability.Completion,
                ai.koog.prompt.llm.LLMCapability.Tools,
                ai.koog.prompt.llm.LLMCapability.Temperature
            ),
            contextLength = 128_000
        )
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        val baseUrl = config.baseUrl.ifBlank { "http://localhost:11434" }
        return simpleOllamaAIExecutor(baseUrl = baseUrl, httpClientFactory = httpClientFactory)
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        return LLMParams(temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0, maxTokens = config.maxTokens)
    }

    override fun getAvailableModels(): List<String> {
        return listOf("llama3.2", "mistral", "phi3")
    }
}
