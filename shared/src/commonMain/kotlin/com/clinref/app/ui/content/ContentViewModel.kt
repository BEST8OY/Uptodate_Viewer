package com.clinref.app.ui.content

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.ContributorGroup
import com.clinref.app.domain.ReadingPosition
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.FavoriteRepository
import com.clinref.app.repository.HistoryRepository
import com.clinref.app.repository.ReadingPositionRepository
import com.clinref.app.util.HtmlNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ContentViewModel(
    private val contentRepository: ContentRepository,
    private val favoriteRepository: FavoriteRepository,
    private val historyRepository: HistoryRepository,
    private val readingPositionRepository: ReadingPositionRepository,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    companion object {
        private const val KEY_CURRENT_TOPIC_ID = "current_topic_id"
        private const val KEY_NAVIGATION_HISTORY = "navigation_history"
        private const val KEY_HISTORY_INDEX = "history_index"
    }

    private var _themeColors: ThemeColors? = null
    private var _rawHtml: String? = null
    private var _rawRev: String = ""
    private var currentLoadJob: Job? = null
    private var opened = false
    private var revisionCounter = 0L

    /** Latest position reported by the renderer for the currently displayed document. */
    private var latestPosition: ReadingPosition? = null

    private val _currentTopicId = MutableStateFlow<String?>(savedStateHandle.get<String>(KEY_CURRENT_TOPIC_ID))
    val currentTopicId: StateFlow<String?> = _currentTopicId

    private val _topicContent = MutableStateFlow<ContentRepository.TopicContent?>(null)
    val topicContent: StateFlow<ContentRepository.TopicContent?> = _topicContent

    /** Single source of truth for what the article renderer shows and where it starts. */
    private val _document = MutableStateFlow<ArticleDocument?>(null)
    val document: StateFlow<ArticleDocument?> = _document

    private val _isFavorite = MutableStateFlow(false)
    val isFavorite: StateFlow<Boolean> = _isFavorite

    private val _showOutline = MutableStateFlow(false)
    val showOutline: StateFlow<Boolean> = _showOutline

    private val _outlineSections = MutableStateFlow<List<OutlineSection>>(emptyList())
    val outlineSections: StateFlow<List<OutlineSection>> = _outlineSections

    private val _onNavigateToGraphic = MutableStateFlow<String?>(null)
    val onNavigateToGraphic: StateFlow<String?> = _onNavigateToGraphic

    private val _contributorsDialog = MutableStateFlow<List<ContributorGroup>?>(null)
    val contributorsDialog: StateFlow<List<ContributorGroup>?> = _contributorsDialog

    /**
     * One-shot, imperative "scroll to this section now" commands (outline taps, same-topic links).
     * Distinct from [ArticleDocument.start], which only describes where a fresh load begins.
     */
    private val _sectionJumps = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val sectionJumps: SharedFlow<String> = _sectionJumps.asSharedFlow()

    private val _activeSectionId = MutableStateFlow<String?>(null)
    val activeSectionId: StateFlow<String?> = _activeSectionId

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward

    private val _articleTitle = MutableStateFlow("")
    val articleTitle: StateFlow<String> = _articleTitle

    private val _navigationHistory = MutableStateFlow<List<String>>(
        savedStateHandle.get<List<String>>(KEY_NAVIGATION_HISTORY) ?: emptyList()
    )
    val navigationHistory: StateFlow<List<String>> = _navigationHistory

    private val _historyIndex = MutableStateFlow(
        savedStateHandle.get<Int>(KEY_HISTORY_INDEX) ?: -1
    )
    val historyIndex: StateFlow<Int> = _historyIndex

    private val actions = mutableMapOf<String, String>()

    init {
        val restoredTopicId = _currentTopicId.value
        if (restoredTopicId != null) {
            loadTopic(restoredTopicId, addToHistory = false)
        }
    }

    fun setThemeColors(colors: ThemeColors) {
        val previous = _themeColors ?: ThemeColors.light()
        _themeColors = colors
        if (previous.isDark != colors.isDark) regenerateHtml()
    }

    /**
     * Re-themes the *current* document in place: same topic, same content revision, and the reader's
     * live position as the start target, so a light/dark switch never jumps back to the top.
     */
    private fun regenerateHtml() {
        val doc = _document.value ?: return
        emitDocument(doc.topicId, resolveStart(doc))
    }

    private fun emitDocument(topicId: String, start: StartTarget) {
        val html = _rawHtml ?: return
        _document.value = ArticleDocument(
            topicId = topicId,
            html = buildHtml(html),
            contentRev = _rawRev,
            revision = ++revisionCounter,
            start = start
        )
    }

    /**
     * Where a renderer should start for [document]: the reader's live position when it belongs to
     * this exact content, otherwise the document's own [ArticleDocument.start]. Lets a recreated
     * view (tab switch, rotation) resume where the reader actually is.
     */
    fun resolveStart(document: ArticleDocument): StartTarget =
        latestPosition
            ?.takeIf { it.contentRev == document.contentRev }
            ?.let { StartTarget.Resume(it) }
            ?: document.start

    private fun buildHtml(html: String): String {
        val css = getCss()
        return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            $css
        </head>
        <body>${html.removeSurrounding("\"")}</body>
        </html>
        """.trimIndent()
    }

    /**
     * Idempotent entry point for a route instance. The route is only an *initial hint*: once the
     * ViewModel has a topic (restored, or in-article navigation moved it elsewhere) re-entering the
     * screen (tab switch, return from another screen) must not reload or reset anything.
     */
    fun open(topicId: String, sectionId: String? = null) {
        if (opened) return
        opened = true
        if (_currentTopicId.value != null) return
        resetNavigationHistory()
        loadTopic(topicId, sectionId = sectionId)
    }

    /** Called by the renderer whenever the reader's position changes (debounced by the renderer). */
    fun onPositionChanged(position: ReadingPosition) {
        val doc = _document.value ?: return
        // A late report from a previously displayed document must not be attributed to this one.
        if (position.contentRev != doc.contentRev) return
        latestPosition = position
        readingPositionRepository.save(doc.topicId, position)
        position.sectionId?.let { _activeSectionId.value = it }
    }

    fun scrollToSection(sectionId: String) {
        val clean = normalizeSection(sectionId) ?: return
        _sectionJumps.tryEmit(clean)
    }

    /** `"FULL"` is the internal whole-article sentinel and never a real DOM anchor. */
    private fun normalizeSection(sectionId: String?): String? =
        sectionId?.trim()?.takeIf { it.isNotBlank() && !it.equals("FULL", ignoreCase = true) }

    private suspend fun initialStart(topicId: String, sectionId: String?): StartTarget {
        normalizeSection(sectionId)?.let { return StartTarget.Section(it) }
        val saved = try {
            readingPositionRepository.get(topicId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return saved?.let { StartTarget.Resume(it) } ?: StartTarget.Top
    }

    fun loadTopic(topicId: String, addToHistory: Boolean = true, sectionId: String? = null) {
        currentLoadJob?.cancel()
        currentLoadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _currentTopicId.value = topicId
            savedStateHandle[KEY_CURRENT_TOPIC_ID] = topicId

            val content = try {
                withContext(ioDispatcher) {
                    contentRepository.getTopicContent(topicId)
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Failed to load content"
                _isLoading.value = false
                return@launch
            }

            _topicContent.value = content

            if (content == null) {
                _error.value = "Content not found"
                _articleTitle.value = ""
                _isLoading.value = false
                return@launch
            }

            val title = withContext(ioDispatcher) { contentRepository.getTopicTitle(topicId) } ?: topicId
            _articleTitle.value = title

            var html = content.bodyHtml
            html = HtmlNormalizer.normalizeHeaders(html)
            html = HtmlNormalizer.injectMetaLinks(html, content.contributors)

            actions.clear()
            html = Regex("""href="javascript:appAction\((.*?)\);?"""", RegexOption.DOT_MATCHES_ALL)
                .replace(html) { match ->
                    val jsonStr = match.groupValues[1]
                    val actionId = "action_${actions.size}"
                    actions[actionId] = jsonStr
                    """href="appaction://$actionId""""
                }

            val start = initialStart(topicId, sectionId)
            _rawHtml = html
            _rawRev = html.hashCode().toUInt().toString(16)
            latestPosition = null
            emitDocument(topicId, start)

            if (content.outlineHtml.isNotBlank()) {
                val sections = withContext(Dispatchers.Default) {
                    HtmlNormalizer.parseOutline(content.outlineHtml)
                }
                _outlineSections.value = sections
            } else {
                _outlineSections.value = emptyList()
            }

            if (addToHistory) {
                historyRepository.addOrPromote(topicId, title)
                val history = _navigationHistory.value
                val idx = _historyIndex.value
                val trimmed = if (idx >= 0) history.take(idx + 1) else emptyList()
                val newHistory = trimmed + topicId
                _navigationHistory.value = newHistory
                savedStateHandle[KEY_NAVIGATION_HISTORY] = newHistory
                val newIndex = newHistory.size - 1
                _historyIndex.value = newIndex
                savedStateHandle[KEY_HISTORY_INDEX] = newIndex
            }

            _isFavorite.value = favoriteRepository.isFavorite(topicId)
            _isLoading.value = false
            updateNavigationState()
        }
    }

    fun handleAction(actionId: String) {
        val rawJsonStr = actions[actionId] ?: return
        try {
            val jsonStr = rawJsonStr
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#39;", "'")
            executeActionJson(jsonStr)
        } catch (_: Exception) { }
    }

    fun handleOutlineAction(json: String) {
        try {
            executeActionJson(json)
        } catch (_: Exception) { }
    }

    private fun executeActionJson(jsonStr: String) {
        val data = Json.parseToJsonElement(jsonStr).jsonObject
        val meta = data["meta"]?.jsonObject
        val items = data["data"]?.jsonArray

        val assetType = meta?.get("assetType")?.jsonPrimitive?.content
        val assetComponent = meta?.get("assetComponent")?.jsonPrimitive?.content
        val section = meta?.get("section")?.jsonPrimitive?.content
            ?: items?.firstOrNull()?.jsonObject?.get("section")?.jsonPrimitive?.content

        when {
            assetComponent in listOf("contributors", "disclosures") -> {
                _contributorsDialog.value = _topicContent.value?.contributors
            }
            assetType == "graphic" -> {
                val graphicId = items?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                if (graphicId != null) {
                    _onNavigateToGraphic.value = graphicId
                }
            }
            assetType == "topic" -> {
                val targetTopicId = items?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                if (targetTopicId != null) {
                    if (targetTopicId == _currentTopicId.value && section != null) {
                        scrollToSection(section)
                    } else {
                        loadTopic(targetTopicId)
                    }
                }
            }
        }
    }

    fun toggleOutline() {
        _showOutline.value = !_showOutline.value
    }

    fun toggleFavorite() {
        val topicId = _currentTopicId.value ?: return
        viewModelScope.launch {
            if (_isFavorite.value) {
                favoriteRepository.remove(topicId)
            } else {
                val title = _articleTitle.value.ifEmpty { topicId }
                favoriteRepository.add(topicId, title)
            }
            _isFavorite.value = !_isFavorite.value
        }
    }

    fun clearNavigationToGraphic() {
        _onNavigateToGraphic.value = null
    }

    fun dismissContributorsDialog() {
        _contributorsDialog.value = null
    }

    fun setActiveSection(sectionId: String?) {
        _activeSectionId.value = sectionId
    }

    fun resetNavigationHistory() {
        _navigationHistory.value = emptyList()
        savedStateHandle[KEY_NAVIGATION_HISTORY] = emptyList<String>()
        _historyIndex.value = -1
        savedStateHandle[KEY_HISTORY_INDEX] = -1
        _canGoBack.value = false
        _canGoForward.value = false
    }

    fun goBack() {
        val idx = _historyIndex.value
        if (idx > 0) {
            val history = _navigationHistory.value
            val newIdx = idx - 1
            _historyIndex.value = newIdx
            savedStateHandle[KEY_HISTORY_INDEX] = newIdx
            loadTopic(history[newIdx], addToHistory = false)
        }
    }

    fun goForward() {
        val idx = _historyIndex.value
        val history = _navigationHistory.value
        if (idx < history.size - 1) {
            val newIdx = idx + 1
            _historyIndex.value = newIdx
            savedStateHandle[KEY_HISTORY_INDEX] = newIdx
            loadTopic(history[newIdx], addToHistory = false)
        }
    }

    private fun updateNavigationState() {
        val idx = _historyIndex.value
        val history = _navigationHistory.value
        _canGoBack.value = idx > 0
        _canGoForward.value = idx < history.size - 1
    }

    private fun getCss(): String {
        val colors = _themeColors ?: ThemeColors.light()
        return CssBuilder(colors).buildDocumentCss()
    }
}
