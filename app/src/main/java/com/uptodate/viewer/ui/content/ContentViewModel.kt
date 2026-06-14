package com.uptodate.viewer.ui.content

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.domain.GraphicData
import com.uptodate.viewer.repository.AssetRepository
import com.uptodate.viewer.repository.ContentRepository
import com.uptodate.viewer.repository.FavoriteRepository
import com.uptodate.viewer.repository.HistoryRepository
import com.uptodate.viewer.util.HtmlNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    private val _outlineHtml = MutableStateFlow<String?>(null)
    val outlineHtml: StateFlow<String?> = _outlineHtml

    private val _graphicDialog = MutableStateFlow<GraphicData?>(null)
    val graphicDialog: StateFlow<GraphicData?> = _graphicDialog

    private val _contributorsDialog = MutableStateFlow<List<Map<String, Any?>>?>(null)
    val contributorsDialog: StateFlow<List<Map<String, Any?>>?> = _contributorsDialog

    private val _scrollToSection = MutableStateFlow<String?>(null)
    val scrollToSection: StateFlow<String?> = _scrollToSection

    private val _navigationHistory = MutableStateFlow<List<String>>(emptyList())
    val navigationHistory: StateFlow<List<String>> = _navigationHistory

    private var historyIndex = -1
    private val actions = mutableMapOf<String, String>()

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
                    var outline = content.outlineHtml
                    outline = Regex("""href="javascript:appAction\((.*?)\);?"""", RegexOption.DOT_MATCHES_ALL)
                        .replace(outline) { match ->
                            val jsonStr = match.groupValues[1]
                            val actionId = "action_${actions.size}"
                            actions[actionId] = jsonStr
                            """href="appaction://$actionId""""
                        }
                    _outlineHtml.value = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        $css
                    </head>
                    <body class="outline-mode"><div class="topic-outline">$outline</div></body>
                    </html>
                    """.trimIndent()
                } else {
                    _outlineHtml.value = null
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
        val jsonStr = actions[actionId] ?: return
        try {
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
        return """
        <style>
            body { font-family: sans-serif; padding: 16px; line-height: 1.6; }
            h1, h2, h3, h4, h5, h6 { color: #333; margin-top: 1.5em; }
            h1 { font-size: 1.8em; border-bottom: 2px solid #eee; padding-bottom: 0.3em; }
            h2 { font-size: 1.5em; }
            a { color: #1976D2; text-decoration: none; }
            a:hover { text-decoration: underline; }
            .topic-title { font-size: 1.8em; color: #333; }
            .meta-links-row { margin: 10px 0; padding: 8px; background: #f5f5f5; border-radius: 4px; }
            .meta-links-row a { margin: 0 8px; }
            .meta-separator { color: #ccc; }
            .outline-mode { padding: 8px; font-size: 0.9em; }
            .topic-outline ul { list-style: none; padding-left: 16px; }
            .topic-outline li { margin: 4px 0; }
            .contributor-group-title { font-weight: bold; margin-top: 16px; }
            .contributor-list { list-style: none; padding: 0; }
            .contributor-name { font-weight: bold; }
            .contributor-associations { color: #555; }
            .contributor-disclosure { font-style: italic; color: #7f8c8d; }
        </style>
        """.trimIndent()
    }
}
