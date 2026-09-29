package com.clinref.app.domain.ai

import kotlinx.serialization.Serializable

@Serializable
data class AiConfiguration(
    val provider: AiProvider = AiProvider.OPENAI,
    val baseUrl: String = "",
    val model: String = "",
    val providerSettings: ProviderSettings = ProviderSettings.OpenAI(),
    val historyCompressionThreshold: Int = 8000,
    val isConfigured: Boolean = false
) {
    // Convenience accessors for common settings
    val temperature: Float get() = providerSettings.temperature
    val maxTokens: Int get() = providerSettings.maxTokens
}
