package com.clinref.app.domain.ai

import com.clinref.app.data.local.entity.MessageEntity
import com.clinref.app.repository.ConversationRepository
import com.clinref.shared.secure.SecurePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
    private val koogAgentFactory: KoogAgentFactory,
    private val clinicalOrchestrator: ClinicalOrchestrator,
    private val streamingManager: StreamingManager,
    private val reliabilityManager: ReliabilityManager,
    private val securePreferences: SecurePreferences,
    private val secureLogger: SecureLogger,
    private val backgroundExecutionBridge: BackgroundExecutionBridge? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _activeAssistantMessageId = MutableStateFlow<String?>(null)
    val activeAssistantMessageId: StateFlow<String?> = _activeAssistantMessageId.asStateFlow()

    internal class TurnContext(
        val messageId: String,
        var userCancelled: Boolean = false,
        var terminalEventEmitted: Boolean = false
    )

    private var activeJob: Job? = null
    private var activeTurn: TurnContext? = null
    private var idleCancelled = false

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

    fun sendMessage(conversationId: String, content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return false
        if (activeJob?.isActive == true) {
            secureLogger.log(
                SecureLogger.Level.WARN,
                "ChatExecutionManager",
                "Rejected sendMessage for conversation $conversationId: synthesis session is currently active."
            )
            return false
        }

        scope.launch {
            streamingManager.reset()
            val pendingAssistantId = UUID.randomUUID().toString()
            val turnContext = TurnContext(messageId = pendingAssistantId)
            activeTurn = turnContext
            idleCancelled = false
            _activeConversationId.value = conversationId
            _activeAssistantMessageId.value = pendingAssistantId
            _events.tryEmit(ChatExecutionEvent.GenerationStarted(conversationId, pendingAssistantId))

            val patientProfile: PatientProfile?
            val config: AiConfiguration
            try {
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

                patientProfile = conversationRepository.getPatientProfile(conversationId)
                config = securePreferences.configuration.value
                if (!config.isConfigured) {
                    val errorMsg = MessageEntity(
                        id = pendingAssistantId,
                        conversationId = conversationId,
                        role = "assistant",
                        content = "The clinical AI core is unconfigured. Please finalize provider keys in clinical settings.",
                        timestamp = System.currentTimeMillis(),
                        isError = true
                    )
                    try {
                        conversationRepository.addMessage(errorMsg)
                    } catch (e: Exception) {
                        secureLogger.log(SecureLogger.Level.ERROR, "ChatExecutionManager", "Failed to persist unconfigured error: ${e.message}")
                    }
                    _activeConversationId.value = null
                    _activeAssistantMessageId.value = null
                    streamingManager.reset()
                    turnContext.terminalEventEmitted = true
                    if (activeTurn === turnContext) activeTurn = null
                    _events.tryEmit(ChatExecutionEvent.GenerationError(conversationId, errorMsg))
                    return@launch
                }
            } catch (e: Exception) {
                secureLogger.log(SecureLogger.Level.ERROR, "ChatExecutionManager", "Initialization error in sendMessage: ${e.message}")
                _activeConversationId.value = null
                _activeAssistantMessageId.value = null
                streamingManager.reset()
                val initErrorMsg = MessageEntity(
                    id = pendingAssistantId,
                    conversationId = conversationId,
                    role = "assistant",
                    content = "Failed to initialize conversation message: ${e.message ?: "Unknown error"}",
                    timestamp = System.currentTimeMillis(),
                    isError = true
                )
                try {
                    conversationRepository.addMessage(initErrorMsg)
                } catch (_: Exception) {
                    // Best effort persistence in case storage failure caused initialization error
                }
                turnContext.terminalEventEmitted = true
                if (activeTurn === turnContext) activeTurn = null
                _events.tryEmit(ChatExecutionEvent.GenerationError(conversationId, initErrorMsg))
                return@launch
            }

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
                    val result = kotlinx.coroutines.withContext(ioDispatcher) {
                        reliabilityManager.runWithTimeout(timeoutMs = timeoutMs) {
                            val runtime = koogAgentFactory.resolveRuntime(config)
                                ?: throw IllegalStateException("Failed to bind agent model. Verify API keys and network interfaces.")
                            val history = conversationRepository.getMessagesPage(
                                conversationId,
                                ClinicalOrchestrator.MAX_CHAT_HISTORY_TURNS,
                                0
                            )
                            clinicalOrchestrator.runTurn(
                                question = trimmed,
                                patientProfile = patientProfile,
                                chatHistory = history.reversed(),
                                executor = runtime.first,
                                model = runtime.second,
                                providerParams = runtime.third,
                                streamingManager = streamingManager,
                                isAnthropic = config.provider == AiProvider.ANTHROPIC
                            )
                        }
                    }
                    handleAgentResult(conversationId, pendingAssistantId, result.content, turnContext)
                } catch (e: CancellationException) {
                    if (turnContext.userCancelled) {
                        throw e
                    }
                    handleAgentError(
                        conversationId = conversationId,
                        messageId = pendingAssistantId,
                        errorMessage = e.message ?: "Request timed out.",
                        errorType = StreamingManager.ErrorType.TIMEOUT,
                        turnContext = turnContext
                    )
                } catch (e: Exception) {
                    secureLogger.log(SecureLogger.Level.ERROR, "ChatExecutionManager", "Clinical runtime crash: ${e.message}")
                    handleAgentError(
                        conversationId = conversationId,
                        messageId = pendingAssistantId,
                        errorMessage = e.message ?: "General core execution error.",
                        errorType = StreamingManager.ErrorType.UNKNOWN,
                        turnContext = turnContext
                    )
                } finally {
                    statusJob.cancel()
                    if (activeTurn === turnContext) {
                        activeTurn = null
                    }
                    if (_activeAssistantMessageId.value == pendingAssistantId) {
                        _activeConversationId.value = null
                        _activeAssistantMessageId.value = null
                        streamingManager.reset()
                    }
                    idleCancelled = true
                    if (!turnContext.terminalEventEmitted && !turnContext.userCancelled) {
                        turnContext.terminalEventEmitted = true
                        val fallbackError = MessageEntity(
                            id = pendingAssistantId,
                            conversationId = conversationId,
                            role = "assistant",
                            content = "Clinical synthesis terminated without event.",
                            timestamp = System.currentTimeMillis(),
                            isError = true
                        )
                        try {
                            conversationRepository.addMessage(fallbackError)
                        } catch (_: Exception) {
                            // Best effort persistence
                        }
                        _events.tryEmit(ChatExecutionEvent.GenerationError(conversationId, fallbackError))
                    }
                }
            }
        }
        return true
    }

    fun cancelGeneration(conversationId: String) {
        val currentActive = _activeConversationId.value
        if (currentActive != null && currentActive != conversationId) {
            return
        }
        val turn = activeTurn
        if (currentActive == null && (turn == null && idleCancelled)) {
            return
        }
        if (currentActive == null) {
            idleCancelled = true
        }
        val job = activeJob
        turn?.userCancelled = true
        activeTurn = null
        activeJob = null
        job?.cancel()
        val pendingId = _activeAssistantMessageId.value
        _activeConversationId.value = null
        _activeAssistantMessageId.value = null
        streamingManager.reset()

        scope.launch {
            val cancelMsg = MessageEntity(
                id = pendingId ?: UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "cancelled",
                content = "[Clinical generation session terminated by operator]",
                timestamp = System.currentTimeMillis(),
                isError = true
            )
            try {
                conversationRepository.addMessage(cancelMsg)
            } catch (e: Exception) {
                secureLogger.log(SecureLogger.Level.ERROR, "ChatExecutionManager", "Failed to persist cancellation: ${e.message}")
            }
            turn?.terminalEventEmitted = true
            _events.tryEmit(ChatExecutionEvent.GenerationCancelled(conversationId, cancelMsg))
            backgroundExecutionBridge?.onExecutionCancelled(conversationId)
        }
    }

    companion object {
        const val BLOCKED_MARKER = "Clinical Response Verification Blocked: "
    }

    internal suspend fun handleAgentResult(
        conversationId: String,
        messageId: String,
        result: String,
        turnContext: TurnContext? = null
    ) {
        val cleanResult = ReasoningTokenSanitizer.stripReasoning(result)
        when (val state = streamingManager.agentState.value) {
            is StreamingManager.AgentState.Completed -> {
                val hasWrongNumbers = state.validation.violations.any {
                    it.code == ViolationCodes.UNVERIFIED_QTY || it.code == ViolationCodes.UNRETRIEVED_DOSES
                }
                if (state.validation.passed && !cleanResult.startsWith(BLOCKED_MARKER)) {
                    saveSuccessfulMessage(
                        conversationId = conversationId,
                        messageId = messageId,
                        result = cleanResult,
                        validation = state.validation,
                        usage = state.tokenUsage,
                        turnContext = turnContext
                    )
                } else if (hasWrongNumbers && cleanResult.isNotBlank() && !cleanResult.startsWith(BLOCKED_MARKER)) {
                    val effectiveValidation = if (state.validation.warnings.isEmpty()) {
                        state.validation.copy(
                            warnings = listOf(
                                state.validation.blockedReason
                                    ?: "Clinical response contains unverified clinical quantities."
                            )
                        )
                    } else {
                        state.validation
                    }
                    secureLogger.log(
                        SecureLogger.Level.WARN, "ChatExecutionManager",
                        "Safety validator blocked wrong numbers; displaying final LLM message with warnings: ${effectiveValidation.warnings.firstOrNull()}."
                    )
                    saveSuccessfulMessage(
                        conversationId = conversationId,
                        messageId = messageId,
                        result = cleanResult,
                        validation = effectiveValidation,
                        usage = state.tokenUsage,
                        turnContext = turnContext
                    )
                } else {
                    val blockedReason = state.validation.blockedReason
                        ?: "Output quarantined by safety rules."
                    secureLogger.log(
                        SecureLogger.Level.WARN, "ChatExecutionManager",
                        "Safety blocked: $blockedReason."
                    )
                    val content = if (cleanResult.startsWith(BLOCKED_MARKER)) {
                        cleanResult
                    } else {
                        "$BLOCKED_MARKER$blockedReason"
                    }
                    saveQuarantinedMessage(conversationId, messageId, content, state.validation, turnContext)
                }
            }
            is StreamingManager.AgentState.Error -> {
                handleAgentError(conversationId, messageId, state.message, state.type, turnContext)
            }
            else -> {
                secureLogger.log(SecureLogger.Level.WARN, "ChatExecutionManager", "handleAgentResult: unexpected state ${state::class.simpleName}")
                saveFallbackMessage(conversationId, messageId, cleanResult, turnContext)
            }
        }
    }

    private suspend fun saveSuccessfulMessage(
        conversationId: String,
        messageId: String,
        result: String,
        validation: SafetyValidator.ValidationResult,
        usage: StreamingManager.TokenUsage,
        turnContext: TurnContext? = null
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
        try {
            conversationRepository.addMessage(assistantMsg)
            conversationRepository.updateTokenCounts(
                conversationId,
                promptDelta = usage.promptTokens,
                completionDelta = usage.completionTokens,
                toolDelta = 0
            )
            conversationRepository.updateLastPreview(conversationId, result.take(100))
        } catch (e: Exception) {
            secureLogger.log(SecureLogger.Level.ERROR, "ChatExecutionManager", "Failed to persist completed message: ${e.message}")
        }
        markTurnTerminal(messageId, turnContext)
        _events.tryEmit(ChatExecutionEvent.AssistantMessageCompleted(conversationId, assistantMsg, validation.warnings))
        backgroundExecutionBridge?.onExecutionCompleted(conversationId, result.take(120), false)
    }

    private suspend fun saveQuarantinedMessage(
        conversationId: String,
        messageId: String,
        content: String,
        validation: SafetyValidator.ValidationResult,
        turnContext: TurnContext? = null
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
        try {
            conversationRepository.addMessage(blockedMsg)
            conversationRepository.updateLastPreview(conversationId, content.take(100))
        } catch (e: Exception) {
            secureLogger.log(SecureLogger.Level.ERROR, "ChatExecutionManager", "Failed to persist quarantined message: ${e.message}")
        }
        markTurnTerminal(messageId, turnContext)
        _events.tryEmit(ChatExecutionEvent.AssistantMessageCompleted(conversationId, blockedMsg, validation.warnings))
        backgroundExecutionBridge?.onExecutionCompleted(conversationId, "Output quarantined by safety rules.", false)
    }

    private suspend fun saveFallbackMessage(
        conversationId: String,
        messageId: String,
        result: String,
        turnContext: TurnContext? = null
    ) {
        val fallbackResult = result.ifBlank { "Clinical query response generated." }
        val fallbackMsg = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            role = "assistant",
            content = fallbackResult,
            timestamp = System.currentTimeMillis()
        )
        try {
            conversationRepository.addMessage(fallbackMsg)
            conversationRepository.updateLastPreview(conversationId, fallbackResult.take(100))
        } catch (e: Exception) {
            secureLogger.log(SecureLogger.Level.ERROR, "ChatExecutionManager", "Failed to persist fallback message: ${e.message}")
        }
        markTurnTerminal(messageId, turnContext)
        _events.tryEmit(ChatExecutionEvent.AssistantMessageCompleted(conversationId, fallbackMsg, emptyList()))
        backgroundExecutionBridge?.onExecutionCompleted(conversationId, fallbackResult.take(120), false)
    }

    private suspend fun handleAgentError(
        conversationId: String,
        messageId: String,
        errorMessage: String,
        errorType: StreamingManager.ErrorType,
        turnContext: TurnContext? = null
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
        try {
            conversationRepository.addMessage(errorMsg)
        } catch (e: Exception) {
            secureLogger.log(SecureLogger.Level.ERROR, "ChatExecutionManager", "Failed to persist error message: ${e.message}")
        }
        markTurnTerminal(messageId, turnContext)
        _events.tryEmit(ChatExecutionEvent.GenerationError(conversationId, errorMsg))
        backgroundExecutionBridge?.onExecutionCompleted(conversationId, errorMessage, true)
    }

    private fun markTurnTerminal(messageId: String, turnContext: TurnContext?) {
        val effectiveTurn = turnContext ?: if (activeTurn?.messageId == messageId) activeTurn else null
        effectiveTurn?.terminalEventEmitted = true
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
