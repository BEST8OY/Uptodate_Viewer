package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.deepseek.DeepSeekModels
import ai.koog.prompt.executor.clients.mistralai.models.MistralAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import ai.koog.prompt.executor.llms.all.simpleMistralAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.clinref.app.data.ai.RoomChatHistoryProvider
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.secure.SecurePreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates Koog AIAgent instances for each conversation turn.
 *
 * AIAgent is single-use — calling .run() twice throws. So we create
 * a fresh agent per sendMessage() call.
 *
 * Provider support:
 * - OpenAI: prompt-executor-openai-client:1.0.0 ✓
 * - Anthropic: prompt-executor-anthropic-client:1.0.0 ✓
 * - Google: prompt-executor-google-client:1.0.0-beta ✓
 * - Mistral: prompt-executor-mistralai-client:1.0.0-beta ✓
 * - DeepSeek: prompt-executor-deepseek-client:1.0.0-beta ✓
 * - OpenRouter: prompt-executor-openrouter-client:1.0.0 ✓
 * - Ollama: prompt-executor-ollama-client:1.0.0 ✓
 */
@Singleton
class KoogAgentFactory @Inject constructor(
    private val securePreferences: SecurePreferences,
    private val medicalDatabaseTools: MedicalDatabaseTools,
    private val safetyValidator: SafetyValidator,
    private val chatHistoryProvider: RoomChatHistoryProvider
) {

    private val httpClientFactory = OkHttpKoogHttpClient.Factory()

    suspend fun createAgent(
        config: AiConfiguration,
        conversationId: String,
        patientProfile: PatientProfile,
        streamingManager: StreamingManager
    ): AIAgent<String, String>? {
        val apiKey = securePreferences.getApiKey(config.provider)
        if (apiKey.isBlank() && config.provider != AiProvider.OLLAMA) return null

        val executor = executorFor(config) ?: return null
        val model = resolveModel(config)

        val toolRegistry = ToolRegistry {
            tools(medicalDatabaseTools)
        }

        val accumulator = TurnContextAccumulator()

        return AIAgent(
            promptExecutor = executor,
            llmModel = model,
            systemPrompt = buildSystemPrompt(patientProfile),
            toolRegistry = toolRegistry,
            temperature = config.temperature.toDouble(),
            maxIterations = 25
        ) {
            install(ChatMemory) {
                chatHistoryProvider(this@KoogAgentFactory.chatHistoryProvider)
                windowSize(50)
                filterMessages { msg -> msg is ai.koog.prompt.message.Message.User || msg is ai.koog.prompt.message.Message.Assistant }
            }

            handleEvents {
                onToolCallStarting { eventContext ->
                    val argsStr = eventContext.toolArgs.toString()
                    accumulator.onToolCallStarting(eventContext.toolName, argsStr)
                    streamingManager.onToolCallStarting(eventContext.toolName, argsStr)
                }

                onToolCallCompleted { eventContext ->
                    val resultText = eventContext.toolResult?.toString() ?: ""
                    val success = eventContext.toolResult != null
                    accumulator.onToolCallCompleted(eventContext.toolName, resultText, success)
                    streamingManager.onToolCallCompleted(eventContext.toolName)
                    streamingManager.onWaitingForLlm()
                }

                onAgentCompleted { eventContext ->
                    val result = eventContext.result?.toString() ?: ""
                    val turnContext = accumulator.buildTurnContext(result)
                    val validation = safetyValidator.validate(turnContext)
                    streamingManager.onCompleted(result, validation)
                    accumulator.reset()
                }

                onAgentExecutionFailed { eventContext ->
                    streamingManager.onError(eventContext.error.message ?: "Unknown error")
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

    suspend fun getAvailableModels(provider: AiProvider, baseUrl: String = ""): List<String> {
        return getStaticFallback(provider)
    }

    private fun resolveModel(config: AiConfiguration): LLModel {
        if (config.model.isNotBlank()) {
            return LLModel(
                provider = providerFor(config.provider),
                id = config.model,
                capabilities = listOf(
                    ai.koog.prompt.llm.LLMCapability.Completion,
                    ai.koog.prompt.llm.LLMCapability.Tools,
                    ai.koog.prompt.llm.LLMCapability.Temperature
                ),
                contextLength = 128_000
            )
        }
        return when (config.provider) {
            AiProvider.OPENAI -> OpenAIModels.Chat.GPT4o
            AiProvider.ANTHROPIC -> AnthropicModels.Sonnet_4_5
            AiProvider.GOOGLE -> GoogleModels.Gemini2_5Flash
            AiProvider.MISTRAL -> MistralAIModels.Chat.MistralLarge21
            AiProvider.DEEPSEEK -> DeepSeekModels.DeepSeekV4Flash
            AiProvider.OPENROUTER -> OpenRouterModels.Chat.GPT4o
            else -> LLModel(
                provider = providerFor(config.provider),
                id = "gpt-4o",
                capabilities = listOf(
                    ai.koog.prompt.llm.LLMCapability.Completion,
                    ai.koog.prompt.llm.LLMCapability.Tools,
                    ai.koog.prompt.llm.LLMCapability.Temperature
                ),
                contextLength = 128_000
            )
        }
    }

    private suspend fun executorFor(config: AiConfiguration): PromptExecutor? {
        val apiKey = securePreferences.getApiKey(config.provider)
        return when (config.provider) {
            AiProvider.OPENAI -> simpleOpenAIExecutor(apiKey, httpClientFactory)
            AiProvider.ANTHROPIC -> simpleAnthropicExecutor(apiKey, httpClientFactory)
            AiProvider.GOOGLE -> simpleGoogleAIExecutor(apiKey, httpClientFactory)
            AiProvider.MISTRAL -> simpleMistralAIExecutor(apiKey, httpClientFactory)
            AiProvider.DEEPSEEK -> simpleDeepSeekExecutor(apiKey, httpClientFactory)
            AiProvider.OPENROUTER -> simpleOpenRouterExecutor(apiKey, httpClientFactory)
            AiProvider.OLLAMA -> simpleOllamaAIExecutor(httpClientFactory = httpClientFactory)
            else -> null
        }
    }

    private fun providerFor(provider: AiProvider): LLMProvider {
        return when (provider) {
            AiProvider.OPENAI -> LLMProvider.OpenAI
            AiProvider.ANTHROPIC -> LLMProvider.Anthropic
            AiProvider.GOOGLE -> LLMProvider.Google
            AiProvider.MISTRAL -> LLMProvider.Mistral
            AiProvider.DEEPSEEK -> LLMProvider.DeepSeek
            AiProvider.OPENROUTER -> LLMProvider.OpenRouter
            AiProvider.OLLAMA -> LLMProvider.Ollama
        }
    }

    private fun getStaticFallback(provider: AiProvider): List<String> = when (provider) {
        AiProvider.OPENAI -> OpenAIModels.models
            .filter { it.id.startsWith("gpt-") || it.id.startsWith("o") }
            .map { it.id }
        AiProvider.ANTHROPIC -> AnthropicModels.models.map { it.id }
        AiProvider.GOOGLE -> GoogleModels.models.map { it.id }
        AiProvider.MISTRAL -> MistralAIModels.models.map { it.id }
        AiProvider.DEEPSEEK -> DeepSeekModels.models.map { it.id }
        AiProvider.OPENROUTER -> OpenRouterModels.models.map { it.id }
        AiProvider.OLLAMA -> listOf("llama3.2", "mistral", "phi3")
    }
}
