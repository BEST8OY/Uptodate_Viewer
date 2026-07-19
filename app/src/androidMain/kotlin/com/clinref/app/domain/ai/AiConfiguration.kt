package com.clinref.app.domain.ai

import kotlinx.serialization.Serializable

@Serializable
data class AiConfiguration(
    val provider: AiProvider = AiProvider.OPENAI,
    val baseUrl: String = "",
    val model: String = "",
    val temperature: Float = 0.3f,
    val maxTokens: Int = 4096,
    val historyCompressionThreshold: Int = 8000,
    val isConfigured: Boolean = false
)
