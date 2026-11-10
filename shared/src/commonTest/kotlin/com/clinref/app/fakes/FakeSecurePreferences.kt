package com.clinref.app.fakes

import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import com.clinref.shared.secure.SecurePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSecurePreferences(
    initialConfig: AiConfiguration = AiConfiguration()
) : SecurePreferences {

    private val keys = mutableMapOf<AiProvider, String>()
    private val _config = MutableStateFlow(initialConfig)
    override val configuration: StateFlow<AiConfiguration> = _config.asStateFlow()

    override fun saveApiKey(provider: AiProvider, key: String) {
        keys[provider] = key
    }

    override fun getApiKey(provider: AiProvider): String {
        return keys[provider] ?: ""
    }

    override fun saveConfiguration(config: AiConfiguration) {
        _config.value = config
    }

    override fun loadConfiguration(): AiConfiguration {
        return _config.value
    }
}
