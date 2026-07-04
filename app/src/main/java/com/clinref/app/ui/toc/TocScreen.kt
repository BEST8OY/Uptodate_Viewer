package com.clinref.app.ui.toc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.TocItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TocScreen(
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit,
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TocViewModel = hiltViewModel()
) {
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
            ListItem(
                headlineContent = { Text("Search topics...") },
                leadingContent = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                },
                modifier = Modifier.clickable(onClick = onSearchClick)
            )

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
