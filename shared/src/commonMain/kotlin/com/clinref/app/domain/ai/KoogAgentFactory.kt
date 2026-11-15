package com.clinref.app.domain.ai

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import com.clinref.app.domain.ai.providers.AiProviderFactory
import com.clinref.app.domain.ai.providers.AnthropicProvider
import com.clinref.app.domain.ai.providers.GoogleProvider
import com.clinref.app.domain.ai.providers.MistralAIProvider
import com.clinref.app.domain.ai.providers.OllamaProvider
import com.clinref.app.domain.ai.providers.OpenAIProvider
import com.clinref.app.domain.ai.providers.OpenRouterProvider
import com.clinref.shared.platform.PlatformLogger as Log
import com.clinref.shared.secure.SecurePreferences
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "KoogAgentFactory"

class KoogAgentFactory(
    private val securePreferences: SecurePreferences,
    private val secureLogger: SecureLogger? = null
) {

    private val httpClientFactory: ai.koog.http.client.KoogHttpClient.Factory = SafeKoogHttpClientFactory()
    private val executorCache = ConcurrentHashMap<String, PromptExecutor>()
    private val decisionClientCache = ConcurrentHashMap<Int, DecisionClient>()

    private val providers: Map<AiProvider, AiProviderFactory> by lazy {
        mapOf(
            AiProvider.GOOGLE to GoogleProvider(httpClientFactory),
            AiProvider.OPENAI to OpenAIProvider(httpClientFactory),
            AiProvider.MISTRAL to MistralAIProvider(httpClientFactory),
            AiProvider.ANTHROPIC to AnthropicProvider(httpClientFactory),
            AiProvider.OPENROUTER to OpenRouterProvider(httpClientFactory),
            AiProvider.OLLAMA to OllamaProvider(httpClientFactory)
        )
    }

    private fun getProvider(provider: AiProvider): AiProviderFactory? = providers[provider]

    suspend fun resolveRuntime(config: AiConfiguration): Triple<PromptExecutor, LLModel, LLMParams?>? {
        val apiKey = securePreferences.getApiKey(config.provider)
        if (apiKey.isBlank() && config.provider != AiProvider.OLLAMA) return null

        val providerFactory = getProvider(config.provider) ?: return null
        val cacheKey = "${config.provider}_${apiKey.hashCode()}_${config.baseUrl}"
        val executor = executorCache[cacheKey] ?: run {
            val created = providerFactory.createExecutor(config, apiKey) ?: return null
            executorCache[cacheKey] = created
            created
        }
        val model = providerFactory.resolveModel(config)
        val providerParams = providerFactory.createParams(config)
        return Triple(executor, model, providerParams)
    }

    /**
     * Resolves an optional [DecisionClient] when retrieval reranking is enabled and a
     * Google AI Studio API key is configured. Returns `null` when disabled or unconfigured
     * so primary clinical retrieval proceeds unimpeded (fail-open).
     */
    fun resolveDecisionClient(config: AiConfiguration): DecisionClient? {
        if (config.decisionSettings.mode == DecisionMode.OFF) return null
        val googleKey = securePreferences.getApiKey(AiProvider.GOOGLE).trim()
        if (googleKey.isEmpty()) {
            secureLogger?.log(
                SecureLogger.Level.WARN,
                "KoogAgentFactory",
                "Decisions reranking enabled but no Google Gemini API key is configured; running without Decisions API"
            )
            return null
        }
        val keyHash = googleKey.hashCode()
        return decisionClientCache.getOrPut(keyHash) {
            DecisionClient(
                apiKey = googleKey,
                httpClientFactory = httpClientFactory,
                failOpen = true
            )
        }
    }

    fun clearDecisionClients() {
        decisionClientCache.values.forEach { client ->
            try { client.close() } catch (_: Exception) {}
        }
        decisionClientCache.clear()
    }

    suspend fun testConnection(config: AiConfiguration, apiKeyOverride: String? = null): Result<Unit> {
        val apiKey = apiKeyOverride ?: securePreferences.getApiKey(config.provider)
        if (apiKey.isBlank() && config.provider != AiProvider.OLLAMA) {
            return Result.failure(IllegalStateException("API key is not configured for ${config.provider.displayName}"))
        }

        val providerFactory = getProvider(config.provider)
            ?: return Result.failure(IllegalStateException("Unsupported provider: ${config.provider.displayName}"))

        val cacheKey = "${config.provider}_${apiKey.hashCode()}_${config.baseUrl}"
        val executor = executorCache[cacheKey] ?: run {
            val created = providerFactory.createExecutor(config, apiKey)
                ?: return Result.failure(IllegalStateException("Failed to initialize client for ${config.provider.displayName}"))
            executorCache[cacheKey] = created
            created
        }
        val model = providerFactory.resolveModel(config)
        val providerParams = providerFactory.createParams(config)

        return try {
            val pingPrompt = prompt(id = "test-connection-ping", params = providerParams) {
                user("ping")
            }
            executor.execute(
                prompt = pingPrompt,
                model = model,
                tools = emptyList()
            )
            Result.success(Unit)
        } catch (e: Throwable) {
            val rootCause = e.cause ?: e
            Result.failure(rootCause)
        }
    }

    suspend fun getAvailableModels(provider: AiProvider, baseUrl: String = ""): List<String> {
        return getProvider(provider)?.getAvailableModels()?.distinct() ?: emptyList()
    }
}
