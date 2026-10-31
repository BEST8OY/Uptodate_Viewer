package com.clinref.app.domain.ai.providers

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.ProviderSettings
import kotlin.math.roundToInt

/**
 * Anthropic provider implementation.
 *
 * Models: Claude 3.5 Sonnet, Claude 3 Opus, Claude 3 Haiku, etc.
 * API: https://docs.anthropic.com
 */
class AnthropicProvider(
    private val httpClientFactory: KoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        val modelId = config.model.trim().ifEmpty { AnthropicModels.models.first().id }
        val predefined = AnthropicModels.models.find { it.id.equals(modelId, ignoreCase = true) }
        val settings = config.providerSettings as? ProviderSettings.Anthropic
        val isThinking = settings?.enableThinking == true ||
            modelId.contains("3-7", ignoreCase = true) ||
            modelId.contains("3.7", ignoreCase = true) ||
            modelId.contains("thinking", ignoreCase = true)

        val baseCapabilities = predefined?.capabilities ?: listOf(
            ai.koog.prompt.llm.LLMCapability.Completion,
            ai.koog.prompt.llm.LLMCapability.Tools,
            ai.koog.prompt.llm.LLMCapability.Temperature
        )
        val capabilities = if (isThinking && ai.koog.prompt.llm.LLMCapability.Thinking !in baseCapabilities) {
            baseCapabilities + ai.koog.prompt.llm.LLMCapability.Thinking
        } else {
            baseCapabilities
        }

        val contextLength = predefined?.contextLength ?: 200_000L
        val model = LLModel(
            provider = LLMProvider.Anthropic,
            id = modelId,
            capabilities = capabilities,
            contextLength = contextLength
        )
        try {
            AnthropicModels.addCustomModel(model)
        } catch (_: Throwable) {}
        return model
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        val key = apiKey.trim()
        if (key.isBlank()) return null

        val settings = AnthropicClientSettings(
            timeoutConfig = ConnectionTimeoutConfig(
                requestTimeoutMillis = 120_000L,
                connectTimeoutMillis = 30_000L,
                socketTimeoutMillis = 120_000L
            )
        )

        val client = AnthropicLLMClient(
            apiKey = key,
            settings = settings,
            httpClientFactory = httpClientFactory
        )

        return MultiLLMPromptExecutor(client)
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        val settings = config.providerSettings as? ProviderSettings.Anthropic
        val isThinking = settings?.enableThinking == true
        // Anthropic requires temperature to be 1.0 when extended thinking is enabled
        val temperature = if (isThinking) 1.0 else (config.temperature.toDouble() * 100).roundToInt() / 100.0

        val thinkingConfig = if (isThinking) {
            val budget = settings.thinkingBudget ?: 2048
            AnthropicThinking.Enabled(budgetTokens = budget)
        } else null

        return AnthropicParams(
            temperature = temperature,
            maxTokens = settings?.maxTokens ?: config.maxTokens,
            thinking = thinkingConfig,
            cacheControl = AnthropicCacheControl.Default
        )
    }

    override fun getAvailableModels(): List<String> {
        return AnthropicModels.models.map { it.id }
    }
}
