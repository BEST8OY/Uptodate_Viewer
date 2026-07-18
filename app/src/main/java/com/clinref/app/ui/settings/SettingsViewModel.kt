package com.clinref.app.ui.settings

import ai.koog.agents.core.agent.AIAgent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.secure.SecurePreferences
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.StreamingManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val securePreferences: SecurePreferences,
    private val koogAgentFactory: KoogAgentFactory,
    private val reliabilityManager: ReliabilityManager
) : ViewModel() {

    private val _configuration = MutableStateFlow(AiConfiguration())
    val configuration: StateFlow<AiConfiguration> = _configuration.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    init {
        viewModelScope.launch {
            _configuration.value = securePreferences.configuration.first()
            val config = _configuration.value
            _apiKey.value = securePreferences.getApiKey(config.provider)
            loadModels(config.provider, config.baseUrl)
        }
    }

    fun updateProvider(provider: AiProvider) {
        _configuration.value = _configuration.value.copy(provider = provider)
        viewModelScope.launch {
            _apiKey.value = securePreferences.getApiKey(provider)
            loadModels(provider, _configuration.value.baseUrl)
        }
    }

    fun updateApiKey(key: String) {
        _apiKey.value = key
    }

    fun updateBaseUrl(url: String) {
        _configuration.value = _configuration.value.copy(baseUrl = url)
    }

    fun updateModel(model: String) {
        _configuration.value = _configuration.value.copy(model = model)
    }

    fun updateTemperature(temp: Float) {
        _configuration.value = _configuration.value.copy(temperature = temp)
    }

    fun updateMaxTokens(tokens: Int) {
        _configuration.value = _configuration.value.copy(maxTokens = tokens)
    }

    fun updateHistoryThreshold(threshold: Int) {
        _configuration.value = _configuration.value.copy(historyCompressionThreshold = threshold)
    }

    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = TestResult.Loading
            try {
                val config = _configuration.value
                securePreferences.saveApiKey(config.provider, _apiKey.value)

                val testConfig = config.copy(isConfigured = true)
                val testStreamingManager = StreamingManager()

                val agent: AIAgent<String, String>?
                try {
                    agent = koogAgentFactory.createAgent(
                        config = testConfig,
                        conversationId = "test-connection",
                        patientProfile = PatientProfile(),
                        streamingManager = testStreamingManager
                    )
                } catch (e: Exception) {
                    val rootCause = e.cause ?: e
                    _testResult.value = TestResult.Error(
                        "Failed to create agent: ${rootCause.message ?: rootCause.javaClass.simpleName}"
                    )
                    return@launch
                }

                if (agent == null) {
                    val msg = when (config.provider) {
                        com.clinref.app.domain.ai.AiProvider.DEEPSEEK,
                        com.clinref.app.domain.ai.AiProvider.OPENROUTER ->
                            "${config.provider.displayName} is not yet supported in this build. Use OpenAI, Anthropic, Google, or Ollama."
                        else -> "Could not create agent. Check your API key and settings."
                    }
                    _testResult.value = TestResult.Error(msg)
                    return@launch
                }

                val result = try {
                    reliabilityManager.runWithTimeout(timeoutMs = 15_000L) {
                        agent.run("Say 'Connection successful' in exactly those words.")
                    }
                } catch (e: Exception) {
                    val rootCause = e.cause ?: e
                    _testResult.value = TestResult.Error(
                        "Request failed: ${rootCause.message ?: rootCause.javaClass.simpleName}"
                    )
                    return@launch
                }

                _testResult.value = if (result.contains("Connection successful", ignoreCase = true)) {
                    TestResult.Success
                } else {
                    TestResult.Error("Unexpected response: ${result.take(200)}")
                }
            } catch (e: Exception) {
                val rootCause = e.cause ?: e
                _testResult.value = TestResult.Error(
                    "Connection failed: ${rootCause.message ?: rootCause.javaClass.simpleName}"
                )
            }
        }
    }

    fun save() {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val config = _configuration.value.copy(isConfigured = true)
                securePreferences.saveApiKey(config.provider, _apiKey.value)
                securePreferences.saveConfiguration(config)
                _testResult.value = null
            } finally {
                _isSaving.value = false
            }
        }
    }

    private suspend fun loadModels(provider: AiProvider, baseUrl: String) {
        _availableModels.value = koogAgentFactory.getAvailableModels(provider, baseUrl)
    }

    sealed class TestResult {
        data object Loading : TestResult()
        data object Success : TestResult()
        data class Error(val message: String) : TestResult()
    }
}
