package com.clinref.app.ui.toc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.Audience
import com.clinref.app.domain.SearchResult
import com.clinref.app.domain.TocItem
import com.clinref.app.ui.search.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun TocScreen(
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TocViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel()
) {
    var isSearchActive by remember { mutableStateOf(false) }

    if (isSearchActive) {
        SearchOverlay(
            onBack = { isSearchActive = false },
            onTopicSelected = { topicId ->
                isSearchActive = false
                onTopicSelected(topicId)
            },
            onGraphicSelected = { graphicId ->
                isSearchActive = false
                onGraphicSelected(graphicId)
            },
            viewModel = searchViewModel
        )
        return
    }

    val tocItems by viewModel.tocItems.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val expandedIds by viewModel.expandedIds.collectAsStateWithLifecycle()

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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Contents") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Surface(
                onClick = { isSearchActive = true },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Search topics...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
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
                            .padding(bottom = 80.dp)
                    ) {
                        items(
                            items = visibleTocItems,
                            key = { (_, _, uniqueKey) -> uniqueKey }
                        ) { (item, level, _) ->
                            TocItemRow(
                                item = item,
                                level = level,
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SearchOverlay(
    onBack: () -> Unit,
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit,
    viewModel: SearchViewModel
) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val selectedAudience by viewModel.selectedAudience.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val searchError by viewModel.error.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    val searchBarState = rememberSearchBarState()
    val expanded = searchBarState.status == SearchBarValue.Expanded
    var hasSearched by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = expanded) {
        searchBarState.status = SearchBarValue.Collapsed
    }
    BackHandler(enabled = !expanded) { onBack() }

    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .collect { q ->
                viewModel.onQueryChanged(q)
                hasSearched = false
            }
    }

    val inputField = @Composable {
        SearchBarDefaults.InputField(
            query = query,
            onQueryChange = { query = it },
            onSearch = {
                if (query.isNotBlank()) {
                    viewModel.search(query)
                    hasSearched = true
                }
            },
            expanded = expanded,
            onExpandedChange = { searchBarState.status = if (it) SearchBarValue.Expanded else SearchBarValue.Collapsed },
            placeholder = { Text("Search topics...") },
            leadingIcon = {
                IconButton(onClick = {
                    if (expanded) {
                        searchBarState.status = SearchBarValue.Collapsed
                    } else {
                        onBack()
                    }
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        query = ""
                        viewModel.onQueryChanged("")
                        hasSearched = false
                    }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            }
        )
    }

    SearchBar(
        state = searchBarState,
        inputField = inputField
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                            onClick = { viewModel.onAudienceChanged(audience) },
                            label = { Text(audience.label) }
                        )
                    }
                }
            }

            if (suggestions.isNotEmpty()) {
                items(suggestions) { suggestion ->
                    ListItem(
                        headlineContent = { Text(suggestion) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.clickable {
                            query = suggestion
                            viewModel.search(suggestion)
                            hasSearched = true
                        }
                    )
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

            if (hasSearched && searchResults.isEmpty() && searchError == null && !isLoading && query.isNotEmpty()) {
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

            if (searchResults.isNotEmpty()) {
                items(searchResults) { result ->
                    ListItem(
                        headlineContent = { Text(result.title) },
                        modifier = Modifier.clickable {
                            when (result) {
                                is SearchResult.Topic -> onTopicSelected(result.topicId)
                                is SearchResult.Graphic -> onGraphicSelected(result.graphicId)
                            }
                        }
                    )
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
    isExpanded: Boolean,
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit,
    onLoadChildren: (String) -> Unit,
    onToggleExpand: (String) -> Unit,
    onResolveTopicId: (String) -> String? = { null },
    modifier: Modifier = Modifier
) {
    ListItem(
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
