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
import com.clinref.app.domain.ai.AiProvider
import com.clinref.app.domain.ai.ClinicalSource
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.SecureLogger
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.domain.ai.SystemPrompt
import com.clinref.app.domain.ai.TurnContextAccumulator
import com.clinref.app.data.secure.SecurePreferences
import com.clinref.app.domain.ai.AiJsonUtils
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class ResolvedTopicRef(
    val topicId: String,
    val title: String,
    val sectionId: String? = null,
    val topicTitle: String = ""
)
data class ResolvedGraphicRef(
    val graphicId: String,
    val title: String,
    val topicId: String? = null,
    val topicTitle: String? = null
)

sealed interface ChatListItem {
    data class Message(val uiModel: MessageUiModel) : ChatListItem
}

data class MessageUiModel(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val warnings: List<String> = emptyList(),
    val isError: Boolean = false,
    val showTimestamp: Boolean = false,
    val topicRefs: List<ResolvedTopicRef> = emptyList(),
    val graphicRefs: List<ResolvedGraphicRef> = emptyList(),
    val articles: List<ClinicalSource.Article> = emptyList(),
    val tables: List<ClinicalSource.Table> = emptyList()
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val contentRepository: ContentRepository,
    private val koogAgentFactory: KoogAgentFactory,
    private val streamingManager: StreamingManager,
    private val reliabilityManager: ReliabilityManager,
    private val securePreferences: SecurePreferences,
    private val secureLogger: SecureLogger
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _messages = MutableStateFlow<List<MessageUiModel>>(emptyList())
    val messages: StateFlow<List<MessageUiModel>> = _messages.asStateFlow()

    val chatItems: StateFlow<List<ChatListItem>> = _messages.map { list ->
        buildChatItems(list)
    }.let { flow ->
        val state = MutableStateFlow<List<ChatListItem>>(emptyList())
        viewModelScope.launch {
            flow.collect { state.value = it }
        }
        state.asStateFlow()
    }

    val agentState: StateFlow<StreamingManager.AgentState> = streamingManager.agentState
    val toolProgress: StateFlow<StreamingManager.ToolProgress?> = streamingManager.toolProgress
    val orchestrationSteps: StateFlow<List<StreamingManager.OrchestrationStep>> = streamingManager.orchestrationSteps
    val currentStatusText: StateFlow<String> = streamingManager.currentStatusText
    val liveDiscoveredSources: StateFlow<List<SafetyValidator.TopicRef>> = streamingManager.liveDiscoveredSources
    val streamingText: StateFlow<String> = streamingManager.streamingText

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
    private var userCancelled = false
    private var messageOffset = 0

    companion object {
        private const val PAGE_SIZE = 50
    }

    fun loadConversation(conversationId: String) {
        generationJob?.cancel()
        generationJob = null
        _currentConversationId.value = conversationId
        messageOffset = 0
        streamingManager.reset()
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
            conversationRepository.updateLastPreview(conversationId, content.take(100))

            if (conversationRepository.isOverTokenLimit(conversationId)) {
                val errorMsg = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "assistant",
                    content = "This conversation has exceeded safe token capacities. Please initialize a fresh clinical workspace.",
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
                    content = "The clinical AI core is unconfigured. Please finalize provider keys in clinical settings.",
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
                conversationRepository.addMessage(errorMsg)
                _messages.value = _messages.value + errorMsg.toUiModel(isError = true)
                return@launch
            }

            streamingManager.reset()
            userCancelled = false
            val timeoutMs = reliabilityManager.getAgentTimeoutMs(config.provider)
            generationJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    var lastAccumulator: TurnContextAccumulator? = null
                    val result = reliabilityManager.runWithTimeout(timeoutMs = timeoutMs) {
                        val (agent, accumulator) = koogAgentFactory.createAgent(
                            config = config,
                            conversationId = conversationId,
                            patientProfile = patientProfile,
                            streamingManager = streamingManager,
                            userMessage = content
                        ) ?: throw IllegalStateException("Failed to bind agent model. Verify API keys and network interfaces.")
                        lastAccumulator = accumulator
                        agent.run(content, conversationId)
                    }
                    withContext(Dispatchers.Main) {
                        handleAgentResult(conversationId, result, lastAccumulator)
                    }
                } catch (e: CancellationException) {
                    if (userCancelled) throw e
                    withContext(Dispatchers.Main) {
                        streamingManager.onError(e.message ?: "Request timed out.")
                        val errorState = streamingManager.agentState.value
                        if (errorState is StreamingManager.AgentState.Error) {
                            val errorMsg = MessageEntity(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                role = "assistant",
                                content = mapErrorToUserMessage(errorState.type, errorState.message),
                                timestamp = System.currentTimeMillis(),
                                isError = true
                            )
                            conversationRepository.addMessage(errorMsg)
                            _messages.value = _messages.value + errorMsg.toUiModel(isError = true)
                        }
                    }
                } catch (e: Exception) {
                    secureLogger.log(SecureLogger.Level.ERROR, "ChatViewModel", "Clinical runtime crash: ${e.message}")
                    withContext(Dispatchers.Main) {
                        streamingManager.onError(e.message ?: "General core execution error.")
                        val errorState = streamingManager.agentState.value
                        if (errorState is StreamingManager.AgentState.Error) {
                            val errorMsg = MessageEntity(
                                id = UUID.randomUUID().toString(),
                                conversationId = conversationId,
                                role = "assistant",
                                content = mapErrorToUserMessage(errorState.type, errorState.message),
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
        userCancelled = true
        generationJob?.cancel()
        generationJob = null
        val conversationId = _currentConversationId.value ?: return
        viewModelScope.launch {
            val cancelMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "cancelled",
                content = "[Clinical generation session terminated by operator]",
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
        val clip = ClipData.newPlainText("ClinRef Clinical Output", content)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied content to workspace clipboard", Toast.LENGTH_SHORT).show()
    }

    private suspend fun handleAgentResult(
        conversationId: String,
        result: String,
        accumulator: TurnContextAccumulator?
    ) {
        when (val state = streamingManager.agentState.value) {
            is StreamingManager.AgentState.Completed -> {
                if (state.validation.passed && !result.startsWith("Clinical Response Verification Blocked:")) {
                    saveSuccessfulMessage(
                        conversationId, result, state.validation, state.tokenUsage
                    )
                } else {
                    val blockedReason = state.validation.blockedReason
                        ?: "Output quarantined by safety rules."
                    secureLogger.log(
                        SecureLogger.Level.WARN, "ChatViewModel",
                        "Safety blocked: $blockedReason."
                    )
                    val content = if (result.startsWith("Clinical Response Verification Blocked:")) {
                        result
                    } else {
                        "Clinical Response Verification Blocked: $blockedReason"
                    }
                    val blockedMsg = MessageEntity(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = content,
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
                    content = mapErrorToUserMessage(state.type, state.message),
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
                conversationRepository.addMessage(errorMsg)
                _messages.value = _messages.value + errorMsg.toUiModel(isError = true)
            }
            else -> {
                secureLogger.log(SecureLogger.Level.WARN, "ChatViewModel", "handleAgentResult: state ${state::class.simpleName}")
            }
        }
    }

    private suspend fun saveSuccessfulMessage(
        conversationId: String,
        result: String,
        validation: SafetyValidator.ValidationResult,
        usage: StreamingManager.TokenUsage
    ) {
        val assistantMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            role = "assistant",
            content = result,
            timestamp = System.currentTimeMillis(),
            warningsJson = json.encodeToString(validation.warnings),
            topicRefsJson = json.encodeToString(validation.topicRefs),
            graphicRefsJson = json.encodeToString(validation.graphicRefs)
        )
        conversationRepository.addMessage(assistantMsg)
        _messages.value = _messages.value + assistantMsg.toUiModel(
            warnings = validation.warnings
        )
        conversationRepository.updateTokenCounts(
            conversationId,
            promptDelta = usage.promptTokens,
            completionDelta = usage.completionTokens,
            toolDelta = 0
        )
        conversationRepository.updateLastPreview(conversationId, result.take(100))
    }

    private fun mapErrorToUserMessage(type: StreamingManager.ErrorType, detail: String? = null): String {
        val baseMessage = when (type) {
            StreamingManager.ErrorType.INVALID_KEY -> "API Key validation rejected. Re-authenticate clinical tokens in Configuration."
            StreamingManager.ErrorType.NO_NETWORK -> "Network layer unreachable. Please check connectivity."
            StreamingManager.ErrorType.RATE_LIMIT -> "Upstream rate limit reached. Retrying..."
            StreamingManager.ErrorType.TIMEOUT -> "Reference core timeout. Please resubmit."
            StreamingManager.ErrorType.NO_RESULTS -> "Query executed, but reference matches returned no data."
            StreamingManager.ErrorType.UNKNOWN -> "An error occurred during response generation."
        }
        val cleanDetail = detail?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("Unknown error", ignoreCase = true) && !it.equals(baseMessage, ignoreCase = true)
        }
        return if (cleanDetail != null) {
            "$baseMessage\n\n[$cleanDetail]"
        } else {
            baseMessage
        }
    }

    private fun buildChatItems(messages: List<MessageUiModel>): List<ChatListItem> {
        if (messages.isEmpty()) return emptyList()
        return messages.map { message ->
            ChatListItem.Message(message.copy(showTimestamp = true))
        }
    }

    private fun MessageEntity.toUiModel(
        warnings: List<String> = emptyList(),
        isError: Boolean = false
    ): MessageUiModel {
        val parsedWarnings = if (warnings.isEmpty() && !warningsJson.isNullOrBlank()) {
            try { json.decodeFromString<List<String>>(warningsJson) } catch (_: Exception) { emptyList() }
        } else warnings

        val topicRefs = if (!topicRefsJson.isNullOrBlank()) {
            try {
                json.decodeFromString<List<SafetyValidator.TopicRef>>(topicRefsJson).map {
                    val resolvedTopicTitle = contentRepository.getTopicTitle(it.topicId)?.takeIf { title ->
                        !AiJsonUtils.isNumericOnly(title)
                    } ?: it.topicTitle.takeIf { title ->
                        title.isNotBlank() && !AiJsonUtils.isNumericOnly(title)
                    } ?: "Clinical Topic #${it.topicId}"

                    val displayTitle = it.label.ifBlank { resolvedTopicTitle }
                    ResolvedTopicRef(
                        topicId = it.topicId,
                        title = displayTitle,
                        sectionId = it.sectionId.ifEmpty { null },
                        topicTitle = resolvedTopicTitle
                    )
                }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        val graphicRefs = if (!graphicRefsJson.isNullOrBlank()) {
            try {
                json.decodeFromString<List<SafetyValidator.GraphicRef>>(graphicRefsJson).map { ref ->
                    val parentTopicTitle = ref.topicId?.let { tid ->
                        contentRepository.getTopicTitle(tid)?.takeIf { title -> !AiJsonUtils.isNumericOnly(title) }
                            ?: topicRefs.find { t -> t.topicId == tid }?.topicTitle
                    }
                    ResolvedGraphicRef(
                        graphicId = ref.graphicId,
                        title = ref.label,
                        topicId = ref.topicId,
                        topicTitle = parentTopicTitle
                    )
                }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        val (articles, tables) = ClinicalSource.fromResolved(topicRefs, graphicRefs)

        return MessageUiModel(
            id = id,
            role = role,
            content = content,
            timestamp = timestamp,
            warnings = parsedWarnings,
            isError = isError || this.isError || role == "cancelled",
            topicRefs = topicRefs,
            graphicRefs = graphicRefs,
            articles = articles,
            tables = tables
        )
    }
}
