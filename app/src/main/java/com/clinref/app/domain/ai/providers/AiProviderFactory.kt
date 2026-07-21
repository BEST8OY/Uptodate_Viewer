package com.clinref.app.domain.ai.providers

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import com.clinref.app.domain.ai.AiConfiguration

/**
 * Interface for provider-specific AI configuration.
 * Each provider implements this to handle its own model resolution,
 * executor creation, and available models list.
 */
interface AiProviderFactory {

    /** Resolve the LLM model to use based on configuration */
    fun resolveModel(config: AiConfiguration): LLModel

    /** Create a prompt executor for this provider */
    suspend fun createExecutor(config: AiConfiguration, apiKey: String): PromptExecutor?

    /** Get list of available model IDs for this provider */
    fun getAvailableModels(): List<String>
}
