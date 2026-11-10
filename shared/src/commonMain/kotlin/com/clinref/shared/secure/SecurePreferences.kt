package com.clinref.shared.secure

import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import kotlinx.coroutines.flow.StateFlow

/**
 * Multiplatform contract for secure API key and configuration storage.
 */
interface SecurePreferences {
    fun saveApiKey(provider: AiProvider, key: String)
    fun getApiKey(provider: AiProvider): String
    fun saveConfiguration(config: AiConfiguration)
    fun loadConfiguration(): AiConfiguration
    val configuration: StateFlow<AiConfiguration>
}
