package com.clinref.app.domain.ai.providers

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.ProviderSettings
import kotlin.math.roundToInt

class OpenAIProvider(
    private val httpClientFactory: KoogHttpClient.Factory
) : AiProviderFactory {

    override fun resolveModel(config: AiConfiguration): LLModel {
        val modelId = config.model.trim().ifEmpty { OpenAIModels.models.first().id }
        val predefined = OpenAIModels.models.find { it.id.equals(modelId, ignoreCase = true) }
        val settings = config.providerSettings as? ProviderSettings.OpenAI
        val isReasoningModel = (settings?.reasoningEffort != null && settings.reasoningEffort.lowercase() !in listOf("none", "")) ||
            modelId.startsWith("o1", ignoreCase = true) ||
            modelId.startsWith("o3", ignoreCase = true) ||
            modelId.startsWith("o4", ignoreCase = true) ||
            modelId.contains("gpt-5", ignoreCase = true)

        val baseCapabilities = predefined?.capabilities ?: listOf(
            ai.koog.prompt.llm.LLMCapability.Completion,
            ai.koog.prompt.llm.LLMCapability.Tools,
            ai.koog.prompt.llm.LLMCapability.Temperature,
            ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Completions
        )
        val capabilities = if (isReasoningModel && ai.koog.prompt.llm.LLMCapability.Thinking !in baseCapabilities) {
            baseCapabilities + ai.koog.prompt.llm.LLMCapability.Thinking
        } else {
            baseCapabilities
        }

        val contextLength = predefined?.contextLength ?: 128_000L
        val model = LLModel(
            provider = LLMProvider.OpenAI,
            id = modelId,
            capabilities = capabilities,
            contextLength = contextLength
        )
        try {
            OpenAIModels.addCustomModel(model)
        } catch (_: Throwable) {}
        return model
    }

    override suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor? {
        val key = apiKey.trim()
        if (key.isBlank()) return null

        val settings = OpenAIClientSettings(
            timeoutConfig = ConnectionTimeoutConfig(
                requestTimeoutMillis = 120_000L,
                connectTimeoutMillis = 30_000L,
                socketTimeoutMillis = 120_000L
            )
        )

        val client = OpenAILLMClient(
            apiKey = key,
            settings = settings,
            httpClientFactory = httpClientFactory
        )

        return MultiLLMPromptExecutor(client)
    }

    override fun createParams(config: AiConfiguration): LLMParams {
        val settings = config.providerSettings as? ProviderSettings.OpenAI
            ?: return LLMParams(temperature = (config.temperature.toDouble() * 100).roundToInt() / 100.0, maxTokens = config.maxTokens)
        val hasReasoning = settings.reasoningEffort != null && settings.reasoningEffort.lowercase() !in listOf("none", "")
        val temperature = if (hasReasoning) null else (config.temperature.toDouble() * 100).roundToInt() / 100.0

        return OpenAIChatParams(
            temperature = temperature,
            maxTokens = settings.maxTokens,
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
