package com.clinref.app.domain.ai.providers

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
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
 * Supports custom user-typed models (e.g., "nvidia/nemotron-3.5-super-vl:free",
 * "anthropic/claude-3.7-sonnet", etc.) as well as predefined models.
 * API: https://openrouter.ai/docs
 */
class OpenRouterProvider(
    private val httpClientFactory: KoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        val modelId = config.model.trim().ifEmpty { DEFAULT_MODEL }
        val model = LLModel(
            provider = LLMProvider.OpenRouter,
            id = modelId,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Tools,
                LLMCapability.Temperature
            ),
            contextLength = 128_000
        )
        try {
            OpenRouterModels.addCustomModel(model)
        } catch (_: Throwable) {
            // Ignore if already registered
        }
        return model
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        val key = apiKey.trim()
        if (key.isBlank()) return null

        val rawBase = config.baseUrl.trim().trimEnd('/')
        val (baseUrl, completionsPath) = when {
            rawBase.isBlank() -> "https://openrouter.ai" to "api/v1/chat/completions"
            rawBase.endsWith("/api/v1") -> rawBase.removeSuffix("/api/v1") to "api/v1/chat/completions"
            else -> rawBase to "api/v1/chat/completions"
        }

        val settings = OpenRouterClientSettings(
            baseUrl = baseUrl,
            chatCompletionsPath = completionsPath,
            timeoutConfig = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(
                requestTimeoutMillis = 120_000L,
                connectTimeoutMillis = 30_000L,
                socketTimeoutMillis = 120_000L
            )
        )

        val client = OpenRouterLLMClient(
            apiKey = key,
            settings = settings,
            httpClientFactory = httpClientFactory
        )

        return MultiLLMPromptExecutor(client)
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        val settings = config.providerSettings as? ProviderSettings.OpenRouter
            ?: return LLMParams(
                temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0,
                maxTokens = config.maxTokens
            )
        val temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0

        return OpenRouterParams(
            temperature = temperature,
            maxTokens = settings.maxTokens,
            frequencyPenalty = settings.frequencyPenalty,
            presencePenalty = settings.presencePenalty,
            repetitionPenalty = settings.repetitionPenalty,
            minP = settings.minP,
            transforms = settings.transforms
        )
    }

    override fun getAvailableModels(): List<String> {
        val popular = listOf(
            DEFAULT_MODEL,
            "google/gemini-2.0-flash-exp:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "anthropic/claude-3.7-sonnet",
            "anthropic/claude-3.5-sonnet",
            "openai/gpt-4o",
            "deepseek/deepseek-r1"
        )
        val builtin = OpenRouterModels.models.map { it.id }
        return (popular + builtin).distinct()
    }

    companion object {
        const val DEFAULT_MODEL = "nvidia/nemotron-3.5-super-vl:free"
    }
}
