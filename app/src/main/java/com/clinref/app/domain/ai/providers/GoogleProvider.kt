package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingLevel
import ai.koog.prompt.executor.llms.all.simpleGoogleExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.ProviderSettings

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
                contextLength = 1_000_000
            )
        }
        return GoogleModels.Gemini2_5Flash
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        if (apiKey.isBlank()) return null
        return simpleGoogleExecutor(apiKey, httpClientFactory)
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        val settings = config.providerSettings as? ProviderSettings.Google
            ?: return LLMParams(temperature = config.temperature.toDouble(), maxTokens = config.maxTokens)
        val temperature = config.temperature.toDouble()

        return GoogleParams(
            temperature = if (settings.topP != null) null else temperature,
            maxTokens = settings.maxTokens,
            topP = settings.topP,
            topK = settings.topK,
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
