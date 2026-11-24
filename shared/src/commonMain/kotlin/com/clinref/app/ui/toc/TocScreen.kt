package com.clinref.app.ui.toc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.clinref.app.domain.TocItem
import com.clinref.app.ui.adaptive.BottomSafeInsetsExcludingIme
import com.clinref.app.ui.adaptive.CenteredReadableContainer
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import com.clinref.app.ui.adaptive.TopAndHorizontalSafeInsets
import com.clinref.app.ui.adaptive.rememberContentBottomPadding
import com.clinref.app.ui.common.BackHandler
import com.clinref.app.ui.common.ClinRefEmptyState
import com.clinref.app.ui.common.ClinRefErrorState
import com.clinref.app.ui.common.bindReselectScrollToTop
import com.clinref.app.ui.navigation.TocRoute
import com.clinref.app.ui.search.SearchResultsContent
import com.clinref.app.ui.search.SearchViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TocScreen(
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    reselectEvents: Flow<NavKey> = emptyFlow(),
    viewModel: TocViewModel = koinViewModel(),
    searchViewModel: SearchViewModel = koinViewModel()
) {
    val listState = rememberLazyListState()
    listState.bindReselectScrollToTop(reselectEvents, TocRoute)

    val tocItems by viewModel.tocItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val expandedIds by viewModel.expandedIds.collectAsStateWithLifecycle()

    val suggestions by searchViewModel.suggestions.collectAsStateWithLifecycle()
    val searchResults by searchViewModel.searchResults.collectAsStateWithLifecycle()
    val selectedAudience by searchViewModel.selectedAudience.collectAsStateWithLifecycle()
    val isSearchLoading by searchViewModel.isLoading.collectAsStateWithLifecycle()
    val searchError by searchViewModel.error.collectAsStateWithLifecycle()

    val searchBarState = rememberContainedSearchBarState()
    val textFieldState = rememberTextFieldState()
    val scope = rememberCoroutineScope()
    val expanded = searchBarState.currentValue == SearchBarValue.Expanded
    var hasSearched by rememberSaveable { mutableStateOf(false) }
    var skipNextQuery by remember { mutableStateOf(false) }
    var lastQuery by rememberSaveable { mutableStateOf("") }

    val isShowingResults = !expanded && hasSearched
    val adaptiveInfo = LocalWindowAdaptiveInfo.current
    val bottomInsetPadding = rememberContentBottomPadding(16.dp)

    BackHandler(enabled = expanded || isShowingResults) {
        if (isShowingResults) {
            hasSearched = false
            searchViewModel.onQueryChanged("")
        } else {
            scope.launch { searchBarState.animateToCollapsed() }
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { q ->
                if (skipNextQuery) {
                    skipNextQuery = false
                    return@collect
                }
                if (q != lastQuery) {
                    lastQuery = q
                    searchViewModel.onQueryChanged(q)
                    hasSearched = false
                }
            }
    }

    val visibleTocItems by remember(tocItems, expandedIds) {
        derivedStateOf {
            fun flatten(items: List<TocItem>, level: Int, parentPath: String = ""): List<Triple<TocItem, Int, String>> {
                val list = mutableListOf<Triple<TocItem, Int, String>>()
                for (item in items) {
                    val key = if (parentPath.isEmpty()) item.id else "${parentPath}/${item.id}"
                    list.add(Triple(item, level, key))
                    val children = item.childrenInfo
                    if (item.id in expandedIds && children != null) {
                        list.addAll(flatten(children, level + 1, key))
                    }
                }
                return list
            }
            flatten(tocItems, level = 0)
        }
    }

    val inputField = @Composable {
        SearchBarDefaults.InputField(
            searchBarState = searchBarState,
            textFieldState = textFieldState,
            onSearch = {
                val query = textFieldState.text.toString()
                if (query.isNotBlank()) {
                    skipNextQuery = true
                    lastQuery = query
                    searchViewModel.search(query)
                    hasSearched = true
                    scope.launch { searchBarState.animateToCollapsed() }
                }
            },
            placeholder = { Text("Search topics...") },
            trailingIcon = {
                if (textFieldState.text.isNotEmpty()) {
                    IconButton(onClick = {
                        textFieldState.edit { replace(0, length, "") }
                        skipNextQuery = true
                        lastQuery = ""
                        searchViewModel.onQueryChanged("")
                        hasSearched = false
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            }
        )
    }

    Scaffold(
        modifier = modifier
            .testTag("toc_screen")
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    when {
                        textFieldState.text.isNotEmpty() -> {
                            textFieldState.edit { replace(0, length, "") }
                            skipNextQuery = true
                            lastQuery = ""
                            searchViewModel.onQueryChanged("")
                            hasSearched = false
                            true
                        }
                        expanded -> {
                            scope.launch { searchBarState.animateToCollapsed() }
                            true
                        }
                        isShowingResults -> {
                            hasSearched = false
                            searchViewModel.onQueryChanged("")
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentWindowInsets = TopAndHorizontalSafeInsets,
        topBar = {
            AppBarWithSearch(
                state = searchBarState,
                inputField = inputField,
            )
            ExpandedFullScreenContainedSearchBar(state = searchBarState, inputField = inputField) {
                CenteredReadableContainer {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(BottomSafeInsetsExcludingIme)
                            .imePadding()
                            .padding(horizontal = adaptiveInfo.horizontalMargin),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (textFieldState.text.isEmpty() && suggestions.isEmpty()) {
                            item {
                                ClinRefEmptyState(
                                    icon = Icons.Default.Search,
                                    title = "Search for topics",
                                    titleStyle = MaterialTheme.typography.bodyLarge,
                                    scrollable = false,
                                    fillViewport = false,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        if (suggestions.isNotEmpty()) {
                            itemsIndexed(
                                items = suggestions,
                                key = { _, suggestion -> suggestion }
                            ) { idx, suggestion ->
                                SegmentedListItem(
                                    onClick = {
                                        skipNextQuery = true
                                        lastQuery = suggestion
                                        textFieldState.edit { replace(0, length, suggestion) }
                                        searchViewModel.search(suggestion)
                                        hasSearched = true
                                        scope.launch { searchBarState.animateToCollapsed() }
                                    },
                                    shapes = ListItemDefaults.segmentedShapes(index = idx, count = suggestions.size),
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                                    leadingContent = {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    },
                                    content = { Text(suggestion) }
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            if (isShowingResults) {
                SearchResultsContent(
                    searchResults = searchResults,
                    selectedAudience = selectedAudience,
                    isSearchLoading = isSearchLoading,
                    searchError = searchError,
                    onAudienceChanged = searchViewModel::onAudienceChanged,
                    onTopicSelected = onTopicSelected,
                    onGraphicSelected = onGraphicSelected
                )
            } else if (!expanded) {
                when {
                    isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = bottomInsetPadding),
                            contentAlignment = Alignment.Center
                        ) {
                            ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
                        }
                    }
                    error != null -> {
                        ClinRefErrorState(
                            message = error ?: "Unknown error",
                            onRetry = { viewModel.retry() },
                            contentPadding = PaddingValues(
                                start = 32.dp,
                                top = 32.dp,
                                end = 32.dp,
                                bottom = bottomInsetPadding + 32.dp
                            )
                        )
                    }
                    tocItems.isEmpty() -> {
                        ClinRefEmptyState(
                            icon = Icons.Default.FolderOpen,
                            title = "No topics found",
                            titleStyle = MaterialTheme.typography.bodyLarge,
                            contentPadding = PaddingValues(
                                start = 32.dp,
                                top = 32.dp,
                                end = 32.dp,
                                bottom = bottomInsetPadding + 32.dp
                            )
                        )
                    }
                    else -> {
                        CenteredReadableContainer {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = bottomInsetPadding),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                itemsIndexed(
                                    items = visibleTocItems,
                                    key = { _, (_, _, uniqueKey) -> uniqueKey }
                                ) { index, (item, level, _) ->
                                    TocItemRow(
                                        item = item,
                                        level = level,
                                        index = index,
                                        totalCount = visibleTocItems.size,
                                        isExpanded = item.id in expandedIds,
                                        onTopicSelected = onTopicSelected,
                                        onGraphicSelected = onGraphicSelected,
                                        onLoadChildren = viewModel::loadChildren,
                                        onToggleExpand = viewModel::toggleExpanded,
                                        onResolveTopicId = { tocId -> viewModel.resolveTopicId(tocId) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
