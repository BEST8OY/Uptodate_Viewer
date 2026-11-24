package com.clinref.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import com.clinref.app.domain.ai.DecisionMode
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.defaultSettingsForProvider
import com.clinref.shared.secure.SecurePreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val securePreferences: SecurePreferences,
    private val koogAgentFactory: KoogAgentFactory,
    private val reliabilityManager: ReliabilityManager = ReliabilityManager()
) : ViewModel() {

    private val _configuration = MutableStateFlow(AiConfiguration())
    val configuration: StateFlow<AiConfiguration> = _configuration.asStateFlow()

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _decisionApiKey = MutableStateFlow("")
    val decisionApiKey: StateFlow<String> = _decisionApiKey.asStateFlow()

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private var reloadJob: Job? = null
    private var loadModelsJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        reloadJob?.cancel()
        loadModelsJob?.cancel()
        reloadJob = viewModelScope.launch {
            _configuration.value = securePreferences.configuration.first()
            val config = _configuration.value
            _apiKey.value = securePreferences.getApiKey(config.provider)
            _decisionApiKey.value = securePreferences.getApiKey(AiProvider.GOOGLE)
            loadModels(config.provider, config.baseUrl)
            _testResult.value = null
        }
    }

    fun updateProvider(provider: AiProvider) {
        val defaultModel = when (provider) {
            AiProvider.OPENROUTER -> com.clinref.app.domain.ai.providers.OpenRouterProvider.DEFAULT_MODEL
            AiProvider.GOOGLE -> "gemini-2.5-flash"
            AiProvider.OPENAI -> "gpt-4o"
            AiProvider.ANTHROPIC -> "claude-3-5-sonnet-latest"
            AiProvider.MISTRAL -> "open-mistral-nemo"
            AiProvider.OLLAMA -> "llama3.2"
        }
        _configuration.value = _configuration.value.copy(
            provider = provider,
            baseUrl = "",
            model = defaultModel,
            providerSettings = defaultSettingsForProvider(provider)
        )
        reloadJob?.cancel()
        loadModelsJob?.cancel()
        loadModelsJob = viewModelScope.launch {
            _apiKey.value = securePreferences.getApiKey(provider)
            loadModels(provider, "")
        }
    }

    fun updateApiKey(key: String) {
        _apiKey.value = key
        if (_configuration.value.provider == AiProvider.GOOGLE) {
            _decisionApiKey.value = key
        }
    }

    fun updateDecisionMode(mode: DecisionMode) {
        val current = _configuration.value
        _configuration.value = current.copy(
            decisionSettings = current.decisionSettings.copy(mode = mode)
        )
    }

    fun updateDecisionApiKey(key: String) {
        _decisionApiKey.value = key
        if (_configuration.value.provider == AiProvider.GOOGLE) {
            _apiKey.value = key
        }
    }

    fun updateBaseUrl(url: String) {
        _configuration.value = _configuration.value.copy(baseUrl = url)
    }

    fun updateModel(model: String) {
        _configuration.value = _configuration.value.copy(model = model)
    }

    private fun updateCommonParams(temperature: Float? = null, maxTokens: Int? = null) {
        val current = _configuration.value
        _configuration.value = current.copy(
            providerSettings = current.providerSettings.withCommonParams(
                temperature = temperature,
                maxTokens = maxTokens
            )
        )
    }

    fun updateTemperature(temp: Float) = updateCommonParams(temperature = temp)

    fun updateMaxTokens(tokens: Int) = updateCommonParams(maxTokens = tokens)

    fun updateProviderSettings(settings: com.clinref.app.domain.ai.ProviderSettings) {
        _configuration.value = _configuration.value.copy(providerSettings = settings)
    }

    fun testConnection() {
        viewModelScope.launch {
            _testResult.value = TestResult.Loading
            try {
                val config = _configuration.value
                val testConfig = config.copy(isConfigured = true)
                val result = reliabilityManager.runWithTimeout(timeoutMs = 15_000L) {
                    koogAgentFactory.testConnection(testConfig, apiKeyOverride = _apiKey.value)
                }

                _testResult.value = result.fold(
                    onSuccess = { TestResult.Success },
                    onFailure = { err ->
                        val rootCause = err.cause ?: err
                        TestResult.Error("Connection failed: ${rootCause.message ?: rootCause::class.simpleName}")
                    }
                )
            } catch (e: Throwable) {
                val rootCause = e.cause ?: e
                _testResult.value = TestResult.Error(
                    "Connection failed: ${rootCause.message ?: rootCause::class.simpleName}"
                )
            }
        }
    }

    fun save(onSaved: () -> Unit = {}) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val config = _configuration.value.copy(isConfigured = true)
                securePreferences.saveApiKey(config.provider, _apiKey.value)
                if (config.provider != AiProvider.GOOGLE) {
                    val trimmedDecisionKey = _decisionApiKey.value.trim()
                    if (trimmedDecisionKey.isNotEmpty() || securePreferences.getApiKey(AiProvider.GOOGLE).isNotEmpty()) {
                        securePreferences.saveApiKey(AiProvider.GOOGLE, _decisionApiKey.value)
                    }
                }
                securePreferences.saveConfiguration(config)
                koogAgentFactory.clearDecisionClients()
                _testResult.value = null
                onSaved()
            } finally {
                _isSaving.value = false
            }
        }
    }

    private suspend fun loadModels(provider: AiProvider, baseUrl: String) {
        val models = koogAgentFactory.getAvailableModels(provider, baseUrl).distinct()
        _availableModels.value = models
        // Inherent package models serve as suggestions/defaults.
        // NEVER overwrite the user's custom-typed model for any provider!
        if (_configuration.value.model.isBlank()) {
            models.firstOrNull()?.let { updateModel(it) }
        }
    }

    sealed class TestResult {
        data object Loading : TestResult()
        data object Success : TestResult()
        data class Error(val message: String) : TestResult()
    }
}
