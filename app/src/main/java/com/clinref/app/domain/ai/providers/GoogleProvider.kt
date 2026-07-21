package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.clinref.app.domain.ai.AiConfiguration

/**
 * Google/Gemini provider implementation.
 *
 * Models available:
 * - Gemini 2.5 Flash — fast, balanced (default)
 * - Gemini 2.5 Flash Lite — fastest, cheapest
 * - Gemini 2.5 Pro — advanced, complex tasks
 * - Gemini 3 Pro Preview — latest, reasoning with thinkingLevel
 * - Gemini 3 Flash Preview — fast with Pro-level intelligence
 *
 * Features:
 * - Thinking mode (Gemini 2.0: thinkingBudget, Gemini 3.0: thinkingLevel)
 * - Configurable timeouts
 * - Tool calling support
 *
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
                contextLength = 1_000_000  // Gemini 2.5 supports 1M context
            )
        }
        return GoogleModels.Gemini2_5Flash
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        if (apiKey.isBlank()) return null

        val settings = GoogleClientSettings(
            timeoutConfig = ConnectionTimeoutConfig(
                requestTimeoutMillis = 120_000L,   // 2 minutes for long generations
                connectTimeoutMillis = 30_000L,     // 30 seconds to connect
                socketTimeoutMillis = 120_000L      // 2 minutes for streaming
            )
        )

        val client = GoogleLLMClient(
            apiKey = apiKey,
            settings = settings,
            httpClientFactory = httpClientFactory
        )

        return MultiLLMPromptExecutor(client)
    }

    override fun getAvailableModels(): List<String> {
        return GoogleModels.models.map { it.id }
    }

    /**
     * Get available Gemini models with their characteristics.
     */
    fun getModelInfo(): List<GeminiModelInfo> = listOf(
        GeminiModelInfo(
            id = GoogleModels.Gemini2_5Flash.id,
            name = "Gemini 2.5 Flash",
            speed = "Medium",
            description = "Balanced speed and capability, supports thinking"
        ),
        GeminiModelInfo(
            id = GoogleModels.Gemini2_5FlashLite.id,
            name = "Gemini 2.5 Flash Lite",
            speed = "Fast",
            description = "Cost-efficient, high throughput"
        ),
        GeminiModelInfo(
            id = GoogleModels.Gemini2_5Pro.id,
            name = "Gemini 2.5 Pro",
            speed = "Slow",
            description = "Advanced capabilities for complex tasks"
        ),
        GeminiModelInfo(
            id = GoogleModels.Gemini3_Pro_Preview.id,
            name = "Gemini 3 Pro Preview",
            speed = "Slow",
            description = "Latest reasoning with thinkingLevel (not thinkingBudget)"
        ),
        GeminiModelInfo(
            id = GoogleModels.Gemini3_Flash_Preview.id,
            name = "Gemini 3 Flash Preview",
            speed = "Fast",
            description = "Pro-level intelligence at Flash speed"
        )
    )

    data class GeminiModelInfo(
        val id: String,
        val name: String,
        val speed: String,
        val description: String
    )
}
