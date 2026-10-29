package com.clinref.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiJsonUtils
import com.clinref.app.domain.ai.ClinicalAgentStrategy
import com.clinref.app.domain.ai.ClinicalSource
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.ReasoningTokenSanitizer
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.SecureLogger
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import com.clinref.shared.secure.SecurePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

sealed interface ChatScrollEvent {
    /**
     * Scroll to the end of the conversation. [awaitMessageId], when set, is the message that was just
     * appended; the UI can wait until it is present before scrolling.
     */
    data class ScrollToBottom(
        val animated: Boolean = true,
        val awaitMessageId: String? = null
    ) : ChatScrollEvent
}

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
    val key: String

    data class Message(val uiModel: MessageUiModel) : ChatListItem {
        override val key: String get() = uiModel.id
    }

    data class ClinicalReferences(
        val messageId: String,
        val articles: List<ClinicalSource.Article>,
        val tables: List<ClinicalSource.Table>
    ) : ChatListItem {
        override val key: String get() = "refs_$messageId"
    }
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

class ChatViewModel(
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

    private val _chatItems = MutableStateFlow<List<ChatListItem>>(emptyList())
    val chatItems: StateFlow<List<ChatListItem>> = _chatItems.asStateFlow()

    private val _scrollEvents = MutableSharedFlow<ChatScrollEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val scrollEvents: SharedFlow<ChatScrollEvent> = _scrollEvents.asSharedFlow()

    private val _activeAssistantMessageId = MutableStateFlow<String?>(null)
    val activeAssistantMessageId: StateFlow<String?> = _activeAssistantMessageId.asStateFlow()

    val agentState: StateFlow<StreamingManager.AgentState> = streamingManager.agentState
    val toolProgress: StateFlow<StreamingManager.ToolProgress?> = streamingManager.toolProgress
    val orchestrationSteps: StateFlow<List<StreamingManager.OrchestrationStep>> = streamingManager.orchestrationSteps
    val currentStatusText: StateFlow<String> = streamingManager.currentStatusText
    val streamingText: StateFlow<String> = streamingManager.streamingText
    val reasoningText: StateFlow<String> = streamingManager.reasoningText
    val isReasoning: StateFlow<Boolean> = streamingManager.isReasoning
    val liveDiscoveredSources: StateFlow<List<SafetyValidator.TopicRef>> = streamingManager.liveDiscoveredSources

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

    /**
     * Atomically synchronizes messages and derived decomposed chat items.
     */
    private fun updateMessages(newMessages: List<MessageUiModel>) {
        val deduplicated = newMessages.distinctBy { it.id }
        _messages.value = deduplicated
        _chatItems.value = buildChatItems(deduplicated)
    }

    fun loadConversation(conversationId: String, forceReload: Boolean = false) {
        if (!forceReload && _currentConversationId.value == conversationId && _messages.value.isNotEmpty()) {
            return
        }
        generationJob?.cancel()
        generationJob = null
        _activeAssistantMessageId.value = null
        _currentConversationId.value = conversationId
        messageOffset = 0
        streamingManager.reset()
        viewModelScope.launch {
            val profile = conversationRepository.getPatientProfile(conversationId)
            _patientProfile.value = profile
            val messages = conversationRepository.getMessagesPage(conversationId, PAGE_SIZE, 0)
            messageOffset = messages.size
            _hasMoreMessages.value = messages.size >= PAGE_SIZE
            val uiModels = messages.reversed().map { it.toUiModel() }
            updateMessages(uiModels)
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
                    val olderUiModels = olderMessages.reversed().map { it.toUiModel() }
                    updateMessages(olderUiModels + _messages.value)
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
        val trimmed = content.trim()
        if (trimmed.isBlank()) return

        viewModelScope.launch {
            val userMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "user",
                content = trimmed,
                timestamp = System.currentTimeMillis()
            )
            conversationRepository.addMessage(userMsg)
            messageOffset++
            updateMessages(_messages.value + userMsg.toUiModel())
            _scrollEvents.tryEmit(ChatScrollEvent.ScrollToBottom(animated = true, awaitMessageId = userMsg.id))
            conversationRepository.updateLastPreview(conversationId, trimmed.take(100))

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
                updateMessages(_messages.value + errorMsg.toUiModel(isError = true))
                _scrollEvents.tryEmit(ChatScrollEvent.ScrollToBottom(animated = false, awaitMessageId = errorMsg.id))
                return@launch
            }

            // Assign a stable ID for the upcoming assistant message so that streaming and completed states share identity
            val pendingAssistantId = UUID.randomUUID().toString()
            _activeAssistantMessageId.value = pendingAssistantId

            streamingManager.reset()
            userCancelled = false
            val timeoutMs = reliabilityManager.getAgentTimeoutMs(config.provider)

            generationJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result = reliabilityManager.runWithTimeout(timeoutMs = timeoutMs) {
                        // The accumulator is created and wired into the agent (tool callbacks,
                        // patient profile, question) inside createAgent and must outlive this
                        // local scope; the ViewModel itself never reads it afterwards.
                        val (agent, _) = koogAgentFactory.createAgent(
                            config = config,
                            conversationId = conversationId,
                            patientProfile = patientProfile,
                            streamingManager = streamingManager,
                            userMessage = trimmed
                        ) ?: throw IllegalStateException("Failed to bind agent model. Verify API keys and network interfaces.")
                        agent.run(trimmed, conversationId)
                    }
                    withContext(Dispatchers.Main) {
                        handleAgentResult(conversationId, pendingAssistantId, result)
                    }
                } catch (e: CancellationException) {
                    if (userCancelled) throw e
                    withContext(Dispatchers.Main) {
                        handleAgentError(conversationId, pendingAssistantId, e.message ?: "Request timed out.", StreamingManager.ErrorType.TIMEOUT)
                    }
                } catch (e: Exception) {
                    secureLogger.log(SecureLogger.Level.ERROR, "ChatViewModel", "Clinical runtime crash: ${e.message}")
                    withContext(Dispatchers.Main) {
                        handleAgentError(conversationId, pendingAssistantId, e.message ?: "General core execution error.", StreamingManager.ErrorType.UNKNOWN)
                    }
                } finally {
                    withContext(Dispatchers.Main) {
                        _activeAssistantMessageId.value = null
                    }
                }
            }
        }
    }

    private suspend fun handleAgentError(
        conversationId: String,
        messageId: String,
        errorMessage: String,
        errorType: StreamingManager.ErrorType
    ) {
        streamingManager.onError(errorMessage)
        val errorMsg = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            role = "assistant",
            content = mapErrorToUserMessage(errorType, errorMessage),
            timestamp = System.currentTimeMillis(),
            isError = true
        )
        conversationRepository.addMessage(errorMsg)
        messageOffset++
        updateMessages(_messages.value + errorMsg.toUiModel(isError = true))
        _scrollEvents.tryEmit(ChatScrollEvent.ScrollToBottom(animated = false, awaitMessageId = errorMsg.id))
    }

    fun cancelGeneration() {
        userCancelled = true
        generationJob?.cancel()
        generationJob = null
        val pendingId = _activeAssistantMessageId.value
        _activeAssistantMessageId.value = null
        val conversationId = _currentConversationId.value ?: return

        viewModelScope.launch {
            val cancelMsg = MessageEntity(
                id = pendingId ?: UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "cancelled",
                content = "[Clinical generation session terminated by operator]",
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            conversationRepository.addMessage(cancelMsg)
            messageOffset++
            updateMessages(_messages.value + cancelMsg.toUiModel())
            streamingManager.reset()
        }
    }

    private suspend fun handleAgentResult(
        conversationId: String,
        messageId: String,
        result: String
    ) {
        val cleanResult = ReasoningTokenSanitizer.stripReasoning(result)
        when (val state = streamingManager.agentState.value) {
            is StreamingManager.AgentState.Completed -> {
                if (state.validation.passed && !cleanResult.startsWith(ClinicalAgentStrategy.BLOCKED_MARKER)) {
                    saveSuccessfulMessage(
                        conversationId, messageId, cleanResult, state.validation, state.tokenUsage
                    )
                } else {
                    val blockedReason = state.validation.blockedReason
                        ?: "Output quarantined by safety rules."
                    secureLogger.log(
                        SecureLogger.Level.WARN, "ChatViewModel",
                        "Safety blocked: $blockedReason."
                    )
                    val content = if (cleanResult.startsWith(ClinicalAgentStrategy.BLOCKED_MARKER)) {
                        cleanResult
                    } else {
                        "${ClinicalAgentStrategy.BLOCKED_MARKER}$blockedReason"
                    }
                    val blockedMsg = MessageEntity(
                        id = messageId,
                        conversationId = conversationId,
                        role = "assistant",
                        content = content,
                        timestamp = System.currentTimeMillis(),
                        warningsJson = json.encodeToString(state.validation.warnings),
                        topicRefsJson = json.encodeToString(state.validation.topicRefs),
                        graphicRefsJson = json.encodeToString(state.validation.graphicRefs),
                        isError = true
                    )
                    conversationRepository.addMessage(blockedMsg)
                    messageOffset++
                    updateMessages(_messages.value + blockedMsg.toUiModel(warnings = state.validation.warnings, isError = true))
                }
            }
            is StreamingManager.AgentState.Error -> {
                val errorMsg = MessageEntity(
                    id = messageId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = mapErrorToUserMessage(state.type, state.message),
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
                conversationRepository.addMessage(errorMsg)
                messageOffset++
                updateMessages(_messages.value + errorMsg.toUiModel(isError = true))
            }
            else -> {
                secureLogger.log(SecureLogger.Level.WARN, "ChatViewModel", "handleAgentResult: state ${state::class.simpleName}")
                val fallbackResult = result.ifBlank { "Clinical query response generated." }
                val fallbackMsg = MessageEntity(
                    id = messageId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = fallbackResult,
                    timestamp = System.currentTimeMillis()
                )
                conversationRepository.addMessage(fallbackMsg)
                messageOffset++
                updateMessages(_messages.value + fallbackMsg.toUiModel())
            }
        }
    }

    private suspend fun saveSuccessfulMessage(
        conversationId: String,
        messageId: String,
        result: String,
        validation: SafetyValidator.ValidationResult,
        usage: StreamingManager.TokenUsage
    ) {
        val assistantMsg = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            role = "assistant",
            content = result,
            timestamp = System.currentTimeMillis(),
            warningsJson = json.encodeToString(validation.warnings),
            topicRefsJson = json.encodeToString(validation.topicRefs),
            graphicRefsJson = json.encodeToString(validation.graphicRefs)
        )
        conversationRepository.addMessage(assistantMsg)
        messageOffset++
        updateMessages(_messages.value + assistantMsg.toUiModel(warnings = validation.warnings))
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
        val result = ArrayList<ChatListItem>(messages.size * 2)
        for (message in messages) {
            result.add(ChatListItem.Message(message.copy(showTimestamp = true)))
            if ((message.articles.isNotEmpty() || message.tables.isNotEmpty()) && message.role == "assistant") {
                result.add(ChatListItem.ClinicalReferences(message.id, message.articles, message.tables))
            }
        }
        return result
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
