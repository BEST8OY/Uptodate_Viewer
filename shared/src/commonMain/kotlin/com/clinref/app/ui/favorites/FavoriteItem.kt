package com.clinref.app.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.FavoriteEntry
import com.clinref.app.ui.common.SegmentedDismissBackground
import com.clinref.app.ui.common.clipDuringSwipe
import com.clinref.app.util.formatTimestamp

@Composable
fun EmptyFavoritesState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FavoriteBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "No favorites yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FavoriteItem(
    entry: FavoriteEntry,
    index: Int,
    totalCount: Int,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onTopicSelected: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeToRemove: () -> Unit,
    modifier: Modifier = Modifier,
    shapes: ListItemShapes = ListItemDefaults.segmentedShapes(index = index, count = totalCount),
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.4f }
    )

    LaunchedEffect(entry.topicId) {
        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
    }

    LaunchedEffect(isSelectionMode) {
        if (isSelectionMode && dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.reset()
        }
    }

    val outerClipPath = remember { Path() }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !isSelectionMode,
        gesturesEnabled = !isSelectionMode,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clipDuringSwipe(
                dismissState = dismissState,
                shape = shapes.shape,
                reusePath = outerClipPath,
            )
            .semantics {
                contentDescription = "Remove ${entry.title} from favorites"
                if (!isSelectionMode) {
                    customActions = listOf(
                        CustomAccessibilityAction(label = "Remove from favorites") {
                            onSwipeToRemove()
                            true
                        }
                    )
                }
            },
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                onSwipeToRemove()
            }
        },
        backgroundContent = {
            SegmentedDismissBackground(
                dismissState = dismissState,
                shape = shapes.shape,
                actionLabel = "Remove",
                actionIcon = Icons.Default.DeleteSweep
            )
        }
    ) {
        if (isSelectionMode) {
            SegmentedListItem(
                checked = isSelected,
                onCheckedChange = { onTopicSelected() },
                onLongClick = onLongPress,
                shapes = shapes,
                leadingContent = {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null
                    )
                },
                trailingContent = {
                    Text(
                        text = formatTimestamp(entry.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                content = {
                    Text(
                        text = entry.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = if (isSelected) {
                    ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    )
                } else {
                    ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    )
                }
            )
        } else {
            SegmentedListItem(
                onClick = onTopicSelected,
                onLongClick = onLongPress,
                shapes = shapes,
                leadingContent = {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingContent = {
                    Text(
                        text = formatTimestamp(entry.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                content = {
                    Text(
                        text = entry.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }
    }
}

