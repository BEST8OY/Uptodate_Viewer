package com.clinref.app.ui.content

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.ContributorGroup
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.FavoriteRepository
import com.clinref.app.repository.HistoryRepository
import com.clinref.app.util.HtmlNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.lifecycle.SavedStateHandle
import javax.inject.Inject

@HiltViewModel
class ContentViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val favoriteRepository: FavoriteRepository,
    private val historyRepository: HistoryRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val KEY_CURRENT_TOPIC_ID = "current_topic_id"
        private const val KEY_NAVIGATION_HISTORY = "navigation_history"
        private const val KEY_HISTORY_INDEX = "history_index"
    }

    private var _themeColors: ThemeColors? = null
    private var _rawHtml: String? = null
    private var currentLoadJob: Job? = null

    private val _currentTopicId = MutableStateFlow<String?>(savedStateHandle.get<String>(KEY_CURRENT_TOPIC_ID))
    val currentTopicId: StateFlow<String?> = _currentTopicId

    private val _topicContent = MutableStateFlow<ContentRepository.TopicContent?>(null)
    val topicContent: StateFlow<ContentRepository.TopicContent?> = _topicContent

    private val _processedHtml = MutableStateFlow<String?>(null)
    val processedHtml: StateFlow<String?> = _processedHtml

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

    private val _scrollToSection = MutableStateFlow<String?>(null)
    val scrollToSection: StateFlow<String?> = _scrollToSection

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
        val changed = _themeColors?.isDark != colors.isDark
        _themeColors = colors
        if (changed) regenerateHtml()
    }

    private fun regenerateHtml() {
        val html = _rawHtml ?: return
        val css = getCss()
        _processedHtml.value = """
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

    fun loadTopic(topicId: String, addToHistory: Boolean = true) {
        currentLoadJob?.cancel()
        currentLoadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            _currentTopicId.value = topicId
            savedStateHandle[KEY_CURRENT_TOPIC_ID] = topicId

            val content = try {
                contentRepository.getTopicContent(topicId)
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

            val title = contentRepository.getTopicTitle(topicId) ?: topicId
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

            _rawHtml = html
            regenerateHtml()

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
                        _scrollToSection.value = section
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

    fun clearScrollToSection() {
        _scrollToSection.value = null
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
        val colors = _themeColors ?: ThemeColors(
            isDark = false,
            bg = "#ffffff",
            surface = "#f5f5f5",
            text = "#000000",
            textSecondary = "#666666",
            textTertiary = "#999999",
            border = "#e0e0e0",
            borderEmphasis = "#cccccc",
            primary = "#1976D2",
            onPrimary = "#ffffff",
            heading = "#1a1a1a",
            drug = "#059669",
            danger = "#e11d48",
            caution = "#d97706",
            grade = "#7c3aed",
            selection = "#1976D2",
            primaryContainer = "#d1e4ff",
            onPrimaryContainer = "#001d36",
            tertiaryContainer = "#f3deff",
            onTertiaryContainer = "#31004a"
        )
        return CssBuilder(colors).buildDocumentCss()
    }
}
