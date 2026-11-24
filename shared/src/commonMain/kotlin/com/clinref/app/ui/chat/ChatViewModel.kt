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
import com.clinref.app.domain.ai.GraphicRef
import com.clinref.app.domain.ai.TopicRef
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
    data class ScrollToMessage(
        val messageId: String,
        val animated: Boolean = true
    ) : ChatScrollEvent

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

    data class Message(
        val uiModel: MessageUiModel,
        val isTurnSuccessful: Boolean = true
    ) : ChatListItem {
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
    val isError: Boolean = false,
    val articles: List<ClinicalSource.Article> = emptyList(),
    val tables: List<ClinicalSource.Table> = emptyList()
)

class ChatViewModel(
    private val conversationRepository: ConversationRepository,
    private val contentRepository: ContentRepository,
    private val streamingManager: StreamingManager,
    private val securePreferences: SecurePreferences,
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

    private val _isAwaitingResponse = MutableStateFlow(false)
    val isAwaitingResponse: StateFlow<Boolean> = _isAwaitingResponse.asStateFlow()

    val streamingUiState: StateFlow<StreamingManager.StreamingUiState> = streamingManager.uiState

    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    private val _conversationTitle = MutableStateFlow("")
    val conversationTitle: StateFlow<String> = _conversationTitle.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(false)
    val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

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
                            ChatScrollEvent.ScrollToMessage(messageId = event.message.id, animated = true)
                        )
                    }
                    is ChatExecutionEvent.GenerationStarted -> {
                        _activeAssistantMessageId.value = event.messageId
                        _chatItems.value = buildChatItems(_messages.value)
                    }
                    is ChatExecutionEvent.AssistantMessageCompleted -> {
                        val isActive = _activeAssistantMessageId.value == event.message.id
                        if (isActive) {
                            _isAwaitingResponse.value = false
                            _activeAssistantMessageId.value = null
                        }
                        messageOffset++
                        val current = _messages.value
                        val cleanMessages = if (isActive) {
                            val orphanIndex = current.indexOfLast { it.id != event.message.id && (it.isError || it.role == "cancelled") }
                            if (orphanIndex >= 0 && orphanIndex >= current.size - 2) {
                                current.filterIndexed { idx, _ -> idx != orphanIndex }
                            } else {
                                current
                            }
                        } else {
                            current
                        }
                        updateMessages(cleanMessages + event.message.toUiModel())
                    }
                    is ChatExecutionEvent.GenerationError -> {
                        if (_activeAssistantMessageId.value == event.message.id) {
                            _isAwaitingResponse.value = false
                            _activeAssistantMessageId.value = null
                        }
                        messageOffset++
                        updateMessages(_messages.value + event.message.toUiModel(isError = true))
                        _scrollEvents.tryEmit(
                            ChatScrollEvent.ScrollToBottom(animated = false, awaitMessageId = event.message.id)
                        )
                    }
                    is ChatExecutionEvent.GenerationCancelled -> {
                        if (_activeAssistantMessageId.value == event.message.id) {
                            _isAwaitingResponse.value = false
                            _activeAssistantMessageId.value = null
                        }
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
        val isGeneratingActive = chatExecutionManager.isGenerating(conversationId)
        _isAwaitingResponse.value = isGeneratingActive
        if (isGeneratingActive) {
            _activeAssistantMessageId.value = chatExecutionManager.activeAssistantMessageId.value
        } else {
            _activeAssistantMessageId.value = null
        }

        _isLoadingConversation.value = true
        viewModelScope.launch {
            try {
                val profile = conversationRepository.getPatientProfile(conversationId)
                _patientProfile.value = profile
                val conv = conversationRepository.getConversation(conversationId)
                _conversationTitle.value = conv?.title.orEmpty()
                val messages = conversationRepository.getMessagesPage(conversationId, PAGE_SIZE, 0)
                messageOffset = messages.size
                _hasMoreMessages.value = messages.size >= PAGE_SIZE
                val uiModels = messages.reversed().map { it.toUiModel() }
                updateMessages(uiModels)
                conversationRepository.markAsRead(conversationId)
            } finally {
                _isLoadingConversation.value = false
            }
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

    fun sendMessage(content: String): Boolean {
        val conversationId = _currentConversationId.value ?: return false
        val trimmed = content.trim()
        if (trimmed.isBlank()) return false
        if (!chatExecutionManager.canSendMessage(conversationId)) return false
        _isAwaitingResponse.value = true
        val accepted = chatExecutionManager.sendMessage(conversationId, trimmed)
        if (!accepted) {
            _isAwaitingResponse.value = false
            return false
        }
        return true
    }

    /**
     * Submits an edited version of an existing user message.
     * Discards the previous user message, agent answer, and all subsequent turns from that round onwards,
     * then submits the newly edited prompt.
     */
    fun submitEditedMessage(editedUserMessageId: String, newContent: String): Boolean {
        val conversationId = _currentConversationId.value ?: return false
        val trimmed = newContent.trim()
        if (trimmed.isBlank()) return false
        if (!chatExecutionManager.canSendMessage(conversationId)) return false

        val currentList = _messages.value
        val targetIndex = currentList.indexOfFirst { it.id == editedUserMessageId }
        if (targetIndex < 0) return false

        val toDiscard = currentList.subList(targetIndex, currentList.size).map { it.id }
        val remaining = currentList.subList(0, targetIndex)

        messageOffset = remaining.size
        updateMessages(remaining)
        _isAwaitingResponse.value = true

        viewModelScope.launch {
            conversationRepository.deleteMessages(conversationId, toDiscard)
            val accepted = chatExecutionManager.sendMessage(conversationId, trimmed)
            if (!accepted) {
                _isAwaitingResponse.value = false
            }
        }
        return true
    }

    /**
     * Retries a failed or uncompleted turn.
     * Discards the failed round and re-runs synthesis for the original user query.
     */
    fun retryTurn(messageId: String): Boolean {
        val conversationId = _currentConversationId.value ?: return false
        if (!chatExecutionManager.canSendMessage(conversationId)) return false

        val currentList = _messages.value
        var targetIndex = currentList.indexOfFirst { it.id == messageId }
        if (targetIndex < 0) return false

        // If an assistant/error message ID was passed, find the preceding user message
        if (currentList[targetIndex].role != "user") {
            targetIndex = (targetIndex - 1 downTo 0).firstOrNull { currentList[it].role == "user" } ?: return false
        }

        val userPrompt = currentList[targetIndex].content
        val toDiscard = currentList.subList(targetIndex, currentList.size).map { it.id }
        val remaining = currentList.subList(0, targetIndex)

        messageOffset = remaining.size
        updateMessages(remaining)
        _isAwaitingResponse.value = true

        viewModelScope.launch {
            conversationRepository.deleteMessages(conversationId, toDiscard)
            val accepted = chatExecutionManager.sendMessage(conversationId, userPrompt)
            if (!accepted) {
                _isAwaitingResponse.value = false
            }
        }
        return true
    }

    fun cancelGeneration() {
        val conversationId = _currentConversationId.value ?: return
        _isAwaitingResponse.value = false
        chatExecutionManager.cancelGeneration(conversationId)
    }

    private fun isTurnSuccessfulAt(
        messages: List<MessageUiModel>,
        index: Int,
        isGenerating: Boolean,
        activeAssistantId: String?
    ): Boolean {
        val message = messages.getOrNull(index) ?: return true
        if (message.role != "user") return true
        val next = messages.getOrNull(index + 1) ?: return isGenerating
        if (activeAssistantId != null && activeAssistantId == next.id) return true
        return next.role == "assistant" && !next.isError && next.role != "cancelled"
    }

    private fun buildChatItems(messages: List<MessageUiModel>): List<ChatListItem> {
        if (messages.isEmpty()) return emptyList()
        val result = ArrayList<ChatListItem>(messages.size * 2)
        val isGenerating = _activeAssistantMessageId.value != null || _isAwaitingResponse.value
        val activeAssistantId = _activeAssistantMessageId.value

        for (i in messages.indices) {
            val message = messages[i]
            val isTurnSuccessful = isTurnSuccessfulAt(messages, i, isGenerating, activeAssistantId)

            result.add(ChatListItem.Message(message, isTurnSuccessful = isTurnSuccessful))
            if ((message.articles.isNotEmpty() || message.tables.isNotEmpty()) && message.role == "assistant") {
                result.add(ChatListItem.ClinicalReferences(message.id, message.articles, message.tables))
            }
        }
        return result
    }

    private fun MessageEntity.toUiModel(
        isError: Boolean = false
    ): MessageUiModel {
        val topicRefs = if (!topicRefsJson.isNullOrBlank()) {
            try {
                json.decodeFromString<List<TopicRef>>(topicRefsJson).map {
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
                json.decodeFromString<List<GraphicRef>>(graphicRefsJson).map { ref ->
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
            isError = isError || this.isError || role == "cancelled",
            articles = articles,
            tables = tables
        )
    }
}
