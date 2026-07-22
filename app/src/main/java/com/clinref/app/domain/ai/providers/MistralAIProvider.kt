package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.mistralai.MistralAIModels
import ai.koog.prompt.executor.clients.mistralai.MistralAIParams
import ai.koog.prompt.executor.llms.all.simpleMistralAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.ProviderSettings

class MistralAIProvider(
    private val httpClientFactory: OkHttpKoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        if (config.model.isNotBlank()) {
            return LLModel(
                provider = LLMProvider.MistralAI,
                id = config.model,
                capabilities = listOf(
                    ai.koog.prompt.llm.LLMCapability.Completion,
                    ai.koog.prompt.llm.LLMCapability.Tools,
                    ai.koog.prompt.llm.LLMCapability.Temperature
                ),
                contextLength = 128_000
            )
        }
        return MistralAIModels.models.first()
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        if (apiKey.isBlank()) return null
        return simpleMistralAIExecutor(apiKey, httpClientFactory)
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        val settings = config.providerSettings as? ProviderSettings.MistralAI
            ?: return LLMParams(temperature = config.temperature.toDouble(), maxTokens = config.maxTokens)
        val temperature = config.temperature.toDouble()

        return MistralAIParams(
            temperature = if (settings.topP != null) null else temperature,
            maxTokens = settings.maxTokens,
            topP = settings.topP,
            frequencyPenalty = settings.frequencyPenalty,
            presencePenalty = settings.presencePenalty,
            safePrompt = settings.safePrompt
        )
    }

    override fun getAvailableModels(): List<String> {
        return MistralAIModels.models.map { it.id }
    }
}
