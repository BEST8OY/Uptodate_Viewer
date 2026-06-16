package com.uptodate.viewer.ui.toc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.uptodate.viewer.domain.TocItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TocScreen(
    onTopicSelected: (String) -> Unit,
    viewModel: TocViewModel = hiltViewModel()
) {
    val tocItems by viewModel.tocItems.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val expandedIds by viewModel.expandedIds.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contents") }
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
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
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
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
                    items(tocItems, key = { it.id }) { item ->
                        TocItemRow(
                            item = item,
                            onTopicSelected = onTopicSelected,
                            onLoadChildren = { parentId -> viewModel.loadChildren(parentId) },
                            expandedIds = expandedIds,
                            onToggleExpand = { id -> viewModel.toggleExpanded(id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TocItemRow(
    item: TocItem,
    onTopicSelected: (String) -> Unit,
    onLoadChildren: (String) -> Unit,
    expandedIds: Set<String>,
    onToggleExpand: (String) -> Unit,
    level: Int = 0
) {
    val isExpanded = item.id in expandedIds
    val expandDesc = if (isExpanded) "Collapse" else "Expand"

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (item.isLeaf) {
                        onTopicSelected(item.id)
                    } else {
                        if (item.childrenInfo == null) {
                            onLoadChildren(item.id)
                        }
                        onToggleExpand(item.id)
                    }
                }
                .padding(start = (16 + level * 24).dp, end = 16.dp)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!item.isLeaf) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = expandDesc,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(24.dp)
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        if (!item.isLeaf) {
                            contentDescription = "${item.title}, $expandDesc"
                        }
                    }
            )
        }

        if (isExpanded && item.childrenInfo != null) {
            item.childrenInfo.forEach { child ->
                TocItemRow(
                    item = child,
                    onTopicSelected = onTopicSelected,
                    onLoadChildren = onLoadChildren,
                    expandedIds = expandedIds,
                    onToggleExpand = onToggleExpand,
                    level = level + 1
                )
            }
        }
    }
}
