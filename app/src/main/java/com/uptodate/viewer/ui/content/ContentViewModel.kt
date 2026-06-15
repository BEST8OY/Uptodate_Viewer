package com.uptodate.viewer.ui.content

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.data.ContributorGroup
import com.uptodate.viewer.domain.GraphicData
import com.uptodate.viewer.repository.AssetRepository
import com.uptodate.viewer.repository.ContentRepository
import com.uptodate.viewer.repository.FavoriteRepository
import com.uptodate.viewer.repository.HistoryRepository
import com.uptodate.viewer.util.HtmlNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

@HiltViewModel
class ContentViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val favoriteRepository: FavoriteRepository,
    private val historyRepository: HistoryRepository,
    private val assetRepository: AssetRepository
) : ViewModel() {

    private var _themeColors: ThemeColors? = null
    private var _rawHtml: String? = null

    private val _currentTopicId = MutableStateFlow<String?>(null)
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

    private val _graphicDialog = MutableStateFlow<GraphicData?>(null)
    val graphicDialog: StateFlow<GraphicData?> = _graphicDialog

    private val _contributorsDialog = MutableStateFlow<List<ContributorGroup>?>(null)
    val contributorsDialog: StateFlow<List<ContributorGroup>?> = _contributorsDialog

    private val _scrollToSection = MutableStateFlow<String?>(null)
    val scrollToSection: StateFlow<String?> = _scrollToSection

    private val _navigationHistory = MutableStateFlow<List<String>>(emptyList())
    val navigationHistory: StateFlow<List<String>> = _navigationHistory

    private var historyIndex = -1
    private val actions = mutableMapOf<String, String>()

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
        viewModelScope.launch {
            _currentTopicId.value = topicId
            val content = contentRepository.getTopicContent(topicId)
            _topicContent.value = content

            if (content != null) {
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

                if (content.outlineHtml.isNotBlank()) {
                    val sections = withContext(Dispatchers.Default) {
                        HtmlNormalizer.parseOutline(content.outlineHtml)
                    }
                    _outlineSections.value = sections
                } else {
                    _outlineSections.value = emptyList()
                }
            }

            if (addToHistory) {
                val title = contentRepository.getTopicTitle(topicId) ?: topicId
                historyRepository.addOrPromote(topicId, title)
            }

            _isFavorite.value = favoriteRepository.isFavorite(topicId)
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
            val data = Json.parseToJsonElement(jsonStr).jsonObject
            val meta = data["meta"]?.jsonObject
            val items = data["data"]?.jsonArray

            val assetType = meta?.get("assetType")?.jsonPrimitive?.content
            val assetComponent = meta?.get("assetComponent")?.jsonPrimitive?.content

            when {
                assetComponent in listOf("contributors", "disclosures") -> {
                    _contributorsDialog.value = _topicContent.value?.contributors
                }
                assetType == "graphic" -> {
                    val graphicId = items?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                    if (graphicId != null) {
                        val graphic = assetRepository.getGraphic(graphicId)
                        _graphicDialog.value = graphic
                    }
                }
                assetType == "topic" -> {
                    val topicId = items?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                    val section = meta?.get("section")?.jsonPrimitive?.content
                        ?: items?.firstOrNull()?.jsonObject?.get("section")?.jsonPrimitive?.content
                    if (topicId != null) {
                        if (topicId == _currentTopicId.value && section != null) {
                            _scrollToSection.value = section
                        } else {
                            loadTopic(topicId)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    fun handleOutlineAction(json: String) {
        try {
            val data = Json.parseToJsonElement(json).jsonObject
            val meta = data["meta"]?.jsonObject
            val items = data["data"]?.jsonArray
            val assetType = meta?.get("assetType")?.jsonPrimitive?.content
            val section = meta?.get("section")?.jsonPrimitive?.content
                ?: items?.firstOrNull()?.jsonObject?.get("section")?.jsonPrimitive?.content

            when (assetType) {
                "graphic" -> {
                    val graphicId = items?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                    if (graphicId != null) {
                        val graphic = assetRepository.getGraphic(graphicId)
                        _graphicDialog.value = graphic
                    }
                }
                "topic" -> {
                    val topicId = items?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
                    if (topicId != null) {
                        if (topicId == _currentTopicId.value && section != null) {
                            _scrollToSection.value = section
                        } else {
                            loadTopic(topicId)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
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
                val title = contentRepository.getTopicTitle(topicId) ?: topicId
                favoriteRepository.add(topicId, title)
            }
            _isFavorite.value = !_isFavorite.value
        }
    }

    fun dismissGraphicDialog() {
        _graphicDialog.value = null
    }

    fun dismissContributorsDialog() {
        _contributorsDialog.value = null
    }

    fun clearScrollToSection() {
        _scrollToSection.value = null
    }

    fun goBack() {
        if (historyIndex > 0) {
            historyIndex--
            val topicId = _navigationHistory.value[historyIndex]
            loadTopic(topicId, addToHistory = false)
        }
    }

    fun goForward() {
        if (historyIndex < _navigationHistory.value.size - 1) {
            historyIndex++
            val topicId = _navigationHistory.value[historyIndex]
            loadTopic(topicId, addToHistory = false)
        }
    }

    private fun getCss(): String {
        val colors = _themeColors ?: ThemeColors(
            isDark = false,
            bg = "#ffffff",
            surface = "#f5f5f5",
            surfaceAlt = "#fafafa",
            text = "#000000",
            textSecondary = "#666666",
            textTertiary = "#999999",
            border = "#e0e0e0",
            borderEmphasis = "#cccccc",
            primary = "#1976D2",
            onPrimary = "#ffffff",
            heading = "#000000",
            drug = "#059669",
            danger = "#e11d48",
            caution = "#d97706",
            grade = "#7c3aed",
            selection = "#1976D2"
        )
        val builder = CssBuilder(colors)
        return builder.build(
            builder.resetAndBase(),
            builder.layoutContainers(),
            builder.headings(),
            builder.links(),
            builder.contributors(),
            builder.bulletLists(),
            builder.tables(),
            builder.references(),
            builder.drugMonograph(),
            builder.patientEducation(),
            builder.calculator()
        )
    }
}
