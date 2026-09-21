package com.clinref.app.ui.toc

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.TocItem

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
