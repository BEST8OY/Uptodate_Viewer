package com.clinref.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiJsonUtils
import com.clinref.app.domain.ai.ChatExecutionEvent
import com.clinref.app.domain.ai.ChatExecutionManager
import com.clinref.app.domain.ai.ClinicalSource
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.SecureLogger
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import com.clinref.shared.secure.SecurePreferences
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

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
    private val streamingManager: StreamingManager,
    private val securePreferences: SecurePreferences,
    private val secureLogger: SecureLogger,
    private val chatExecutionManager: ChatExecutionManager
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

    private var messageOffset = 0

    companion object {
        private const val PAGE_SIZE = 50
    }

    init {
        viewModelScope.launch {
            chatExecutionManager.events.collect { event ->
                if (event.conversationId != _currentConversationId.value) return@collect
                when (event) {
                    is ChatExecutionEvent.UserMessageAdded -> {
                        messageOffset++
                        updateMessages(_messages.value + event.message.toUiModel())
                        _scrollEvents.tryEmit(
                            ChatScrollEvent.ScrollToBottom(animated = true, awaitMessageId = event.message.id)
                        )
                    }
                    is ChatExecutionEvent.GenerationStarted -> {
                        _activeAssistantMessageId.value = event.messageId
                    }
                    is ChatExecutionEvent.AssistantMessageCompleted -> {
                        _activeAssistantMessageId.value = null
                        messageOffset++
                        updateMessages(_messages.value + event.message.toUiModel(warnings = event.warnings))
                        _scrollEvents.tryEmit(
                            ChatScrollEvent.ScrollToBottom(animated = true, awaitMessageId = event.message.id)
                        )
                    }
                    is ChatExecutionEvent.GenerationError -> {
                        _activeAssistantMessageId.value = null
                        messageOffset++
                        updateMessages(_messages.value + event.message.toUiModel(isError = true))
                        _scrollEvents.tryEmit(
                            ChatScrollEvent.ScrollToBottom(animated = false, awaitMessageId = event.message.id)
                        )
                    }
                    is ChatExecutionEvent.GenerationCancelled -> {
                        _activeAssistantMessageId.value = null
                        messageOffset++
                        updateMessages(_messages.value + event.message.toUiModel())
                    }
                }
            }
        }
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
        _currentConversationId.value = conversationId
        messageOffset = 0

        // Synchronize with background active generation if one is running for this conversation
        if (chatExecutionManager.isGenerating(conversationId)) {
            _activeAssistantMessageId.value = chatExecutionManager.activeAssistantMessageId.value
        } else {
            _activeAssistantMessageId.value = null
        }

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
        chatExecutionManager.sendMessage(conversationId, trimmed)
    }

    fun cancelGeneration() {
        val conversationId = _currentConversationId.value ?: return
        chatExecutionManager.cancelGeneration(conversationId)
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
