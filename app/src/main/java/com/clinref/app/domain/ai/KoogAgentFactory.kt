package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.handleEvents
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
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
 * a fresh agent per sendMessage() call.
 *
 * NOTE: Only OpenAI and Anthropic clients are confirmed available in
 * koog-agents:1.0.0. Google/DeepSeek/OpenRouter/Ollama may require
 * separate dependency artifacts or have different package paths.
 * They will be enabled once their exact artifact coordinates are verified.
 */
@Singleton
class KoogAgentFactory @Inject constructor(
    private val securePreferences: SecurePreferences,
    private val medicalDatabaseTools: MedicalDatabaseTools,
    private val safetyValidator: SafetyValidator
) {

    fun createAgent(
        config: AiConfiguration,
        conversationId: String,
        patientProfile: PatientProfile,
        streamingManager: StreamingManager
    ): AIAgent<String, String>? {
        val apiKey = securePreferences.getApiKey(config.provider)
        if (apiKey.isBlank()) return null

        val client = clientFor(config.provider, apiKey) ?: return null

        val model = resolveModel(config)

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
                onToolCallStarting { eventContext ->
                    val argsStr = eventContext.toolArgs.toString()
                    accumulator.onToolCallStarting(eventContext.toolName, argsStr)
                    streamingManager.onToolCallStarting(eventContext.toolName, argsStr)
                }

                onToolCallCompleted { eventContext ->
                    val resultText = eventContext.result?.toString() ?: "Tool call failed"
                    accumulator.onToolCallCompleted(eventContext.toolName, resultText, true)
                    streamingManager.onToolCallCompleted(eventContext.toolName)
                    streamingManager.onWaitingForLlm()
                }

                onAgentCompleted { eventContext ->
                    // TODO: Verify exact property name for result on agent completion context.
                    // The event type AgentCompletedEvent has a 'result' field.
                    // The handler context property name needs IDE verification.
                    val result = eventContext.toString() // Placeholder — replace with actual property
                    val turnContext = accumulator.buildTurnContext(result)
                    val validation = safetyValidator.validate(turnContext)
                    streamingManager.onCompleted(result, validation)
                    accumulator.reset()
                }

                onAgentExecutionFailed { eventContext ->
                    streamingManager.onError("Agent execution failed")
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
        // TODO: Implement dynamic model listing once client.models() return type is verified.
        // The quickstart docs don't show a .models() example; it may not exist on all clients.
        return getStaticFallback(provider)
    }

    private fun resolveModel(config: AiConfiguration): LLModel {
        if (config.model.isNotBlank()) {
            return LLModel(
                provider = providerFor(config.provider),
                id = config.model,
                capabilities = listOf(),
                contextLength = 128_000
            )
        }
        return when (config.provider) {
            AiProvider.OPENAI -> OpenAIModels.Chat.GPT4o
            AiProvider.ANTHROPIC -> AnthropicModels.Opus_4_1
            // TODO: Add Google/DeepSeek/OpenRouter/Ollama model refs once their
            // client artifacts are confirmed available in koog-agents:1.0.0.
            else -> LLModel(
                provider = providerFor(config.provider),
                id = "gpt-4o",
                capabilities = listOf(),
                contextLength = 128_000
            )
        }
    }

    private fun clientFor(provider: AiProvider, apiKey: String): LLMClient? {
        return when (provider) {
            AiProvider.OPENAI -> OpenAILLMClient(apiKey)
            AiProvider.ANTHROPIC -> AnthropicLLMClient(apiKey)
            // TODO: Google/DeepSeek/OpenRouter/Ollama clients need separate
            // dependency artifacts verified. For now, only OpenAI and Anthropic work.
            else -> null
        }
    }

    private fun providerFor(provider: AiProvider): LLMProvider {
        return when (provider) {
            AiProvider.OPENAI -> LLMProvider.OpenAI
            AiProvider.ANTHROPIC -> LLMProvider.Anthropic
            else -> LLMProvider.OpenAI
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
