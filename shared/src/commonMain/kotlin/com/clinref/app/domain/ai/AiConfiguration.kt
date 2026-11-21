package com.clinref.app.domain.ai

import kotlinx.serialization.Serializable

@Serializable
data class DecisionSettings(
    val mode: DecisionMode = DecisionMode.OFF
) {
    fun toDecisionConfig(): DecisionConfig = DecisionConfig(
        enableRetrievalRerank = mode != DecisionMode.OFF,
        autoPrefetch = mode == DecisionMode.RERANK
    )
}

@Serializable
data class AiConfiguration(
    val provider: AiProvider = AiProvider.OPENAI,
    val baseUrl: String = "",
    val model: String = "",
    val providerSettings: ProviderSettings = ProviderSettings.OpenAI(),
    val isConfigured: Boolean = false,
    val decisionSettings: DecisionSettings = DecisionSettings()
) {
    // Convenience accessors for common settings
    val temperature: Float get() = providerSettings.temperature
    val maxTokens: Int get() = providerSettings.maxTokens
}
