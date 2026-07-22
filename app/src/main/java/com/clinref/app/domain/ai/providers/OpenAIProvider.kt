package com.clinref.app.domain.ai.providers

import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.ProviderSettings
import kotlin.math.roundToInt

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
                    ai.koog.prompt.llm.LLMCapability.Temperature,
                    ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Completions
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

    override fun createParams(config: AiConfiguration): LLMParams {
        val settings = config.providerSettings as? ProviderSettings.OpenAI
            ?: return LLMParams(temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0, maxTokens = config.maxTokens)
        val temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0

        return OpenAIChatParams(
            temperature = if (settings.topP != null) null else temperature,
            maxTokens = settings.maxTokens,
            topP = settings.topP,
            frequencyPenalty = settings.frequencyPenalty,
            presencePenalty = settings.presencePenalty,
            reasoningEffort = settings.reasoningEffort?.let {
                when (it.lowercase()) {
                    "low" -> ReasoningEffort.LOW
                    "medium" -> ReasoningEffort.MEDIUM
                    "high" -> ReasoningEffort.HIGH
                    "minimal" -> ReasoningEffort.MINIMAL
                    "none" -> ReasoningEffort.NONE
                    else -> null
                }
            },
            store = settings.store
        )
    }

    override fun getAvailableModels(): List<String> {
        return OpenAIModels.models.map { it.id }
    }
}
