package com.clinref.app.ui.chat

import ai.koog.agents.core.agent.AIAgent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.RateLimiter
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

data class MessageUiModel(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val citations: List<SafetyValidator.Citation> = emptyList(),
    val warnings: List<String> = emptyList(),
    val isError: Boolean = false,
    val showTimestamp: Boolean = true
)

sealed interface ChatListItem {
    data class DateSeparator(val label: String) : ChatListItem
    data class Message(val uiModel: MessageUiModel) : ChatListItem
}

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val koogAgentFactory: KoogAgentFactory,
    private val streamingManager: StreamingManager,
    private val reliabilityManager: ReliabilityManager,
    private val securePreferences: SecurePreferences,
    private val secureLogger: SecureLogger,
    private val rateLimiter: RateLimiter
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _messages = MutableStateFlow<List<MessageUiModel>>(emptyList())
    val messages: StateFlow<List<MessageUiModel>> = _messages.asStateFlow()

    val chatItems: StateFlow<List<ChatListItem>> = _messages.map { messages ->
        buildChatItems(messages)
    }.let { flow ->
        val state = MutableStateFlow<List<ChatListItem>>(emptyList())
        viewModelScope.launch {
            flow.collect { state.value = it }
        }
        state.asStateFlow()
    }

    val agentState: StateFlow<StreamingManager.AgentState> = streamingManager.agentState
    val toolProgress: StateFlow<StreamingManager.ToolProgress?> = streamingManager.toolProgress

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private val _patientProfile = MutableStateFlow<PatientProfile?>(null)
    val patientProfile: StateFlow<PatientProfile?> = _patientProfile.asStateFlow()

    val configuration: StateFlow<AiConfiguration> = securePreferences.configuration

    private var generationJob: Job? = null
    private var messageOffset = 0

    companion object {
        private const val PAGE_SIZE = 50
        private const val TIMESTAMP_GAP_MS = 5 * 60 * 1000L // 5 minutes
    }

    fun loadConversation(conversationId: String) {
        _currentConversationId.value = conversationId
        messageOffset = 0
        viewModelScope.launch {
            val profile = conversationRepository.getPatientProfile(conversationId)
            _patientProfile.value = profile

            val messages = conversationRepository.getMessagesPage(conversationId, PAGE_SIZE, 0)
            messageOffset = messages.size
            _hasMoreMessages.value = messages.size >= PAGE_SIZE
            _messages.value = messages.reversed().map { it.toUiModel() }

            conversationRepository.markAsRead(conversationId)
        }
    }

    fun loadOlderMessages() {
        val conversationId = _currentConversationId.value ?: return
        if (_isLoadingOlder.value || !_hasMoreMessages.value) return

        viewModelScope.launch {
            _isLoadingOlder.value = true
            try {
                val olderMessages = conversationRepository.getMessagesPage(
                    conversationId, PAGE_SIZE, messageOffset
                )
                if (olderMessages.isNotEmpty()) {
                    messageOffset += olderMessages.size
                    _hasMoreMessages.value = olderMessages.size >= PAGE_SIZE
                    val olderUiModels = olderMessages.map { it.toUiModel() }
                    _messages.value = olderUiModels + _messages.value
                } else {
                    _hasMoreMessages.value = false
                }
            } finally {
                _isLoadingOlder.value = false
            }
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
            conversationRepository.updateLastPreview(conversationId, content.take(100))

            if (conversationRepository.isOverTokenLimit(conversationId)) {
                val errorMsg = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "assistant",
                    content = "Conversation has reached the token limit. Please start a new conversation.",
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
                conversationRepository.addMessage(errorMsg)
                _messages.value = _messages.value + errorMsg.toUiModel(isError = true)
                return@launch
            }

            val patientProfile = conversationRepository.getPatientProfile(conversationId)
            val config = configuration.value

            if (!config.isConfigured) {
                val errorMsg = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "assistant",
                    content = "AI is not configured. Please set up your provider in Settings.",
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
                conversationRepository.addMessage(errorMsg)
                _messages.value = _messages.value + errorMsg.toUiModel(isError = true)
                return@launch
            }

            streamingManager.reset()
            generationJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    rateLimiter.configure(config.requestsPerMinute)
                    rateLimiter.acquire()

                    val result = reliabilityManager.withRetry {
                        reliabilityManager.runWithTimeout {
                            val agent: AIAgent<String, String>? = koogAgentFactory.createAgent(
                                config = config,
                                conversationId = conversationId,
                                patientProfile = patientProfile,
                                streamingManager = streamingManager
                            )
                            if (agent == null) {
                                throw IllegalStateException("Could not create AI agent. Check your API key and settings.")
                            }
                            agent.run(content, conversationId)
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
                                timestamp = System.currentTimeMillis(),
                                isError = true
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
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            conversationRepository.addMessage(cancelMsg)
            _messages.value = _messages.value + cancelMsg.toUiModel()
            streamingManager.reset()
        }
    }

    fun copyMessageToClipboard(context: Context, content: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ClinRef Message", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Message copied", Toast.LENGTH_SHORT).show()
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
                    conversationRepository.updateLastPreview(conversationId, result.take(100))
                } else {
                    val blockedMsg = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = state.validation.blockedReason ?: "Response blocked by safety validator.",
                        timestamp = System.currentTimeMillis(),
                        isError = true
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
                    timestamp = System.currentTimeMillis(),
                    isError = true
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

    private fun buildChatItems(messages: List<MessageUiModel>): List<ChatListItem> {
        if (messages.isEmpty()) return emptyList()

        val items = mutableListOf<ChatListItem>()
        var lastDate: Calendar? = null
        var lastTimestamp = 0L

        for (message in messages) {
            val msgDate = Calendar.getInstance().apply { timeInMillis = message.timestamp }
            val sameDay = lastDate?.let {
                it.get(Calendar.YEAR) == msgDate.get(Calendar.YEAR) &&
                    it.get(Calendar.DAY_OF_YEAR) == msgDate.get(Calendar.DAY_OF_YEAR)
            } ?: false

            if (!sameDay) {
                items.add(ChatListItem.DateSeparator(formatDateLabel(message.timestamp)))
            }

            val showTimestamp = !sameDay || (message.timestamp - lastTimestamp > TIMESTAMP_GAP_MS)
            items.add(ChatListItem.Message(message.copy(showTimestamp = showTimestamp)))

            lastDate = msgDate
            lastTimestamp = message.timestamp
        }

        return items
    }

    private fun formatDateLabel(timestamp: Long): String {
        val now = Calendar.getInstance()
        val msgDate = Calendar.getInstance().apply { timeInMillis = timestamp }

        return when {
            now.get(Calendar.YEAR) == msgDate.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == msgDate.get(Calendar.DAY_OF_YEAR) -> "Today"
            now.get(Calendar.YEAR) == msgDate.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) - msgDate.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
            else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
        }
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
            isError = isError || this.isError || role == "cancelled"
        )
    }
}
