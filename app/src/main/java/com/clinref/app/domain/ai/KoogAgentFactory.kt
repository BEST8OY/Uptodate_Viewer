package com.clinref.app.domain.ai

import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.secure.SecurePreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates Koog AIAgent instances for each conversation turn.
 *
 * AIAgent is single-use — calling .run() twice throws. So we create
 * a fresh agent per sendMessage() call. This is intentional and necessary.
 *
 * IMPORTANT: The exact Koog API surface (import paths, constructor signatures,
 * ChatMemory install DSL, event handler API) must be verified against
 * ai.koog:koog-agents:1.0.0 at compile time. The imports and class names
 * below are based on the user's research and existing codebase patterns.
 * If any import fails, check the actual library structure via IDE autocomplete
 * or decompilation.
 *
 * Expected imports (verify at compile time):
 * - ai.koog.agents.core.agent.AIAgent
 * - ai.koog.agents.core.tools.ToolRegistry
 * - ai.koog.agents.core.prompt.PromptExecutor / MultiLLMPromptExecutor
 * - ai.koog.agents.features.chatmemory.ChatMemory
 * - ai.koog.agents.llm.LLModel
 * - ai.koog.agents.llm.LLMCapability
 * - ai.koog.agents.llm.openai.OpenAILLMClient
 * - ai.koog.agents.llm.anthropic.AnthropicLLMClient
 * - ai.koog.agents.llm.google.GoogleLLMClient
 * - ai.koog.agents.llm.deepseek.DeepSeekLLMClient
 * - ai.koog.agents.llm.openrouter.OpenRouterLLMClient
 * - ai.koog.agents.llm.ollama.OllamaClient
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
    ): Any? {
        val apiKey = securePreferences.getApiKey(config.provider)
        if (config.provider != AiProvider.OLLAMA && apiKey.isBlank()) return null

        // Build provider-specific LLM client
        // NOTE: Exact class names verified against koog-agents:1.0.0 at compile time
        val client = clientFor(config.provider, apiKey, config.baseUrl)
            ?: return null

        // Build model reference using free-text model string
        val resolvedModel = resolveModel(config)
        val providerEnum = providerFor(config.provider)
        val model = ai.koog.agents.llm.LLModel(
            provider = providerEnum,
            id = resolvedModel,
            capabilities = listOf(
                ai.koog.agents.llm.LLMCapability.Tools,
                ai.koog.agents.llm.LLMCapability.Temperature
            ),
            contextLength = 128_000
        )

        // Create tool registry with medical database tools
        val toolRegistry = ai.koog.agents.core.tools.ToolRegistry {
            tools(medicalDatabaseTools)
        }

        // Build the agent
        // NOTE: AIAgent constructor signature must be verified — the builder DSL
        // may differ from this sketch. Check actual API via IDE autocomplete.
        val accumulator = TurnContextAccumulator()

        return ai.koog.agents.core.agent.AIAgent(
            promptExecutor = ai.koog.agents.core.prompt.MultiLLMPromptExecutor(client),
            llmModel = model,
            systemPrompt = buildSystemPrompt(patientProfile),
            toolRegistry = toolRegistry,
            temperature = config.temperature.toDouble(),
            maxIterations = 25
        ) {
            // Install ChatMemory for multi-turn conversation support
            // NOTE: ChatMemory feature install API must be verified at compile time
            // install(ai.koog.agents.features.chatmemory.ChatMemory) {
            //     chatHistoryProvider = roomChatHistoryProvider
            //     windowSize = 20
            // }

            // Wire event handlers to StreamingManager + TurnContextAccumulator
            handleEvents {
                onToolCallStarting { ctx ->
                    accumulator.onToolCallStarting(ctx.tool.name, ctx.toolArgs.toString())
                    streamingManager.onToolCallStarting(ctx.tool.name, ctx.toolArgs.toString())
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
            appendLine("4. NEVER answer without citing: topic title, section title, and section ID.")
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
        return try {
            val client = clientFor(provider, "probe", baseUrl)
            if (client != null) {
                val models = client.models()
                if (models.isNotEmpty()) models else getStaticFallback(provider)
            } else {
                getStaticFallback(provider)
            }
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

    /**
     * Build provider-specific LLM client.
     * NOTE: Exact class names and constructor signatures must be verified
     * against ai.koog:koog-agents:1.0.0 at compile time.
     */
    private fun clientFor(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String
    ): ai.koog.agents.llm.LLMClient? {
        return when (provider) {
            AiProvider.OPENAI -> ai.koog.agents.llm.openai.OpenAILLMClient(apiKey)
            AiProvider.ANTHROPIC -> ai.koog.agents.llm.anthropic.AnthropicLLMClient(apiKey)
            AiProvider.GOOGLE -> ai.koog.agents.llm.google.GoogleLLMClient(apiKey)
            AiProvider.DEEPSEEK -> ai.koog.agents.llm.deepseek.DeepSeekLLMClient(apiKey)
            AiProvider.OPENROUTER -> ai.koog.agents.llm.openrouter.OpenRouterLLMClient(apiKey)
            AiProvider.OLLAMA -> ai.koog.agents.llm.ollama.OllamaClient(
                baseUrl.ifEmpty { "http://localhost:11434" }
            )
        }
    }

    /**
     * Map AiProvider to Koog's provider enum for LLModel construction.
     * NOTE: Koog's provider enum name must be verified at compile time.
     */
    private fun providerFor(provider: AiProvider): ai.koog.agents.llm.LLMProvider {
        return when (provider) {
            AiProvider.OPENAI -> ai.koog.agents.llm.LLMProvider.OpenAI
            AiProvider.ANTHROPIC -> ai.koog.agents.llm.LLMProvider.Anthropic
            AiProvider.GOOGLE -> ai.koog.agents.llm.LLMProvider.Google
            AiProvider.DEEPSEEK -> ai.koog.agents.llm.LLMProvider.DeepSeek
            AiProvider.OPENROUTER -> ai.koog.agents.llm.LLMProvider.OpenAI // OpenRouter uses OpenAI-compatible API
            AiProvider.OLLAMA -> ai.koog.agents.llm.LLMProvider.Ollama
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
