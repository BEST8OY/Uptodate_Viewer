package com.clinref.app.domain.ai.providers

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.ProviderSettings
import kotlin.math.roundToInt

class GoogleProvider(
    private val httpClientFactory: KoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        val modelId = config.model.trim().ifEmpty { GoogleModels.Gemini2_5Flash.id }
        val predefined = GoogleModels.models.find { it.id.equals(modelId, ignoreCase = true) }
        val settings = config.providerSettings as? ProviderSettings.Google
        val isThinkingModel = settings?.includeThoughts == true ||
            modelId.contains("thinking", ignoreCase = true) ||
            modelId.contains("2.5", ignoreCase = true) ||
            modelId.contains("3", ignoreCase = true)

        val baseCapabilities = predefined?.capabilities ?: listOf(
            ai.koog.prompt.llm.LLMCapability.Completion,
            ai.koog.prompt.llm.LLMCapability.Tools,
            ai.koog.prompt.llm.LLMCapability.Temperature
        )
        val capabilities = if (isThinkingModel && ai.koog.prompt.llm.LLMCapability.Thinking !in baseCapabilities) {
            baseCapabilities + ai.koog.prompt.llm.LLMCapability.Thinking
        } else {
            baseCapabilities
        }

        val contextLength = predefined?.contextLength ?: 1_000_000L
        val model = LLModel(
            provider = LLMProvider.Google,
            id = modelId,
            capabilities = capabilities,
            contextLength = contextLength
        )
        try {
            GoogleModels.addCustomModel(model)
        } catch (_: Throwable) {}
        return model
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        val key = apiKey.trim()
        if (key.isBlank()) return null

        val settings = GoogleClientSettings(
            timeoutConfig = ConnectionTimeoutConfig(
                requestTimeoutMillis = 120_000L,
                connectTimeoutMillis = 30_000L,
                socketTimeoutMillis = 120_000L
            )
        )

        val client = GoogleLLMClient(
            apiKey = key,
            settings = settings,
            httpClientFactory = httpClientFactory
        )

        return MultiLLMPromptExecutor(client)
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        val settings = config.providerSettings as? ProviderSettings.Google
            ?: return LLMParams(temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0, maxTokens = config.maxTokens)
        val temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0

        return GoogleParams(
            temperature = temperature,
            maxTokens = settings.maxTokens,
            thinkingConfig = if (settings.includeThoughts) {
                GoogleThinkingConfig(
                    includeThoughts = true,
                    thinkingBudget = settings.thinkingBudget,
                    thinkingLevel = settings.thinkingLevel?.let {
                        when (it.lowercase()) {
                            "low" -> GoogleThinkingLevel.LOW
                            "high" -> GoogleThinkingLevel.HIGH
                            else -> null
                        }
                    }
                )
            } else null
        )
    }

    override fun getAvailableModels(): List<String> {
        return GoogleModels.models.map { it.id }
    }
}
