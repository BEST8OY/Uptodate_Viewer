package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.ollama.OllamaClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.secure.SecurePreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates Koog AIAgent instances for each conversation turn.
 *
 * AIAgent is single-use — calling .run() twice throws. So we create
 * a fresh agent per sendMessage() call. This is intentional and necessary.
 */
@Singleton
class KoogAgentFactory @Inject constructor(
    private val securePreferences: SecurePreferences,
    private val medicalDatabaseTools: MedicalDatabaseTools,
    private val safetyValidator: SafetyValidator
) {

    /**
     * Creates a fresh AIAgent for a single conversation turn.
     *
     * @param config Provider/model configuration from settings
     * @param conversationId Used as ChatMemory session ID for history isolation
     * @param patientProfile Injected into system prompt for clinical context
     * @param streamingManager Receives tool progress and completion events
     * @return A configured agent ready for .run(), or null if configuration is invalid
     */
    fun createAgent(
        config: AiConfiguration,
        conversationId: String,
        patientProfile: PatientProfile,
        streamingManager: StreamingManager
    ): AIAgent<String, String>? {
        val apiKey = securePreferences.getApiKey(config.provider)
        if (config.provider != AiProvider.OLLAMA && apiKey.isBlank()) return null

        val client = clientFor(config.provider, apiKey, config.baseUrl)
            ?: return null

        val resolvedModel = resolveModel(config)
        val model = LLModel(
            provider = providerFor(config.provider),
            id = resolvedModel,
            capabilities = listOf(LLMCapability.Tools, LLMCapability.Temperature),
            contextLength = 128_000
        )

        val toolRegistry = ToolRegistry {
            tools(medicalDatabaseTools)
        }

        val accumulator = TurnContextAccumulator()

        return AIAgent(
            promptExecutor = MultiLLMPromptExecutor(client),
            llmModel = model,
            systemPrompt = buildSystemPrompt(patientProfile),
            toolRegistry = toolRegistry,
            temperature = config.temperature.toDouble(),
            maxIterations = 25
        ) {
            handleEvents {
                onToolCallStarting { ctx ->
                    val argsStr = ctx.toolArgs.toString()
                    accumulator.onToolCallStarting(ctx.tool.name, argsStr)
                    streamingManager.onToolCallStarting(ctx.tool.name, argsStr)
                }

                onToolCallCompleted { ctx ->
                    val success = ctx.result != null
                    val resultText = ctx.result?.toString() ?: "Tool call failed"
                    accumulator.onToolCallCompleted(ctx.tool.name, resultText, success)
                    streamingManager.onToolCallCompleted(ctx.tool.name)
                    streamingManager.onWaitingForLlm()
                }

                onAgentCompleted { ctx ->
                    val result = ctx.result.orEmpty()
                    val turnContext = accumulator.buildTurnContext(result)
                    val validation = safetyValidator.validate(turnContext)
                    streamingManager.onCompleted(result, validation)
                    accumulator.reset()
                }

                onAgentExecutionFailed { ctx ->
                    streamingManager.onError(ctx.throwable.message ?: "Unknown error")
                    accumulator.reset()
                }
            }
        }
    }

    fun buildSystemPrompt(patientProfile: PatientProfile): String {
        val profileBlock = patientProfile.toSystemBlock()
        return buildString {
            appendLine("You are ClinRef AI, a clinical reference assistant. You answer medical questions by searching the available medical database, reading relevant sections, and citing your sources.")
            appendLine()
            if (profileBlock.isNotBlank()) {
                appendLine(profileBlock)
                appendLine()
            }
            appendLine("RULES:")
            appendLine("1. ALWAYS call searchTopics first to find relevant topics.")
            appendLine("2. ALWAYS call getTopicOutline to understand topic structure.")
            appendLine("3. ALWAYS call getTopicSectionText to read specific sections before answering.")
            appendLine("4. When citing sources, use this exact format for each citation:")
            appendLine("   Topic: <topic title>, Section: <section title> (ID: <section id>)")
            appendLine("   You may include multiple citations. Every clinical answer must have at least one.")
            appendLine("5. For sections marked [WARNING], include the warning in your response.")
            appendLine("6. Never paraphrase complex dosing tables, formulas, or images.")
            appendLine("7. Do not perform calculations across multiple sections.")
            appendLine("8. Use getRelatedTopics to suggest related content when relevant to the user's question.")
            appendLine("9. Use getGraphicInfo to describe what a graphic contains (type and title) but NEVER interpret visual content.")
            appendLine("10. Graphics types: graphic_table, graphic_figure, graphic_algorithm, graphic_picture, graphic_movie, graphic_waveform, graphic_diagnosticimage. Reference the type, not the visual content.")
        }
    }

    /**
     * Get available models for a provider.
     * Tries dynamic model list first (client.models()), falls back to static.
     */
    suspend fun getAvailableModels(provider: AiProvider, baseUrl: String = ""): List<String> {
        val apiKey = securePreferences.getApiKey(provider)
        if (provider != AiProvider.OLLAMA && apiKey.isBlank()) {
            return getStaticFallback(provider)
        }
        return try {
            val client = clientFor(provider, apiKey, baseUrl) ?: return getStaticFallback(provider)
            val models = client.models()
            if (models.isNotEmpty()) models else getStaticFallback(provider)
        } catch (_: Exception) {
            getStaticFallback(provider)
        }
    }

    private fun resolveModel(config: AiConfiguration): String {
        if (config.model.isNotBlank()) return config.model
        return when (config.provider) {
            AiProvider.OPENAI -> "gpt-4o"
            AiProvider.ANTHROPIC -> "claude-sonnet-4-5"
            AiProvider.GOOGLE -> "gemini-2.5-flash"
            AiProvider.DEEPSEEK -> "deepseek-v4-flash"
            AiProvider.OPENROUTER -> "gpt-4o"
            AiProvider.OLLAMA -> "llama3.2"
        }
    }

    private fun clientFor(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String
    ): LLMClient? {
        return when (provider) {
            AiProvider.OPENAI -> OpenAILLMClient(apiKey)
            AiProvider.ANTHROPIC -> AnthropicLLMClient(apiKey)
            AiProvider.GOOGLE -> GoogleLLMClient(apiKey)
            AiProvider.DEEPSEEK -> DeepSeekLLMClient(apiKey)
            AiProvider.OPENROUTER -> OpenRouterLLMClient(apiKey)
            AiProvider.OLLAMA -> OllamaClient(baseUrl.ifEmpty { "http://localhost:11434" })
        }
    }

    private fun providerFor(provider: AiProvider): LLMProvider {
        return when (provider) {
            AiProvider.OPENAI -> LLMProvider.OpenAI
            AiProvider.ANTHROPIC -> LLMProvider.Anthropic
            AiProvider.GOOGLE -> LLMProvider.Google
            AiProvider.DEEPSEEK -> LLMProvider.DeepSeek
            AiProvider.OPENROUTER -> LLMProvider.OpenAI
            AiProvider.OLLAMA -> LLMProvider.Ollama
        }
    }

    private fun getStaticFallback(provider: AiProvider): List<String> = when (provider) {
        AiProvider.OPENAI -> listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini")
        AiProvider.ANTHROPIC -> listOf("claude-opus-4-1", "claude-sonnet-4-5", "claude-haiku-4")
        AiProvider.GOOGLE -> listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash")
        AiProvider.DEEPSEEK -> listOf("deepseek-v4-flash", "deepseek-v3")
        AiProvider.OPENROUTER -> listOf("gpt-4o", "claude-sonnet-4-5", "gemini-2.5-pro")
        AiProvider.OLLAMA -> listOf("llama3.2", "mistral", "phi3")
    }
}
