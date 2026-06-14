package com.uptodate.viewer.ui.toc

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.uptodate.viewer.domain.model.TocItem

@Composable
fun TocScreen(
    onTopicClick: (String) -> Unit,
    viewModel: TocViewModel
) {
    val state by viewModel.state.collectAsState()

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (state.roots.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "No contents available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(state.roots, key = { it.id }) { node ->
            TocNodeItem(
                node = node,
                indent = 0,
                expandedIds = state.expandedIds,
                onToggle = { id ->
                    val current = id in state.expandedIds
                    if (!current) {
                        viewModel.loadChildren(id)
                    } else {
                        viewModel.toggleExpanded(id)
                    }
                },
                onTopicClick = onTopicClick,
                children = state.children
            )
        }
    }
}

@Composable
private fun TocNodeItem(
    node: TocItem,
    indent: Int,
    expandedIds: Set<String>,
    onToggle: (String) -> Unit,
    onTopicClick: (String) -> Unit,
    children: Map<String, List<TocItem>>
) {
    val isExpanded = node.id in expandedIds
    val canExpand = !node.isLeaf || node.hasChildren

    Column(modifier = Modifier.animateContentSize(tween(200))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (node.isLeaf) onTopicClick(node.id) else onToggle(node.id)
                }
                .padding(start = (indent * 16 + 8).dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                .then(
                    if (node.isLeaf) {
                        Modifier.padding(start = 24.dp)
                    } else {
                        Modifier
                    }
                )
        ) {
            if (canExpand) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.Folder else Icons.Default.Folder,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(20.dp)
                        .padding(2.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(28.dp))
            }

            Text(
                text = node.title,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (canExpand) {
                            Modifier.padding(0.dp)
                        } else {
                            Modifier.padding(0.dp)
                        }
                    ),
                style = if (node.isLeaf) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.titleSmall
                },
                fontWeight = if (node.isLeaf) FontWeight.Normal else FontWeight.SemiBold,
                color = if (node.isLeaf) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        AnimatedVisibility(
            visible = isExpanded && canExpand,
            enter = expandVertically(tween(200)),
            exit = shrinkVertically(tween(200))
        ) {
            Column {
                val nodeChildren = children[node.id] ?: emptyList()
                nodeChildren.forEach { child ->
                    TocNodeItem(
                        node = child,
                        indent = indent + 1,
                        expandedIds = expandedIds,
                        onToggle = onToggle,
                        onTopicClick = onTopicClick,
                        children = children
                    )
                }
            }
        }
    }
}
