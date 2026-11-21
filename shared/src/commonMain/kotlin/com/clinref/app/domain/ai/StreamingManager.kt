package com.clinref.app.domain.ai

import com.clinref.app.data.GraphicContentResponse
import com.clinref.app.data.SearchTopicsResponse
import com.clinref.app.data.TopicOverviewResponse
import com.clinref.app.data.TopicSectionsResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.TimeSource

/**
 * Thread-safe, turn-scoped state machine for live clinical orchestration and token streaming.
 *
 * Key guarantees:
 * - Single atomic [StreamingUiState] emission (no torn reads across phase/steps/text/sources).
 * - Turn epoch ([turnId]) prevents late callbacks from cancelled turns corrupting a new turn.
 * - Parallel tool tracking keeps clinician status accurate until all in-flight tools finish.
 * - Frame-paced streaming text coalescing (~36ms) with 0ms time-to-first-token and immediate
 *   terminal flush.
 */
class StreamingManager(
    private val flushScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val flushIntervalMs: Long = DEFAULT_FLUSH_INTERVAL_MS
) {

    data class TokenUsage(
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val totalTokens: Int = 0,
        val toolTokens: Int = 0
    )

    enum class StepStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    enum class OrchestrationPhase {
        IDLE,
        EVIDENCE_RETRIEVAL,
        SYNTHESIS,
        COMPLETED,
        ERROR
    }

    data class OrchestrationStep(
        val id: String,
        val title: String,
        val detail: String? = null,
        val status: StepStatus = StepStatus.IN_PROGRESS,
        val toolName: String? = null
    )

    data class DiscoveredSource(
        val topicId: String,
        val title: String,
        val isGraphic: Boolean = false,
        val sectionsRead: Int = 0
    ) {
        fun toTopicRef(): TopicRef = TopicRef(
            topicId = topicId,
            sectionId = "",
            label = title,
            topicTitle = title,
            isGraphic = isGraphic
        )
    }

    data class StreamingUiState(
        val conversationId: String? = null,
        val turnId: String? = null,
        val phase: OrchestrationPhase = OrchestrationPhase.IDLE,
        val statusText: String = INITIAL_STATUS_TEXT,
        val steps: List<OrchestrationStep> = emptyList(),
        val activeToolCount: Int = 0,
        val discoveredSources: List<DiscoveredSource> = emptyList(),
        val sectionsReadCount: Int = 0,
        val tablesReadCount: Int = 0,
        val streamingText: String = "",
        val reasoningText: String = "",
        val isReasoning: Boolean = false,
        val hasStartedStreamingText: Boolean = false
    ) {
        val topicsConsultedCount: Int get() = discoveredSources.count { !it.isGraphic }
    }

    sealed class AgentState {
        data object Idle : AgentState()
        data class ToolCallInProgress(
            val toolName: String,
            val args: String,
            val stepIndex: Int
        ) : AgentState()
        data class WaitingForLlm(val stepIndex: Int) : AgentState()
        data class Completed(
            val result: String,
            val topicRefs: List<TopicRef> = emptyList(),
            val graphicRefs: List<GraphicRef> = emptyList(),
            val tokenUsage: TokenUsage = TokenUsage()
        ) : AgentState()
        data class Error(
            val message: String,
            val type: ErrorType
        ) : AgentState()
    }

    enum class ErrorType { INVALID_KEY, NO_NETWORK, RATE_LIMIT, TIMEOUT, NO_RESULTS, UNKNOWN }

    private val json = AiJsonUtils.json
    private val stateLock = Any()

    private val _uiState = MutableStateFlow(StreamingUiState())
    val uiState: StateFlow<StreamingUiState> = _uiState.asStateFlow()

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _currentStatusText = MutableStateFlow(INITIAL_STATUS_TEXT)
    val currentStatusText: StateFlow<String> = _currentStatusText.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    // Mutable turn-local state guarded strictly by stateLock
    private var activeTurnId: String? = null
    private var activeConversationId: String? = null
    private var stepCounter = 0
    private val activeTools = LinkedHashMap<String, ActiveToolInfo>() // stepId -> info
    private var accumulatedUsage = TokenUsage()

    private val knownTopicTitles = LinkedHashMap<String, String>()
    private val readSectionKeys = LinkedHashSet<Pair<String, String>>() // (topicId, sectionId)
    private val readGraphicIds = LinkedHashSet<String>()
    private val sourceMap = LinkedHashMap<String, DiscoveredSource>() // key: "t:<id>" or "g:<id>"

    private val streamingBuffer = StringBuilder()
    private val reasoningBuffer = StringBuilder()
    private val pendingTagBuffer = StringBuilder()
    private var inReasoningBlock = false
    private var isReasoningFlag = false
    private var hasEmittedFirstVisibleToken = false
    private var lastFlushMark: TimeSource.Monotonic.ValueTimeMark? = null
    private var pendingFlushJob: Job? = null

    private data class ActiveToolInfo(
        val toolName: String,
        val statusLabel: String
    )

    /**
     * Begins a fresh orchestration turn. All subsequent callbacks belong to this epoch
     * until [reset] or another [startTurn] is called.
     */
    fun startTurn(conversationId: String, turnId: String) {
        synchronized(stateLock) {
            clearTurnStateLocked()
            activeConversationId = conversationId
            activeTurnId = turnId
            val fresh = StreamingUiState(
                conversationId = conversationId,
                turnId = turnId,
                phase = OrchestrationPhase.EVIDENCE_RETRIEVAL,
                statusText = "Analyzing clinical question…"
            )
            publishStateLocked(fresh, AgentState.WaitingForLlm(0))
        }
    }

    fun onToolCallStarting(toolName: String, args: String, callId: String = "", turnId: String? = null) {
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            val canonicalName = AiJsonUtils.normalizeToolName(toolName)
            stepCounter++
            val stepId = callId.ifBlank { "step_$stepCounter" }

            // Clear pre-tool buffers so intermediate model thoughts never leak into final text
            clearBuffersLocked()

            val (stepTitle, stepDetail, liveStatus) = describeToolStartLocked(canonicalName, args)
            activeTools[stepId] = ActiveToolInfo(canonicalName, liveStatus)

            val newStep = OrchestrationStep(
                id = stepId,
                title = stepTitle,
                detail = stepDetail,
                status = StepStatus.IN_PROGRESS,
                toolName = canonicalName
            )

            val updatedSteps = _uiState.value.steps + newStep
            val status = computeActiveRetrievalStatusLocked()

            publishStateLocked(
                _uiState.value.copy(
                    phase = OrchestrationPhase.EVIDENCE_RETRIEVAL,
                    statusText = status,
                    steps = updatedSteps,
                    activeToolCount = activeTools.size,
                    streamingText = "",
                    reasoningText = "",
                    isReasoning = false,
                    hasStartedStreamingText = false
                ),
                AgentState.ToolCallInProgress(canonicalName, args, stepCounter)
            )
        }
    }

    fun onToolCallCompleted(toolName: String, resultText: String = "", callId: String = "", turnId: String? = null) {
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            val canonicalName = AiJsonUtils.normalizeToolName(toolName)

            val matchedStepId = if (callId.isNotBlank() && activeTools.containsKey(callId)) {
                callId
            } else {
                activeTools.entries.firstOrNull { it.value.toolName == canonicalName }?.key
            }
            if (matchedStepId != null) {
                activeTools.remove(matchedStepId)
            }

            val completionDetail = processToolResultLocked(canonicalName, resultText)

            val updatedSteps = _uiState.value.steps.map { step ->
                val matches = if (callId.isNotBlank()) {
                    step.id == callId
                } else {
                    step.id == matchedStepId || (matchedStepId == null && step.toolName == canonicalName && step.status == StepStatus.IN_PROGRESS)
                }
                if (matches) {
                    step.copy(
                        status = StepStatus.COMPLETED,
                        detail = completionDetail ?: step.detail
                    )
                } else {
                    step
                }
            }

            val status = if (activeTools.isNotEmpty()) {
                computeActiveRetrievalStatusLocked()
            } else {
                "Evaluating retrieved evidence…"
            }

            publishStateLocked(
                _uiState.value.copy(
                    statusText = status,
                    steps = updatedSteps,
                    activeToolCount = activeTools.size,
                    discoveredSources = sourceMap.values.toList(),
                    sectionsReadCount = readSectionKeys.size,
                    tablesReadCount = readGraphicIds.size
                )
            )
        }
    }

    fun onSynthesisStarting(turnId: String? = null) {
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            activeTools.clear()
            clearBuffersLocked()

            val completedSteps = _uiState.value.steps.map { step ->
                if (step.status == StepStatus.IN_PROGRESS) step.copy(status = StepStatus.COMPLETED) else step
            }

            val synthesisStep = OrchestrationStep(
                id = STEP_SYNTHESIS_ID,
                title = "Synthesize Clinical Guidance",
                detail = buildSynthesisStepDetailLocked(),
                status = StepStatus.IN_PROGRESS
            )
            val existingIdx = completedSteps.indexOfFirst { it.id == STEP_SYNTHESIS_ID }
            val updatedSteps = if (existingIdx >= 0) {
                completedSteps.toMutableList().apply { this[existingIdx] = synthesisStep }
            } else {
                completedSteps + synthesisStep
            }

            publishStateLocked(
                _uiState.value.copy(
                    phase = OrchestrationPhase.SYNTHESIS,
                    statusText = buildSynthesisStatusLocked(),
                    steps = updatedSteps,
                    activeToolCount = 0,
                    streamingText = "",
                    reasoningText = "",
                    isReasoning = false,
                    hasStartedStreamingText = false
                ),
                AgentState.WaitingForLlm(stepCounter)
            )
        }
    }

    fun onWaitingForLlm(turnId: String? = null) {
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            if (activeTools.isNotEmpty()) return
            val status = if (_uiState.value.phase == OrchestrationPhase.SYNTHESIS) {
                buildSynthesisStatusLocked()
            } else {
                "Planning next retrieval step…"
            }
            publishStateLocked(
                _uiState.value.copy(statusText = status),
                AgentState.WaitingForLlm(stepCounter)
            )
        }
    }

    fun onStreamingTextDelta(delta: String, turnId: String? = null) {
        if (delta.isEmpty()) return
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            val prevTextLen = streamingBuffer.length
            for (i in delta.indices) {
                processStreamingCharLocked(delta[i])
            }
            val textGrew = streamingBuffer.length > prevTextLen
            if (textGrew && !inReasoningBlock) {
                isReasoningFlag = false
            }

            val isFirstVisibleToken = textGrew && !hasEmittedFirstVisibleToken
            if (isFirstVisibleToken) {
                hasEmittedFirstVisibleToken = true
            }

            scheduleOrFlushLocked(forceImmediate = isFirstVisibleToken)
        }
    }

    fun onStreamingReasoningDelta(delta: String, turnId: String? = null) {
        if (delta.isEmpty()) return
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            reasoningBuffer.append(delta)
            isReasoningFlag = true
            scheduleOrFlushLocked(forceImmediate = !_uiState.value.isReasoning)
        }
    }

    fun onStreamingEnd(turnId: String? = null) {
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            if (pendingTagBuffer.isNotEmpty()) {
                val remaining = pendingTagBuffer.toString()
                pendingTagBuffer.clear()
                appendToActiveBufferLocked(remaining)
            }
            isReasoningFlag = false
            flushBuffersLocked()
        }
    }

    fun onLlmCallCompleted(promptTokens: Int, completionTokens: Int, totalTokens: Int, turnId: String? = null) {
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            accumulatedUsage = TokenUsage(
                promptTokens = accumulatedUsage.promptTokens + promptTokens,
                completionTokens = accumulatedUsage.completionTokens + completionTokens,
                totalTokens = accumulatedUsage.totalTokens + totalTokens,
                toolTokens = accumulatedUsage.toolTokens
            )
        }
    }

    fun onCompleted(
        result: String,
        topicRefs: List<TopicRef> = emptyList(),
        graphicRefs: List<GraphicRef> = emptyList(),
        usage: TokenUsage? = null,
        turnId: String? = null
    ) {
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            pendingFlushJob?.cancel()
            pendingFlushJob = null
            if (usage != null) {
                accumulatedUsage = usage
            }
            activeTools.clear()
            streamingBuffer.clear()
            streamingBuffer.append(result)
            reasoningBuffer.clear()
            pendingTagBuffer.clear()
            inReasoningBlock = false
            isReasoningFlag = false

            val completedSteps = _uiState.value.steps.map { step ->
                if (step.status == StepStatus.IN_PROGRESS) step.copy(status = StepStatus.COMPLETED) else step
            }

            publishStateLocked(
                _uiState.value.copy(
                    phase = OrchestrationPhase.COMPLETED,
                    statusText = "Complete",
                    steps = completedSteps,
                    activeToolCount = 0,
                    streamingText = result,
                    reasoningText = "",
                    isReasoning = false,
                    hasStartedStreamingText = result.isNotBlank()
                ),
                AgentState.Completed(result, topicRefs, graphicRefs, accumulatedUsage)
            )
        }
    }

    fun onError(error: String, turnId: String? = null) {
        synchronized(stateLock) {
            if (!isCurrentTurnLocked(turnId)) return
            activeTools.clear()
            clearBuffersLocked()

            val failedSteps = _uiState.value.steps.map { step ->
                if (step.status == StepStatus.IN_PROGRESS) step.copy(status = StepStatus.FAILED) else step
            }
            val type = classifyError(error)

            publishStateLocked(
                _uiState.value.copy(
                    phase = OrchestrationPhase.ERROR,
                    statusText = "Error",
                    steps = failedSteps,
                    activeToolCount = 0,
                    streamingText = "",
                    reasoningText = "",
                    isReasoning = false,
                    hasStartedStreamingText = false
                ),
                AgentState.Error(error, type)
            )
        }
    }

    /**
     * Resets manager state. If [expectedTurnId] is provided, only resets if it matches the
     * active turn (preventing a finishing/cancelled job's finally block from wiping a newer turn).
     */
    fun reset(expectedTurnId: String? = null) {
        synchronized(stateLock) {
            if (expectedTurnId != null && activeTurnId != null && activeTurnId != expectedTurnId) {
                return
            }
            clearTurnStateLocked()
            activeConversationId = null
            activeTurnId = null
            publishStateLocked(StreamingUiState(), AgentState.Idle)
        }
    }

    private fun isCurrentTurnLocked(turnId: String?): Boolean {
        if (turnId == null) return activeTurnId == null
        return activeTurnId == turnId
    }

    private fun clearTurnStateLocked() {
        stepCounter = 0
        activeTools.clear()
        accumulatedUsage = TokenUsage()
        knownTopicTitles.clear()
        readSectionKeys.clear()
        readGraphicIds.clear()
        sourceMap.clear()
        clearBuffersLocked()
    }

    private fun clearBuffersLocked() {
        pendingFlushJob?.cancel()
        pendingFlushJob = null
        streamingBuffer.clear()
        reasoningBuffer.clear()
        pendingTagBuffer.clear()
        inReasoningBlock = false
        isReasoningFlag = false
        hasEmittedFirstVisibleToken = false
        lastFlushMark = null
        _streamingText.value = ""
    }

    private fun scheduleOrFlushLocked(forceImmediate: Boolean) {
        if (flushIntervalMs <= 0L || forceImmediate) {
            flushBuffersLocked()
            return
        }
        val elapsedMs = lastFlushMark?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE
        if (elapsedMs >= flushIntervalMs) {
            flushBuffersLocked()
            return
        }
        if (pendingFlushJob?.isActive == true) return
        val scheduledTurnId = activeTurnId
        val remainingMs = (flushIntervalMs - elapsedMs).coerceAtLeast(8L)
        pendingFlushJob = flushScope.launch {
            delay(remainingMs)
            synchronized(stateLock) {
                if (activeTurnId != scheduledTurnId) return@launch
                pendingFlushJob = null
                flushBuffersLocked()
            }
        }
    }

    private fun flushBuffersLocked() {
        pendingFlushJob?.cancel()
        pendingFlushJob = null
        lastFlushMark = TimeSource.Monotonic.markNow()
        val text = streamingBuffer.toString()
        val reasoning = reasoningBuffer.toString()
        val cur = _uiState.value
        val next = cur.copy(
            streamingText = text,
            reasoningText = reasoning,
            isReasoning = isReasoningFlag,
            hasStartedStreamingText = cur.hasStartedStreamingText || text.isNotEmpty()
        )
        if (next != cur) {
            _uiState.value = next
        }
        if (_streamingText.value != text) {
            _streamingText.value = text
        }
    }

    private fun publishStateLocked(newState: StreamingUiState, newAgentState: AgentState? = null) {
        _uiState.value = newState
        if (_streamingText.value != newState.streamingText) {
            _streamingText.value = newState.streamingText
        }
        if (_currentStatusText.value != newState.statusText) {
            _currentStatusText.value = newState.statusText
        }
        if (newAgentState != null) {
            _agentState.value = newAgentState
        }
    }

    private fun computeActiveRetrievalStatusLocked(): String {
        val list = activeTools.values.toList()
        if (list.isEmpty()) return "Evaluating clinical evidence…"
        if (list.size == 1) return list.first().statusLabel
        val uniqueNames = list.map { it.toolName }.toSet()
        return when {
            uniqueNames.size == 1 && uniqueNames.first() == "searchTopics" ->
                "Searching ${list.size} clinical queries…"
            uniqueNames.size == 1 && uniqueNames.first() == "getTopicOverview" ->
                "Inspecting ${list.size} topic outlines…"
            uniqueNames.size == 1 && uniqueNames.first() == "getTopicSectionsText" ->
                "Reading sections from ${list.size} topics…"
            uniqueNames.size == 1 && uniqueNames.first() == "getGraphicContent" ->
                "Loading ${list.size} clinical tables…"
            else ->
                "Consulting ${list.size} UpToDate sources…"
        }
    }

    private fun buildSynthesisStepDetailLocked(): String {
        val topics = sourceMap.values.count { !it.isGraphic }
        val sections = readSectionKeys.size
        val tables = readGraphicIds.size
        val parts = buildList {
            if (topics > 0) add("$topics ${if (topics == 1) "topic" else "topics"}")
            if (sections > 0) add("$sections ${if (sections == 1) "section" else "sections"}")
            if (tables > 0) add("$tables ${if (tables == 1) "table" else "tables"}")
        }
        return if (parts.isNotEmpty()) {
            "Grounding guidance in ${parts.joinToString(" · ")}"
        } else {
            "Drafting evidence-grounded guidance"
        }
    }

    private fun buildSynthesisStatusLocked(): String {
        val topics = sourceMap.values.count { !it.isGraphic }
        val sections = readSectionKeys.size
        return if (topics > 0) {
            val tWord = if (topics == 1) "topic" else "topics"
            val sSuffix = if (sections > 0) " · $sections ${if (sections == 1) "section" else "sections"}" else ""
            "Synthesizing from $topics $tWord$sSuffix…"
        } else {
            "Synthesizing point-of-care guidance…"
        }
    }

    private fun describeToolStartLocked(canonicalName: String, args: String): Triple<String, String?, String> {
        return try {
            val cleanArgs = AiJsonUtils.extractJsonString(args).ifBlank { args }
            val element = json.parseToJsonElement(cleanArgs) as? JsonObject
            when (canonicalName) {
                "searchTopics" -> {
                    val query = element?.get("query")?.jsonPrimitive?.contentOrNull
                        ?: element?.get("search_query")?.jsonPrimitive?.contentOrNull
                        ?: element?.get("searchQuery")?.jsonPrimitive?.contentOrNull ?: ""
                    val cleanQ = query.trim()
                    Triple(
                        "Search UpToDate",
                        if (cleanQ.isNotEmpty()) "\"$cleanQ\"" else null,
                        if (cleanQ.isNotEmpty()) "Searching \"$cleanQ\"…" else "Searching UpToDate library…"
                    )
                }
                "getTopicOverview" -> {
                    val topicId = element?.get("topicId")?.jsonPrimitive?.contentOrNull
                        ?: element?.get("topic_id")?.jsonPrimitive?.contentOrNull ?: ""
                    val title = knownTopicTitles[topicId]?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                    Triple(
                        "Inspect Topic Outline",
                        title,
                        if (title != null) "Inspecting \"$title\"…" else "Inspecting topic outline…"
                    )
                }
                "getTopicSectionsText" -> {
                    val topicId = element?.get("topicId")?.jsonPrimitive?.contentOrNull
                        ?: element?.get("topic_id")?.jsonPrimitive?.contentOrNull ?: ""
                    val title = knownTopicTitles[topicId]?.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                    Triple(
                        "Read Clinical Sections",
                        title,
                        if (title != null) "Reading \"$title\"…" else "Reading clinical sections…"
                    )
                }
                "getGraphicContent" -> {
                    val graphicId = element?.get("graphicId")?.jsonPrimitive?.contentOrNull
                        ?: element?.get("graphic_id")?.jsonPrimitive?.contentOrNull ?: ""
                    val cleanId = AiJsonUtils.cleanGraphicId(graphicId)
                    val title = sourceMap["g:$cleanId"]?.title
                    Triple(
                        "Load Reference Table",
                        title ?: cleanId.ifBlank { null },
                        if (title != null) "Loading table \"$title\"…" else "Loading clinical table…"
                    )
                }
                else -> Triple(canonicalName, null, "Running $canonicalName…")
            }
        } catch (_: Exception) {
            Triple(canonicalName, null, "Consulting clinical reference…")
        }
    }

    /**
     * Single-pass decoding of tool result JSON: updates discovered sources, section/table counts,
     * and returns a human-readable completion detail for the orchestration step trace.
     */
    private fun processToolResultLocked(canonicalName: String, resultText: String): String? {
        val unwrapped = AiJsonUtils.extractJsonString(resultText)
        if (unwrapped.isBlank()) return null

        return try {
            when (canonicalName) {
                "searchTopics" -> {
                    val res = json.decodeFromString<SearchTopicsResponse>(unwrapped)
                    for (item in res.results) {
                        if (item.id.isNotBlank() && AiJsonUtils.isValidTopicTitle(item.title)) {
                            knownTopicTitles[item.id] = AiJsonUtils.cleanHtmlText(item.title)
                        }
                    }
                    val count = res.results.size
                    when {
                        count > 0 -> "$count candidate ${if (count == 1) "topic" else "topics"}"
                        res.message.startsWith("Auto-refined", ignoreCase = true) -> res.message
                        else -> "No matching topics"
                    }
                }
                "getTopicOverview" -> {
                    val res = json.decodeFromString<TopicOverviewResponse>(unwrapped)
                    val topicId = res.topicId.trim()
                    val title = res.title.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                        ?.let { AiJsonUtils.cleanHtmlText(it) }
                        ?: knownTopicTitles[topicId]

                    if (topicId.isNotBlank() && !title.isNullOrBlank()) {
                        knownTopicTitles[topicId] = title
                        val key = "t:$topicId"
                        val existing = sourceMap[key]
                        sourceMap[key] = existing?.copy(title = title)
                            ?: DiscoveredSource(topicId = topicId, title = title, isGraphic = false)
                    }
                    if (res.topicType.equals("calc", ignoreCase = true) && topicId.isNotBlank()) {
                        readSectionKeys.add(topicId to "FULL")
                    }
                    val count = res.sections.size
                    val sWord = if (count == 1) "section" else "sections"
                    if (!title.isNullOrBlank()) "$title ($count $sWord)" else "$count $sWord in outline"
                }
                "getTopicSectionsText" -> {
                    val res = json.decodeFromString<TopicSectionsResponse>(unwrapped)
                    val topicId = res.topicId.trim()
                    val title = res.topicTitle.takeIf { AiJsonUtils.isValidTopicTitle(it) }
                        ?.let { AiJsonUtils.cleanHtmlText(it) }
                        ?: knownTopicTitles[topicId]

                    if (topicId.isNotBlank() && !title.isNullOrBlank()) {
                        knownTopicTitles[topicId] = title
                    }

                    res.sectionTitles.keys.forEach { secId ->
                        if (topicId.isNotBlank() && secId.isNotBlank()) {
                            readSectionKeys.add(topicId to secId)
                        }
                    }
                    val totalForTopic = if (topicId.isNotBlank()) {
                        readSectionKeys.count { it.first == topicId }
                    } else 0

                    if (topicId.isNotBlank() && !title.isNullOrBlank()) {
                        val key = "t:$topicId"
                        val existing = sourceMap[key]
                        sourceMap[key] = (existing ?: DiscoveredSource(topicId = topicId, title = title))
                            .copy(title = title, sectionsRead = totalForTopic)
                    }

                    val fetched = res.sectionTitles.size
                    val sWord = if (fetched == 1) "section" else "sections"
                    when {
                        !title.isNullOrBlank() && fetched > 0 -> "$fetched $sWord from $title"
                        fetched > 0 -> "$fetched $sWord retrieved"
                        !title.isNullOrBlank() -> "No sections matched in $title"
                        else -> "No sections matched"
                    }
                }
                "getGraphicContent" -> {
                    val res = json.decodeFromString<GraphicContentResponse>(unwrapped)
                    val cleanId = AiJsonUtils.cleanGraphicId(res.graphicId)
                    val cleanTitle = AiJsonUtils.cleanHtmlText(res.title)
                    if (cleanId.isNotBlank()) {
                        readGraphicIds.add(cleanId)
                        if (cleanTitle.isNotBlank()) {
                            sourceMap["g:$cleanId"] = DiscoveredSource(
                                topicId = cleanId,
                                title = cleanTitle,
                                isGraphic = true
                            )
                        }
                    }
                    cleanTitle.ifBlank { "Clinical table loaded" }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun processStreamingCharLocked(ch: Char) {
        if (pendingTagBuffer.isNotEmpty()) {
            pendingTagBuffer.append(ch)
            if (ch == '>') {
                val candidate = pendingTagBuffer.toString()
                pendingTagBuffer.clear()
                when {
                    isOpeningReasoningTag(candidate) -> {
                        inReasoningBlock = true
                        isReasoningFlag = true
                    }
                    isClosingReasoningTag(candidate) -> {
                        inReasoningBlock = false
                        isReasoningFlag = false
                    }
                    else -> appendToActiveBufferLocked(candidate)
                }
            } else if (!isPotentialTagPrefix(pendingTagBuffer.toString()) || pendingTagBuffer.length > 64) {
                val notTag = pendingTagBuffer.toString()
                pendingTagBuffer.clear()
                appendToActiveBufferLocked(notTag)
            }
        } else if (ch == '<') {
            pendingTagBuffer.append(ch)
        } else {
            appendToActiveBufferLocked(ch.toString())
        }
    }

    private fun appendToActiveBufferLocked(text: String) {
        if (inReasoningBlock) {
            reasoningBuffer.append(text)
        } else {
            if (streamingBuffer.isEmpty() && text.all { it == '\n' || it == '\r' || it == ' ' }) {
                return
            }
            streamingBuffer.append(text)
        }
    }

    private fun isOpeningReasoningTag(tag: String): Boolean {
        val lower = tag.lowercase()
        return lower == "<thought>" || lower == "<think>" || lower == "<reasoning>" || lower == "<thinking>" ||
            lower.startsWith("<thought ") || lower.startsWith("<think ") ||
            lower.startsWith("<reasoning ") || lower.startsWith("<thinking ")
    }

    private fun isClosingReasoningTag(tag: String): Boolean {
        val lower = tag.lowercase()
        return lower == "</thought>" || lower == "</think>" || lower == "</reasoning>" || lower == "</thinking>"
    }

    private fun isPotentialTagPrefix(prefix: String): Boolean {
        val lower = prefix.lowercase()
        if (REASONING_TAG_PREFIXES.any { it.startsWith(lower) }) return true
        return lower.startsWith("<thought ") || lower.startsWith("<think ") ||
            lower.startsWith("<reasoning ") || lower.startsWith("<thinking ")
    }

    fun classifyError(error: String, throwable: Throwable? = null): ErrorType {
        var current: Throwable? = throwable
        var hasTimeout = false
        var hasNetwork = false
        var hasAuth = false
        var hasRateLimit = false

        while (current != null) {
            val msg = current.message?.lowercase().orEmpty()
            val cls = current::class.simpleName?.lowercase().orEmpty()

            if ("timeout" in msg || "timeout" in cls || "deadline" in msg || "time out" in msg) hasTimeout = true
            if ("connect" in msg || "network" in msg || "unreachable" in msg || "unknownhost" in msg || "unknownhost" in cls) hasNetwork = true
            if ("401" in msg || "unauthorized" in msg || "api key" in msg || "forbidden" in msg || "403" in msg) hasAuth = true
            if ("429" in msg || "rate" in msg || "quota" in msg || "exhausted" in msg) hasRateLimit = true
            current = current.cause
        }

        if (hasAuth) return ErrorType.INVALID_KEY
        if (hasRateLimit) return ErrorType.RATE_LIMIT
        if (hasTimeout) return ErrorType.TIMEOUT
        if (hasNetwork) return ErrorType.NO_NETWORK

        val lower = error.lowercase()
        return when {
            "api key" in lower || "unauthorized" in lower || "401" in lower || "forbidden" in lower -> ErrorType.INVALID_KEY
            "timeout" in lower || "deadline" in lower || "time out" in lower -> ErrorType.TIMEOUT
            "rate" in lower || "429" in lower || "quota" in lower || "exhausted" in lower -> ErrorType.RATE_LIMIT
            "network" in lower || "connect" in lower || "unreachable" in lower || "unknownhost" in lower -> ErrorType.NO_NETWORK
            "no topic match" in lower || "no results for" in lower || "no clinical data" in lower -> ErrorType.NO_RESULTS
            else -> ErrorType.UNKNOWN
        }
    }

    companion object {
        const val INITIAL_STATUS_TEXT = "Initializing clinical core…"
        private const val STEP_SYNTHESIS_ID = "step_synthesis"
        private const val DEFAULT_FLUSH_INTERVAL_MS = 36L

        private val REASONING_TAG_PREFIXES = listOf(
            "<thought", "<think", "<reasoning", "<thinking",
            "</thought", "</think", "</reasoning", "</thinking"
        )
    }
}
