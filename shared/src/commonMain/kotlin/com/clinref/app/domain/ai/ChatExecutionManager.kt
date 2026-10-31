package com.clinref.app.domain.ai

import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import com.clinref.shared.secure.SecurePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Application-scoped singleton coordinator for clinical AI synthesis sessions.
 *
 * Decouples agent orchestration, tool retrieval, and message persistence from UI screen
 * lifecycles so that reasoning continues uninterrupted when the user navigates away,
 * switches tabs, or minimizes the application.
 */
class ChatExecutionManager(
    private val conversationRepository: ConversationRepository,
    private val contentRepository: ContentRepository,
    private val koogAgentFactory: KoogAgentFactory,
    private val streamingManager: StreamingManager,
    private val reliabilityManager: ReliabilityManager,
    private val securePreferences: SecurePreferences,
    private val secureLogger: SecureLogger,
    private val backgroundExecutionBridge: BackgroundExecutionBridge? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _activeAssistantMessageId = MutableStateFlow<String?>(null)
    val activeAssistantMessageId: StateFlow<String?> = _activeAssistantMessageId.asStateFlow()

    private var activeJob: Job? = null
    private var userCancelled = false

    private val _events = MutableSharedFlow<ChatExecutionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ChatExecutionEvent> = _events.asSharedFlow()

    fun isGenerating(conversationId: String): Boolean =
        _activeConversationId.value == conversationId && activeJob?.isActive == true

    fun canSendMessage(conversationId: String): Boolean =
        activeJob?.isActive != true

    fun sendMessage(conversationId: String, content: String) {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        if (activeJob?.isActive == true) {
            secureLogger.log(
                SecureLogger.Level.WARN,
                "ChatExecutionManager",
                "Rejected sendMessage for conversation $conversationId: synthesis session is currently active."
            )
            return
        }

        scope.launch {
            val userMsg = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "user",
                content = trimmed,
                timestamp = System.currentTimeMillis()
            )
            conversationRepository.addMessage(userMsg)
            conversationRepository.updateLastPreview(conversationId, trimmed.take(100))
            _events.tryEmit(ChatExecutionEvent.UserMessageAdded(conversationId, userMsg))

            val patientProfile = conversationRepository.getPatientProfile(conversationId)
            val config = securePreferences.configuration.value
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
                _events.tryEmit(ChatExecutionEvent.GenerationError(conversationId, errorMsg))
                return@launch
            }

            val pendingAssistantId = UUID.randomUUID().toString()
            _activeConversationId.value = conversationId
            _activeAssistantMessageId.value = pendingAssistantId
            _events.tryEmit(ChatExecutionEvent.GenerationStarted(conversationId, pendingAssistantId))

            streamingManager.reset()
            userCancelled = false
            val timeoutMs = reliabilityManager.getAgentTimeoutMs(config.provider)

            backgroundExecutionBridge?.onExecutionStarted(conversationId, trimmed)

            val statusJob = launch {
                streamingManager.currentStatusText.collect { status ->
                    if (status.isNotBlank()) {
                        backgroundExecutionBridge?.onExecutionProgress(conversationId, status)
                    }
                }
            }

            activeJob = launch {
                try {
                    val result = kotlinx.coroutines.withContext(Dispatchers.IO) {
                        reliabilityManager.runWithTimeout(timeoutMs = timeoutMs) {
                            val (agent, _) = koogAgentFactory.createAgent(
                                config = config,
                                conversationId = conversationId,
                                patientProfile = patientProfile,
                                streamingManager = streamingManager,
                                userMessage = trimmed
                            ) ?: throw IllegalStateException("Failed to bind agent model. Verify API keys and network interfaces.")
                            agent.run(trimmed, conversationId)
                        }
                    }
                    handleAgentResult(conversationId, pendingAssistantId, result)
                } catch (e: CancellationException) {
                    if (userCancelled) {
                        throw e
                    }
                    handleAgentError(
                        conversationId = conversationId,
                        messageId = pendingAssistantId,
                        errorMessage = e.message ?: "Request timed out.",
                        errorType = StreamingManager.ErrorType.TIMEOUT
                    )
                } catch (e: Exception) {
                    secureLogger.log(SecureLogger.Level.ERROR, "ChatExecutionManager", "Clinical runtime crash: ${e.message}")
                    handleAgentError(
                        conversationId = conversationId,
                        messageId = pendingAssistantId,
                        errorMessage = e.message ?: "General core execution error.",
                        errorType = StreamingManager.ErrorType.UNKNOWN
                    )
                } finally {
                    statusJob.cancel()
                    _activeConversationId.value = null
                    _activeAssistantMessageId.value = null
                }
            }
        }
    }

    fun cancelGeneration(conversationId: String) {
        val currentActive = _activeConversationId.value
        if (currentActive != null && currentActive != conversationId) {
            return
        }
        userCancelled = true
        activeJob?.cancel()
        activeJob = null
        val pendingId = _activeAssistantMessageId.value
        _activeConversationId.value = null
        _activeAssistantMessageId.value = null

        scope.launch {
            val cancelMsg = MessageEntity(
                id = pendingId ?: UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "cancelled",
                content = "[Clinical generation session terminated by operator]",
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            conversationRepository.addMessage(cancelMsg)
            streamingManager.reset()
            _events.tryEmit(ChatExecutionEvent.GenerationCancelled(conversationId, cancelMsg))
            backgroundExecutionBridge?.onExecutionCancelled(conversationId)
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
                        conversationId = conversationId,
                        messageId = messageId,
                        result = cleanResult,
                        validation = state.validation,
                        usage = state.tokenUsage
                    )
                } else {
                    val blockedReason = state.validation.blockedReason
                        ?: "Output quarantined by safety rules."
                    secureLogger.log(
                        SecureLogger.Level.WARN, "ChatExecutionManager",
                        "Safety blocked: $blockedReason."
                    )
                    val content = if (cleanResult.startsWith(ClinicalAgentStrategy.BLOCKED_MARKER)) {
                        cleanResult
                    } else {
                        "${ClinicalAgentStrategy.BLOCKED_MARKER}$blockedReason"
                    }
                    saveQuarantinedMessage(conversationId, messageId, content, state.validation)
                }
            }
            is StreamingManager.AgentState.Error -> {
                handleAgentError(conversationId, messageId, state.message, state.type)
            }
            else -> {
                secureLogger.log(SecureLogger.Level.WARN, "ChatExecutionManager", "handleAgentResult: unexpected state ${state::class.simpleName}")
                saveFallbackMessage(conversationId, messageId, cleanResult)
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
        conversationRepository.updateTokenCounts(
            conversationId,
            promptDelta = usage.promptTokens,
            completionDelta = usage.completionTokens,
            toolDelta = 0
        )
        conversationRepository.updateLastPreview(conversationId, result.take(100))
        _events.tryEmit(ChatExecutionEvent.AssistantMessageCompleted(conversationId, assistantMsg, validation.warnings))
        backgroundExecutionBridge?.onExecutionCompleted(conversationId, result.take(120), false)
    }

    private suspend fun saveQuarantinedMessage(
        conversationId: String,
        messageId: String,
        content: String,
        validation: SafetyValidator.ValidationResult
    ) {
        val blockedMsg = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            role = "assistant",
            content = content,
            timestamp = System.currentTimeMillis(),
            warningsJson = json.encodeToString(validation.warnings),
            topicRefsJson = json.encodeToString(validation.topicRefs),
            graphicRefsJson = json.encodeToString(validation.graphicRefs),
            isError = true
        )
        conversationRepository.addMessage(blockedMsg)
        conversationRepository.updateLastPreview(conversationId, content.take(100))
        _events.tryEmit(ChatExecutionEvent.AssistantMessageCompleted(conversationId, blockedMsg, validation.warnings))
        backgroundExecutionBridge?.onExecutionCompleted(conversationId, "Output quarantined by safety rules.", false)
    }

    private suspend fun saveFallbackMessage(
        conversationId: String,
        messageId: String,
        result: String
    ) {
        val fallbackResult = result.ifBlank { "Clinical query response generated." }
        val fallbackMsg = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            role = "assistant",
            content = fallbackResult,
            timestamp = System.currentTimeMillis()
        )
        conversationRepository.addMessage(fallbackMsg)
        conversationRepository.updateLastPreview(conversationId, fallbackResult.take(100))
        _events.tryEmit(ChatExecutionEvent.AssistantMessageCompleted(conversationId, fallbackMsg, emptyList()))
        backgroundExecutionBridge?.onExecutionCompleted(conversationId, fallbackResult.take(120), false)
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
        _events.tryEmit(ChatExecutionEvent.GenerationError(conversationId, errorMsg))
        backgroundExecutionBridge?.onExecutionCompleted(conversationId, errorMessage, true)
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
}
