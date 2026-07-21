package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.http.client.okhttp.OkHttpKoogHttpClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import com.clinref.app.data.ai.RoomChatHistoryProvider
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.secure.SecurePreferences
import com.clinref.app.domain.ai.providers.AiProviderFactory
import com.clinref.app.domain.ai.providers.AnthropicProvider
import com.clinref.app.domain.ai.providers.GoogleProvider
import com.clinref.app.domain.ai.providers.OllamaProvider
import com.clinref.app.domain.ai.providers.MistralAIProvider
import com.clinref.app.domain.ai.providers.OpenAIProvider
import com.clinref.app.domain.ai.providers.OpenRouterProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates Koog AIAgent instances for each conversation turn.
 *
 * AIAgent is single-use — calling .run() twice throws. So we create
 * a fresh agent per sendMessage() call.
 *
 * Provider support (modular — each provider is a separate class):
 * - Google/Gemini: GoogleProvider ✓
 * - OpenAI: OpenAIProvider ✓
 * - Anthropic: AnthropicProvider ✓
 * - Mistral AI: MistralAIProvider ✓
 * - OpenRouter: OpenRouterProvider ✓
 * - Ollama: OllamaProvider ✓
 */
@Singleton
class KoogAgentFactory @Inject constructor(
    private val securePreferences: SecurePreferences,
    private val medicalDatabaseTools: MedicalDatabaseTools,
    private val safetyValidator: SafetyValidator,
    private val chatHistoryProvider: RoomChatHistoryProvider
) {

    private val httpClientFactory = OkHttpKoogHttpClient.Factory()

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

    suspend fun createAgent(
        config: AiConfiguration,
        conversationId: String,
        patientProfile: PatientProfile,
        streamingManager: StreamingManager
    ): AIAgent<String, String>? {
        val apiKey = securePreferences.getApiKey(config.provider)
        if (apiKey.isBlank() && config.provider != AiProvider.OLLAMA) return null

        val providerFactory = getProvider(config.provider) ?: return null
        val executor = providerFactory.createExecutor(config, apiKey) ?: return null
        val model = providerFactory.resolveModel(config)

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

                onLLMCallCompleted { eventContext ->
                    val metaInfo = eventContext.response?.metaInfo
                    if (metaInfo != null) {
                        streamingManager.onLlmCallCompleted(
                            promptTokens = metaInfo.inputTokensCount ?: 0,
                            completionTokens = metaInfo.outputTokensCount ?: 0,
                            totalTokens = metaInfo.totalTokensCount ?: 0
                        )
                    }
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
            appendLine("5. Never paraphrase complex formulas, or images (non-table graphics).")
            appendLine("6. Do not perform calculations across multiple sections.")
            appendLine("7. After reading the primary topic, ALWAYS call getRelatedTopics to check for additional relevant content.")
            appendLine("8. If related topics contain information that would strengthen your answer, read those sections too using getTopicOutline + getTopicSectionText. Read up to 2 related topics maximum.")
            appendLine("9. When section text contains a Topic link that is relevant, follow it: call getTopicOutline + getTopicSectionText.")
            appendLine("10. getTopicOutline returns sections AND graphics. For graphic_table, use getGraphicContent to read table data (you may interpret it). For other graphics, use getGraphicInfo for metadata only — never interpret visual content.")
            appendLine("LINKING:")
            appendLine("- Link to a topic using [text](Topic-id) ONLY when the user would benefit from reading that topic (e.g. related conditions, drug information, cross-references). Do NOT link for passing mentions.")
            appendLine("- Link to a graphic using [text](Graphic-id) ONLY when the graphic contains data relevant to the answer (e.g. dosing tables, diagnostic algorithms). Do NOT link for passing mentions.")
            appendLine("- Every clinical answer must have at least one citation (Rule 4). Citations are separate from topic/graphic links.")
        }
    }

    suspend fun getAvailableModels(provider: AiProvider, baseUrl: String = ""): List<String> {
        return getProvider(provider)?.getAvailableModels() ?: emptyList()
    }
}
