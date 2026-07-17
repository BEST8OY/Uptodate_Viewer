package com.clinref.app.domain.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.reflect.ToolRegistry
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.ollama.OllamaClient
import ai.koog.prompt.executor.MultiLLMPromptExecutor
import com.clinref.app.data.MedicalDatabaseTools
import com.clinref.app.data.secure.SecurePreferences
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KoogAgentFactory @Inject constructor(
    private val securePreferences: SecurePreferences,
    private val medicalDatabaseTools: MedicalDatabaseTools,
    private val safetyValidator: SafetyValidator
) {
    fun createAgent(
        config: AiConfiguration,
        streamingManager: StreamingManager
    ): AIAgent<String, String> {
        val apiKey = securePreferences.getApiKey(config.provider)
        val client = buildClient(config.provider, apiKey, config.baseUrl)
        val executor = MultiLLMPromptExecutor(client)
        val toolRegistry = ToolRegistry { tools(medicalDatabaseTools) }

        return AIAgent(
            promptExecutor = executor,
            systemPrompt = buildSystemPrompt(PatientProfile()),
            llmModel = resolveModel(config),
            toolRegistry = toolRegistry
        ) {
            handleEvents {
                onToolCallStarting { ctx ->
                    streamingManager.onToolCallStarting(ctx.toolName, ctx.toolArgs.toString())
                }

                onAgentCompleted { ctx ->
                    val result = ctx.result.toString()
                    val toolCalls = extractToolCalls(ctx)
                    val citations = extractCitations(result)
                    val fetchedSections = extractFetchedSections(ctx)
                    val turnContext = SafetyValidator.TurnContext(
                        toolCalls = toolCalls,
                        answer = result,
                        citations = citations,
                        fetchedSections = fetchedSections
                    )
                    val validation = safetyValidator.validate(turnContext)
                    streamingManager.onCompleted(result, validation)
                }

                onLLMStreamingFailed { ctx ->
                    streamingManager.onError(ctx.error?.message ?: "Unknown error")
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
        }
    }

    suspend fun getAvailableModels(provider: AiProvider, baseUrl: String = ""): List<String> {
        return when (provider) {
            AiProvider.OPENAI, AiProvider.GOOGLE, AiProvider.DEEPSEEK, AiProvider.OPENROUTER -> {
                try {
                    val client = buildClient(provider, "test", baseUrl)
                    // Koog LLM clients may expose .models() for dynamic listing
                    getStaticFallback(provider)
                } catch (_: Exception) {
                    getStaticFallback(provider)
                }
            }
            AiProvider.ANTHROPIC, AiProvider.OLLAMA -> getStaticFallback(provider)
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

    private fun buildClient(
        provider: AiProvider,
        apiKey: String,
        baseUrl: String
    ): LLMClient = when (provider) {
        AiProvider.OPENAI -> OpenAILLMClient(apiKey)
        AiProvider.ANTHROPIC -> AnthropicLLMClient(apiKey)
        AiProvider.GOOGLE -> GoogleLLMClient(apiKey)
        AiProvider.DEEPSEEK -> DeepSeekLLMClient(apiKey)
        AiProvider.OPENROUTER -> OpenRouterLLMClient(apiKey)
        AiProvider.OLLAMA -> OllamaClient(baseUrl.ifEmpty { "http://localhost:11434" })
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

    private fun extractToolCalls(ctx: Any): List<SafetyValidator.ToolCallRecord> {
        // Extract tool call records from event context
        return try {
            val toolName = ctx::class.java.getMethod("getToolName").invoke(ctx)?.toString() ?: ""
            val toolArgs = ctx::class.java.getMethod("getToolArgs").invoke(ctx)?.toString() ?: ""
            listOf(
                SafetyValidator.ToolCallRecord(
                    toolName = toolName,
                    arguments = mapOf("raw" to toolArgs),
                    result = "",
                    success = true
                )
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractCitations(answer: String): List<SafetyValidator.Citation> {
        // Parse citations from the answer text
        // Citations typically appear as: [Topic Title > Section Title (section-id)]
        val citationRegex = Regex("""\[([^>]+)>\s*([^(]+)\(([^)]+)\)]""")
        return citationRegex.findAll(answer).map { match ->
            SafetyValidator.Citation(
                topicId = match.groupValues[3].trim(),
                topicTitle = match.groupValues[1].trim(),
                sectionId = match.groupValues[3].trim(),
                sectionTitle = match.groupValues[2].trim()
            )
        }.toList()
    }

    private fun extractFetchedSections(ctx: Any): List<SafetyValidator.FetchedSection> {
        // This would need to be populated from tool call results
        // For now return empty; the factory will be enhanced during integration
        return emptyList()
    }
}
