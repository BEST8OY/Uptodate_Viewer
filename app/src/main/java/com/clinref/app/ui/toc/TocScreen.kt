package com.clinref.app.ui.toc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.Audience
import com.clinref.app.domain.SearchResult
import com.clinref.app.domain.TocItem
import com.clinref.app.ui.search.SearchViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class, FlowPreview::class)
@Composable
fun TocScreen(
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TocViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel()
) {
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
            .debounce(300)
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
                    if (item.id in expandedIds && item.childrenInfo != null) {
                        list.addAll(flatten(item.childrenInfo, level + 1, key))
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
        modifier = modifier.testTag("toc_screen"),
        topBar = {
            AppBarWithSearch(
                state = searchBarState,
                inputField = inputField,
            )
            ExpandedFullScreenContainedSearchBar(state = searchBarState, inputField = inputField) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (textFieldState.text.isEmpty() && suggestions.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = "Search for topics",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (suggestions.isNotEmpty()) {
                        items(suggestions.size) { idx ->
                            val suggestion = suggestions[idx]
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
    ) { padding ->
        if (isShowingResults) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                item {
                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Audience.entries.forEach { audience ->
                            FilterChip(
                                selected = selectedAudience == audience,
                                onClick = { searchViewModel.onAudienceChanged(audience) },
                                label = { Text(audience.label) }
                            )
                        }
                    }
                }

                if (searchError != null) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = searchError ?: "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (searchResults.isEmpty() && searchError == null && !isSearchLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "No results found" +
                                        if (selectedAudience != Audience.ALL) " for ${selectedAudience.label}" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (isSearchLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            LoadingIndicator(modifier = Modifier.size(48.dp))
                        }
                    }
                }

                if (searchResults.isNotEmpty()) {
                    item {
                        Text(
                            text = "${searchResults.size} result${if (searchResults.size != 1) "s" else ""} found",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }
                        )
                    }
                    items(searchResults.size) { idx ->
                        val result = searchResults[idx]
                        SegmentedListItem(
                            onClick = {
                                when (result) {
                                    is SearchResult.Topic -> onTopicSelected(result.topicId)
                                    is SearchResult.Graphic -> onGraphicSelected(result.graphicId)
                                }
                            },
                            shapes = ListItemDefaults.segmentedShapes(index = idx, count = searchResults.size),
                            content = { Text(result.title) }
                        )
                    }
                }
            }
        } else if (!expanded) {
            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator(modifier = Modifier.size(48.dp))
                    }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "Error",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = error ?: "Unknown error",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(onClick = { viewModel.retry() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
                tocItems.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No topics found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(bottom = 80.dp)
                    ) {
                        items(
                            items = visibleTocItems,
                            key = { (_, _, uniqueKey) -> uniqueKey }
                        ) { (item, level, uniqueKey) ->
                            TocItemRow(
                                item = item,
                                level = level,
                                index = visibleTocItems.indexOfFirst { it.third == uniqueKey },
                                totalCount = visibleTocItems.size,
                                isExpanded = item.id in expandedIds,
                                onTopicSelected = onTopicSelected,
                                onGraphicSelected = { graphicId ->
                                    val id = if (graphicId.startsWith("Graphic-")) {
                                        graphicId.removePrefix("Graphic-")
                                    } else {
                                        graphicId
                                    }
                                    onGraphicSelected(id)
                                },
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TocItemRow(
    item: TocItem,
    level: Int,
    index: Int,
    totalCount: Int,
    isExpanded: Boolean,
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit,
    onLoadChildren: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
    onResolveTopicId: (String) -> String? = { null },
    modifier: Modifier = Modifier
) {
    SegmentedListItem(
        onClick = {
            if (item.isLeaf) {
                if (item.type == "GRAPHIC") {
                    onGraphicSelected(item.id)
                } else {
                    val topicId = onResolveTopicId(item.id) ?: item.id
                    onTopicSelected(topicId)
                }
            } else {
                if (item.childrenInfo == null) {
                    onLoadChildren(item.id)
                }
                onToggleExpand(item.id)
            }
        },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = totalCount),
        modifier = modifier
            .padding(start = (16 + level * 24).dp)
            .semantics(mergeDescendants = true) {
                if (!item.isLeaf) {
                    val expandDesc = if (isExpanded) "Collapse" else "Expand"
                    contentDescription = "${item.title}, $expandDesc"
                }
            },
        leadingContent = {
            if (!item.isLeaf) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
        },
        content = {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    )
}
