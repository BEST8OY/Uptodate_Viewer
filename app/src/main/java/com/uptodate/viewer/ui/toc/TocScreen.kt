package com.uptodate.viewer.ui.toc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uptodate.viewer.domain.model.TocItem

@Composable
fun TocScreen(
    onTopicClick: (String) -> Unit,
    viewModel: TocViewModel
) {
    val state by viewModel.state.collectAsState()

    if (state.isLoading) {
        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        return
    }

    LazyColumn(modifier = Modifier.padding(horizontal = 8.dp)) {
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

    Column(modifier = Modifier.padding(start = (indent * 16).dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable {
                    if (node.isLeaf) onTopicClick(node.id) else onToggle(node.id)
                }
                .padding(vertical = 4.dp, horizontal = 8.dp)
        ) {
            if (canExpand) {
                Text(
                    text = if (isExpanded) "\u25BE" else "\u25B8",
                    modifier = Modifier
                        .width(24.dp)
                        .height(48.dp)
                        .semantics {
                            contentDescription = if (isExpanded) {
                                "Collapse"
                            } else {
                                "Expand"
                            }
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.width(24.dp))
            }
            Text(
                text = node.title,
                modifier = Modifier.weight(1f),
                style = if (node.isLeaf) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                },
                color = if (node.isLeaf) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        }

        if (isExpanded && canExpand) {
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
