package com.clinref.app.ui.chat

import ai.koog.agents.core.agent.AIAgent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.SecureLogger
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.data.secure.SecurePreferences
import com.clinref.app.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class MessageUiModel(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val citations: List<SafetyValidator.Citation> = emptyList(),
    val warnings: List<String> = emptyList(),
    val isError: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val koogAgentFactory: KoogAgentFactory,
    private val streamingManager: StreamingManager,
    private val reliabilityManager: ReliabilityManager,
    private val securePreferences: SecurePreferences,
    private val secureLogger: SecureLogger
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _messages = MutableStateFlow<List<MessageUiModel>>(emptyList())
    val messages: StateFlow<List<MessageUiModel>> = _messages.asStateFlow()

    val agentState: StateFlow<StreamingManager.AgentState> = streamingManager.agentState
    val toolProgress: StateFlow<StreamingManager.ToolProgress?> = streamingManager.toolProgress

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    val configuration: StateFlow<AiConfiguration> = securePreferences.configuration

    private var generationJob: Job? = null

    fun loadConversation(conversationId: String) {
        _currentConversationId.value = conversationId
        viewModelScope.launch {
            val messages = conversationRepository.getMessages(conversationId).first()
            _messages.value = messages.map { it.toUiModel() } // toUiModel parses citationsJson/warningsJson
        }
    }

    fun sendMessage(content: String) {
        val conversationId = _currentConversationId.value ?: return
        if (content.isBlank()) return

        viewModelScope.launch {
            val userMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "user",
                content = content,
                timestamp = System.currentTimeMillis()
            )
            conversationRepository.addMessage(userMsg)
            _messages.value = _messages.value + userMsg.toUiModel()
            conversationRepository.updateTokenCounts(conversationId, promptDelta = content.length / 4, completionDelta = 0, toolDelta = 0)

            if (conversationRepository.isOverTokenLimit(conversationId)) {
                val errorMsg = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "assistant",
                    content = "Conversation has reached the token limit. Please start a new conversation.",
                    timestamp = System.currentTimeMillis()
                )
                conversationRepository.addMessage(errorMsg)
                _messages.value = _messages.value + errorMsg.toUiModel(isError = true)
                return@launch
            }

            val history = conversationRepository.getMessagesList(conversationId)
            val patientProfile = conversationRepository.getPatientProfile(conversationId)
            val config = configuration.value

            if (!config.isConfigured) {
                val errorMsg = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "assistant",
                    content = "AI is not configured. Please set up your provider in Settings.",
                    timestamp = System.currentTimeMillis()
                )
                conversationRepository.addMessage(errorMsg)
                _messages.value = _messages.value + errorMsg.toUiModel(isError = true)
                return@launch
            }

            streamingManager.reset()
            generationJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val agent: AIAgent<String, String>? = koogAgentFactory.createAgent(
                        config = config,
                        conversationId = conversationId,
                        patientProfile = patientProfile,
                        streamingManager = streamingManager
                    )
                    if (agent == null) {
                        withContext(Dispatchers.Main) {
                            streamingManager.onError("Could not create AI agent. Check your API key and settings.")
                        }
                        return@launch
                    }

                    // TODO: Replace with ChatMemory once Koog's ChatHistoryProvider is wired
                    val systemPrompt = koogAgentFactory.buildSystemPrompt(patientProfile)
                    val promptBuilder = StringBuilder()
                    promptBuilder.appendLine(systemPrompt)
                    promptBuilder.appendLine()
                    for (msg in history) {
                        when (msg.role) {
                            "user" -> promptBuilder.appendLine("User: ${msg.content}")
                            "assistant" -> promptBuilder.appendLine("Assistant: ${msg.content}")
                        }
                        promptBuilder.appendLine()
                    }
                    promptBuilder.appendLine("User: $content")

                    val result = reliabilityManager.withRetry {
                        reliabilityManager.runWithTimeout {
                            agent.run(promptBuilder.toString())
                        }
                    }

                    withContext(Dispatchers.Main) {
                        handleAgentResult(conversationId, result)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    secureLogger.log(SecureLogger.Level.ERROR, "ChatVM", "Agent error: ${e.message}")
                    withContext(Dispatchers.Main) {
                        streamingManager.onError(e.message ?: "Unknown error")
                        val errorState = streamingManager.agentState.value
                        if (errorState is StreamingManager.AgentState.Error) {
                            val errorMsg = MessageEntity(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                role = "assistant",
                                content = mapErrorToUserMessage(errorState.type),
                                timestamp = System.currentTimeMillis()
                            )
                            conversationRepository.addMessage(errorMsg)
                            _messages.value = _messages.value + errorMsg.toUiModel(isError = true)
                        }
                    }
                }
            }
        }
    }

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        val conversationId = _currentConversationId.value ?: return
        viewModelScope.launch {
            val cancelMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "cancelled",
                content = "[Generation cancelled by user]",
                timestamp = System.currentTimeMillis()
            )
            conversationRepository.addMessage(cancelMsg)
            _messages.value = _messages.value + cancelMsg.toUiModel()
            streamingManager.reset()
        }
    }

    fun startNewConversation(patientProfile: PatientProfile) {
        viewModelScope.launch {
            val title = generateConversationTitle(patientProfile)
            val id = conversationRepository.createConversation(title, patientProfile)
            _currentConversationId.value = id
            _messages.value = emptyList()
        }
    }

    private suspend fun handleAgentResult(conversationId: String, result: String) {
        val state = streamingManager.agentState.value
        when (state) {
            is StreamingManager.AgentState.Completed -> {
                if (state.validation.passed) {
                    val assistantMsg = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = result,
                        timestamp = System.currentTimeMillis(),
                        citationsJson = json.encodeToString(state.validation.citations),
                        warningsJson = json.encodeToString(state.validation.warnings)
                    )
                    conversationRepository.addMessage(assistantMsg)
                    _messages.value = _messages.value + assistantMsg.toUiModel(
                        citations = state.validation.citations,
                        warnings = state.validation.warnings
                    )
                    conversationRepository.updateTokenCounts(conversationId, promptDelta = 0, completionDelta = result.length / 4, toolDelta = 0)
                } else {
                    val blockedMsg = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = state.validation.blockedReason ?: "Response blocked by safety validator.",
                        timestamp = System.currentTimeMillis()
                    )
                    conversationRepository.addMessage(blockedMsg)
                    _messages.value = _messages.value + blockedMsg.toUiModel(isError = true)
                }
            }
            is StreamingManager.AgentState.Error -> {
                val errorMsg = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "assistant",
                    content = mapErrorToUserMessage(state.type),
                    timestamp = System.currentTimeMillis()
                )
                conversationRepository.addMessage(errorMsg)
                _messages.value = _messages.value + errorMsg.toUiModel(isError = true)
            }
            else -> {}
        }
    }

    private fun mapErrorToUserMessage(type: StreamingManager.ErrorType): String = when (type) {
        StreamingManager.ErrorType.INVALID_KEY -> "Invalid API key. Please check your settings."
        StreamingManager.ErrorType.NO_NETWORK -> "No network connection. Check your internet."
        StreamingManager.ErrorType.RATE_LIMIT -> "Rate limited. Please wait and try again."
        StreamingManager.ErrorType.TIMEOUT -> "Request timed out. Try again."
        StreamingManager.ErrorType.NO_RESULTS -> "No relevant medical topics found for your query."
        StreamingManager.ErrorType.UNKNOWN -> "An error occurred. Please try again."
    }

    private fun generateConversationTitle(profile: PatientProfile): String {
        val parts = mutableListOf<String>()
        if (profile.age.isNotBlank()) parts.add(profile.age)
        if (profile.sex.isNotBlank()) parts.add(profile.sex)
        if (profile.conditions.isNotEmpty()) parts.add(profile.conditions.first())
        return if (parts.isNotEmpty()) parts.joinToString(", ") else "New Conversation"
    }

    private fun MessageEntity.toUiModel(
        citations: List<SafetyValidator.Citation> = emptyList(),
        warnings: List<String> = emptyList(),
        isError: Boolean = false
    ): MessageUiModel {
        val parsedCitations = if (citations.isEmpty() && !citationsJson.isNullOrBlank()) {
            try { json.decodeFromString<List<SafetyValidator.Citation>>(citationsJson) } catch (_: Exception) { emptyList() }
        } else citations

        val parsedWarnings = if (warnings.isEmpty() && !warningsJson.isNullOrBlank()) {
            try { json.decodeFromString<List<String>>(warningsJson) } catch (_: Exception) { emptyList() }
        } else warnings

        return MessageUiModel(
            id = id,
            role = role,
            content = content,
            timestamp = timestamp,
            citations = parsedCitations,
            warnings = parsedWarnings,
            isError = isError || role == "cancelled"
        )
    }
}
