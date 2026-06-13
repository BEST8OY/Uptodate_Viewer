package com.uptodate.viewer.ui.content

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uptodate.viewer.data.database.content.ContentRepository
import com.uptodate.viewer.data.database.search.SearchRepository
import com.uptodate.viewer.domain.model.ContributorGroup
import com.uptodate.viewer.domain.model.GraphicEntry
import com.uptodate.viewer.domain.persistence.FavoritesRepository
import com.uptodate.viewer.domain.persistence.HistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

class NavigationHistory(private val maxHistory: Int = 50) {
    private val entries = mutableListOf<String>()
    private var index = 0

    val current: String get() = entries[index]
    val canGoBack: Boolean get() = index > 0
    val canGoForward: Boolean get() = index < entries.size - 1

    fun init(topicId: String) {
        entries.clear()
        entries.add(topicId)
        index = 0
    }

    fun navigate(topicId: String) {
        entries.subList(index + 1, entries.size).clear()
        entries.add(topicId)
        while (entries.size > maxHistory) entries.removeAt(0)
        index = entries.size - 1
    }

    fun goBack(): String? {
        if (!canGoBack) return null
        index--
        return entries[index]
    }

    fun goForward(): String? {
        if (!canGoForward) return null
        index++
        return entries[index]
    }
}

@HiltViewModel
class ContentViewModel @Inject constructor(
    private val contentRepository: ContentRepository,
    private val searchRepository: SearchRepository,
    private val favoritesRepository: FavoritesRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    data class TopicContentData(
        val topicId: String,
        val title: String,
        val rawBodyHtml: String,
        val rawOutlineHtml: String,
        val contributors: List<ContributorGroup>?,
        val relatedGraphics: List<GraphicEntry>,
        val isFavorite: Boolean
    )

    data class UiState(
        val topicId: String = "",
        val content: TopicContentData? = null,
        val title: String = "",
        val zoomPercent: Int = 100,
        val showOutline: Boolean = false,
        val showFind: Boolean = false,
        val findQuery: String = "",
        val isLoading: Boolean = true
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val navigationHistory = NavigationHistory()
    val actions: MutableMap<String, String> = mutableMapOf()

    sealed class NavEvent {
        data class OpenGraphic(val graphicId: String) : NavEvent()
    }

    private val navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEventFlow = navEvents.receiveAsFlow()

    private var initialized = false
    private var loadJob: Job? = null

    fun initTopic(topicId: String) {
        if (initialized) return
        initialized = true
        viewModelScope.launch(Dispatchers.IO) { favoritesRepository.load() }
        navigationHistory.init(topicId)
        loadTopicContent(topicId)
    }

    fun loadTopicContent(topicId: String) {
        loadJob?.cancel()
        _state.value = _state.value.copy(isLoading = true)
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val content = contentRepository.getTopicContent(topicId)
            val title = searchRepository.getTopicTitle(topicId) ?: topicId

            if (content == null) {
                _state.value = UiState(topicId = topicId, title = "Not Found", isLoading = false)
                return@launch
            }

            var body = HtmlProcessor.normalizeHeaders(content.bodyHtml)
            val domainContributors = content.contributors?.map { it.toDomain() }
            body = HtmlProcessor.injectMetaLinks(body, domainContributors)
            val (processedBody, bodyActions) = HtmlProcessor.extractActions(body)
            actions.clear()
            actions.putAll(bodyActions)

            var processedOutline = ""
            if (content.outlineHtml != null) {
                val (outline, outlineActions) = HtmlProcessor.extractActions(content.outlineHtml)
                processedOutline = outline
                actions.putAll(outlineActions)
            }

            val graphics = content.relatedGraphics?.flatMap { group ->
                group.graphics?.mapNotNull { g ->
                    val info = g.graphicInfo ?: return@mapNotNull null
                    GraphicEntry(g.label, info.id, info.displayName)
                } ?: emptyList()
            } ?: emptyList()

            val isFav = favoritesRepository.isFavorite(topicId)
            val topicData = TopicContentData(
                topicId = topicId, title = title,
                rawBodyHtml = processedBody, rawOutlineHtml = processedOutline,
                contributors = domainContributors,
                relatedGraphics = graphics, isFavorite = isFav
            )

            _state.value = UiState(
                topicId = topicId, content = topicData, title = title,
                isLoading = false
            )

            historyRepository.addOrPromote(topicId, title)
        }
    }

    fun navigate(newTopicId: String) {
        navigationHistory.navigate(newTopicId)
        loadTopicContent(newTopicId)
    }

    fun goBack(): Boolean {
        val topicId = navigationHistory.goBack() ?: return false
        loadTopicContent(topicId)
        return true
    }

    fun goForward(): Boolean {
        val topicId = navigationHistory.goForward() ?: return false
        loadTopicContent(topicId)
        return true
    }

    val canGoBack: Boolean get() = navigationHistory.canGoBack
    val canGoForward: Boolean get() = navigationHistory.canGoForward

    fun toggleFavorite() {
        val s = _state.value.content ?: return
        val newState = favoritesRepository.toggle(s.topicId, s.title)
        _state.value = _state.value.copy(
            content = s.copy(isFavorite = newState)
        )
    }

    fun setZoom(percent: Int) {
        _state.value = _state.value.copy(zoomPercent = percent)
    }

    fun toggleOutline() {
        _state.value = _state.value.copy(showOutline = !_state.value.showOutline)
    }

    fun showFind() {
        _state.value = _state.value.copy(showFind = true)
    }

    fun hideFind() {
        _state.value = _state.value.copy(showFind = false, findQuery = "")
    }

    fun setFindQuery(query: String) {
        _state.value = _state.value.copy(findQuery = query)
    }

    fun handleActionUrl(actionId: String) {
        val json = actions[actionId] ?: return
        try {
            val trimmed = json.trim().removeSurrounding("\"").removeSuffix(";")
            val parts = trimmed.split(",").map { it.trim().removeSurrounding("\"") }
            if (parts.size >= 2 && parts[0] == "graphic") {
                viewModelScope.launch { navEvents.send(NavEvent.OpenGraphic(parts[1])) }
            }
        } catch (_: Exception) {}
    }

    private fun com.uptodate.viewer.data.database.content.models.ContributorGroup.toDomain() =
        ContributorGroup(
            headingTitle = headingTitle,
            contributors = contributorList?.map { p ->
                com.uptodate.viewer.domain.model.ContributorPerson(
                    name = p.name,
                    associations = p.associations,
                    disclosure = p.disclosure
                )
            } ?: emptyList()
        )
}
