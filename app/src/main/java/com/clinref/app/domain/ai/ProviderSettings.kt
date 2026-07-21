package com.clinref.app.domain.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Provider-specific settings. Each provider has its own settings class
 * with relevant configuration options.
 */
@Serializable
sealed class ProviderSettings {
    /** Common settings shared by all providers */
    abstract val temperature: Float
    abstract val maxTokens: Int

    @Serializable
    @SerialName("Google")
    data class Google(
        override val temperature: Float = 0.7f,
        override val maxTokens: Int = 8192,
        val includeThoughts: Boolean = false,
        val thinkingBudget: Int? = null,        // Gemini 2.0: token limit for reasoning
        val thinkingLevel: String? = null,      // Gemini 3.0: "low" or "high"
        val topP: Double? = null,
        val topK: Int? = null
    ) : ProviderSettings()

    @Serializable
    @SerialName("OpenAI")
    data class OpenAI(
        override val temperature: Float = 1.0f,
        override val maxTokens: Int = 16384,
        val topP: Double? = null,
        val frequencyPenalty: Double? = null,
        val presencePenalty: Double? = null
    ) : ProviderSettings()

    @Serializable
    @SerialName("Anthropic")
    data class Anthropic(
        override val temperature: Float = 1.0f,
        override val maxTokens: Int = 8192,
        val topP: Double? = null,
        val topK: Int? = null
    ) : ProviderSettings()

    @Serializable
    @SerialName("OpenRouter")
    data class OpenRouter(
        override val temperature: Float = 1.0f,
        override val maxTokens: Int = 8192,
        val topP: Double? = null
    ) : ProviderSettings()

    @Serializable
    @SerialName("Ollama")
    data class Ollama(
        override val temperature: Float = 0.8f,
        override val maxTokens: Int = 4096,
        val topP: Double? = null,
        val topK: Int? = null,
        val numCtx: Int? = null                 // Context window size
    ) : ProviderSettings()
}

/**
 * Default settings for each provider.
 */
fun defaultSettingsForProvider(provider: AiProvider): ProviderSettings = when (provider) {
    AiProvider.GOOGLE -> ProviderSettings.Google()
    AiProvider.OPENAI -> ProviderSettings.OpenAI()
    AiProvider.ANTHROPIC -> ProviderSettings.Anthropic()
    AiProvider.OPENROUTER -> ProviderSettings.OpenRouter()
    AiProvider.OLLAMA -> ProviderSettings.Ollama()
}
