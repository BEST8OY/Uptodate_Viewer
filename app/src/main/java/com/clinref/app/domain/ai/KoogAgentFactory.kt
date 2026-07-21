package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
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
 * - OpenRouter: prompt-executor-openrouter-client:1.0.0 ✓
 * - Ollama: prompt-executor-ollama-client:1.0.0 ✓
 * - Mistral/DeepSeek: not available at stable version yet
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
                chatHistoryProvider = this@KoogAgentFactory.chatHistoryProvider
                windowSize(50)
                filterMessages { msg -> msg is ai.koog.prompt.message.Message.User || msg is ai.koog.prompt.message.Message.Assistant }
            }

            handleEvents {
                onToolCallStarting { eventContext ->
                    val callId = eventContext.toolCallId ?: ""
                    val argsStr = eventContext.toolArgs.toString()
                    accumulator.onToolCallStarting(callId, argsStr)
                    streamingManager.onToolCallStarting(eventContext.toolName, argsStr)
                }

                onToolCallCompleted { eventContext ->
                    val callId = eventContext.toolCallId ?: ""
                    val resultText = eventContext.toolResult?.toString() ?: ""
                    val success = eventContext.toolResult != null
                    accumulator.onToolCallCompleted(callId, eventContext.toolName, resultText, success)
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
            appendLine("4. When citing sources, use this exact format, ONE CITATION PER LINE:")
            appendLine("   Topic: <topic title>, Section: <section title> (ID: <section id>)")
            appendLine("   You may include multiple citations, each on its own line. Every clinical answer must have at least one.")
            appendLine("5. For sections marked [WARNING], include the warning in your response.")
            appendLine("6. Never paraphrase complex formulas, or images (non-table graphics).")
            appendLine("7. Do not perform calculations across multiple sections.")
            appendLine("8. After reading the primary topic, ALWAYS call getRelatedTopics to check for additional relevant content.")
            appendLine("9. If related topics contain information that would strengthen your answer, read those sections too using getTopicOutline + getTopicSectionText. Read up to 2 related topics maximum.")
            appendLine("10. When section text contains a Topic link (e.g. [Aspirin](Topic-utd00100)) that is relevant to answering the question, follow that link: call getTopicOutline + getTopicSectionText on the linked topic to read its content. This is critical for drug links, cross-references, and linked conditions.")
            appendLine("11. When a topic references a table graphic (graphic_table), use getGraphicContent to read the table data. You may interpret and summarize table data to answer clinical questions.")
            appendLine("12. For non-table graphics (figures, algorithms, images), use getGraphicInfo to get metadata only. NEVER interpret visual content — reference the type and title only.")
            appendLine("13. Graphics types: graphic_table (readable via getGraphicContent), graphic_figure, graphic_algorithm, graphic_picture, graphic_movie, graphic_waveform, graphic_diagnosticimage (metadata only via getGraphicInfo).")
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
            AiProvider.OPENAI -> OpenAIModels.models.first()
            AiProvider.ANTHROPIC -> AnthropicModels.models.first()
            AiProvider.GOOGLE -> GoogleModels.models.first()
            AiProvider.OPENROUTER -> OpenRouterModels.models.first()
            else -> LLModel(
                provider = providerFor(config.provider),
                id = "llama3.2",
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
            AiProvider.OPENROUTER -> simpleOpenRouterExecutor(apiKey, httpClientFactory)
            AiProvider.OLLAMA -> {
                val baseUrl = config.baseUrl.ifBlank { "http://localhost:11434" }
                simpleOllamaAIExecutor(baseUrl = baseUrl, httpClientFactory = httpClientFactory)
            }
            // Mistral/DeepSeek: client modules not available at stable version yet
            else -> null
        }
    }

    private fun providerFor(provider: AiProvider): LLMProvider {
        return when (provider) {
            AiProvider.OPENAI -> LLMProvider.OpenAI
            AiProvider.ANTHROPIC -> LLMProvider.Anthropic
            AiProvider.GOOGLE -> LLMProvider.Google
            AiProvider.OPENROUTER -> LLMProvider.OpenRouter
            AiProvider.OLLAMA -> LLMProvider.Ollama
            // Mistral/DeepSeek: client modules not available at stable version yet
            else -> error("No LLMProvider mapping for $provider — executorFor() should have returned null first")
        }
    }

    private fun getStaticFallback(provider: AiProvider): List<String> = when (provider) {
        AiProvider.OPENAI -> OpenAIModels.models.map { it.id }
        AiProvider.ANTHROPIC -> AnthropicModels.models.map { it.id }
        AiProvider.GOOGLE -> GoogleModels.models.map { it.id }
        AiProvider.OPENROUTER -> OpenRouterModels.models.map { it.id }
        AiProvider.OLLAMA -> listOf("llama3.2", "mistral", "phi3")
        // Mistral/DeepSeek: not available at stable version yet
        else -> emptyList()
    }
}
