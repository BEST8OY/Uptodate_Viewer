package com.clinref.app.ui.common

import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.clinref.app.ui.adaptive.CenteredReadableContainer
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import com.clinref.app.ui.adaptive.TopAndHorizontalSafeInsets
import com.clinref.app.ui.adaptive.rememberContentBottomPadding
import com.clinref.app.ui.content.normalizeGraphicId
import com.clinref.app.util.formatTimestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class TopicCollectionEntry(
    val topicId: String,
    val title: String,
    val timestamp: Long,
)

data class TopicCollectionStrings(
    val title: String,
    val subtitle: (count: Int) -> String,
    val clearAllDialogTitle: String,
    val clearAllDialogText: String,
    val clearAllActionDescription: String,
    val emptyStateText: String,
    val emptyStateIcon: ImageVector,
    val itemLeadingIcon: ImageVector,
    val removedSnackbarMessage: (count: Int) -> String,
    val removeAccessibilityLabel: String,
    val swipeActionLabel: String = "Remove",
)

@Composable
fun EmptyTopicCollectionState(
    text: String,
    icon: ImageVector,
    bottomInsetPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    ClinRefEmptyState(
        icon = icon,
        title = text,
        contentPadding = PaddingValues(
            start = 32.dp,
            top = 32.dp,
            end = 32.dp,
            bottom = bottomInsetPadding + 32.dp
        ),
        modifier = modifier,
    )
}

/**
 * Unified catalog screen for topic-based collections (Favorites, Reading History).
 *
 * Implements M3 Expressive segmented card presentation with:
 * - Proper [WindowInsets.safeDrawing] insets chain
 * - Touch swipe-to-dismiss via [SegmentedDismissBackground]
 * - Desktop mouse ergonomics (explicit remove action when expanded)
 * - Accessible list items with custom actions instead of overriding content descriptions
 * - Multi-selection mode with single top-bar delete action
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TopicCollectionScreen(
    entries: List<TopicCollectionEntry>,
    strings: TopicCollectionStrings,
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit,
    onRemoveEntry: (String) -> Unit,
    onClearAll: () -> Unit,
    onRestoreEntries: (List<TopicCollectionEntry>) -> Unit,
    reselectKey: NavKey,
    modifier: Modifier = Modifier,
    reselectEvents: Flow<NavKey> = emptyFlow(),
) {
    val listState = rememberLazyListState()
    listState.bindReselectScrollToTop(reselectEvents, reselectKey)

    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    var showClearAllDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<List<TopicCollectionEntry>>(emptyList()) }
    val topAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val adaptiveInfo = LocalWindowAdaptiveInfo.current
    val bottomInsetPadding = rememberContentBottomPadding(16.dp)

    UndoDeleteEffect(
        pendingItems = pendingDelete,
        snackbarHostState = snackbarHostState,
        messageFor = { strings.removedSnackbarMessage(it.size) },
        onRestore = onRestoreEntries,
        onClearPending = { pendingDelete = emptyList() },
        onLifecycleStop = { selectedIds = emptySet() },
    )

    LaunchedEffect(entries) {
        val currentIds = entries.map { it.topicId }.toSet()
        if (selectedIds.any { it !in currentIds }) {
            selectedIds = selectedIds intersect currentIds
        }
    }

    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(strings.clearAllDialogTitle) },
            text = { Text(strings.clearAllDialogText) },
            confirmButton = {
                TextButton(onClick = {
                    onClearAll()
                    showClearAllDialog = false
                }) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        contentWindowInsets = TopAndHorizontalSafeInsets,
        snackbarHost = {
            Box(modifier = Modifier.padding(bottom = bottomInsetPadding)) {
                SnackbarHost(snackbarHostState)
            }
        },
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    title = { Text("${selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { selectedIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            selectedIds = if (selectedIds.size == entries.size) {
                                emptySet()
                            } else {
                                entries.map { it.topicId }.toSet()
                            }
                        }) {
                            Icon(
                                imageVector = if (selectedIds.size == entries.size && entries.isNotEmpty()) {
                                    Icons.Filled.CheckCircle
                                } else {
                                    Icons.Outlined.CheckCircle
                                },
                                contentDescription = "Select All",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(
                            onClick = {
                                val toDelete = entries.filter { it.topicId in selectedIds }
                                selectedIds.forEach { onRemoveEntry(it) }
                                pendingDelete = toDelete
                                selectedIds = emptySet()
                            },
                            enabled = selectedIds.isNotEmpty(),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = if (selectedIds.isNotEmpty()) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                    windowInsets = TopAndHorizontalSafeInsets,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            } else {
                AdaptiveTopAppBar(
                    title = {
                        Text(
                            text = strings.title,
                            style = if (adaptiveInfo.isCompactHeight) MaterialTheme.typography.titleLarge
                                   else MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        )
                    },
                    subtitle = if (entries.isNotEmpty()) {
                        {
                            Text(
                                text = strings.subtitle(entries.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else null,
                    actions = {
                        if (entries.isNotEmpty()) {
                            IconButton(onClick = { showClearAllDialog = true }) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = strings.clearAllActionDescription,
                                )
                            }
                        }
                    },
                    scrollBehavior = topAppBarScrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            if (entries.isEmpty()) {
                EmptyTopicCollectionState(
                    text = strings.emptyStateText,
                    icon = strings.emptyStateIcon,
                    bottomInsetPadding = bottomInsetPadding,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CenteredReadableContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = bottomInsetPadding),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        itemsIndexed(
                            items = entries,
                            key = { _, entry -> entry.topicId },
                        ) { index, entry ->
                            val isSelected = entry.topicId in selectedIds
                            TopicCollectionItemRow(
                                entry = entry,
                                index = index,
                                totalCount = entries.size,
                                isSelected = isSelected,
                                isSelectionMode = isSelectionMode,
                                strings = strings,
                                shapes = ListItemDefaults.segmentedShapes(index = index, count = entries.size),
                                onTopicSelected = {
                                    if (isSelectionMode) {
                                        selectedIds = if (isSelected) selectedIds - entry.topicId else selectedIds + entry.topicId
                                    } else {
                                        if (entry.topicId.startsWith("Graphic-", ignoreCase = true)) {
                                            onGraphicSelected(normalizeGraphicId(entry.topicId))
                                        } else {
                                            onTopicSelected(entry.topicId)
                                        }
                                    }
                                },
                                onLongPress = {
                                    if (!isSelectionMode) {
                                        selectedIds = setOf(entry.topicId)
                                    }
                                },
                                onRemove = {
                                    onRemoveEntry(entry.topicId)
                                    pendingDelete = listOf(entry)
                                },
                                modifier = Modifier.animateItem(
                                    fadeInSpec = nonBouncyExitSpring(),
                                    placementSpec = nonBouncyExitSpring(
                                        visibilityThreshold = IntOffset.VisibilityThreshold,
                                    ),
                                    fadeOutSpec = nonBouncyFastExitSpring(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopicCollectionItemRow(
    entry: TopicCollectionEntry,
    index: Int,
    totalCount: Int,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    strings: TopicCollectionStrings,
    onTopicSelected: () -> Unit,
    onLongPress: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    shapes: ListItemShapes = ListItemDefaults.segmentedShapes(index = index, count = totalCount),
) {
    val adaptiveInfo = LocalWindowAdaptiveInfo.current
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { totalDistance -> totalDistance * 0.4f },
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
            .padding(horizontal = adaptiveInfo.horizontalMargin)
            .clipDuringSwipe(
                dismissState = dismissState,
                shape = shapes.shape,
                reusePath = outerClipPath,
            )
            .semantics {
                if (!isSelectionMode) {
                    customActions = listOf(
                        CustomAccessibilityAction(label = strings.removeAccessibilityLabel) {
                            onRemove()
                            true
                        },
                    )
                }
            },
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                onRemove()
            }
        },
        backgroundContent = {
            SegmentedDismissBackground(
                dismissState = dismissState,
                shape = shapes.shape,
                endAction = DismissAction(
                    label = strings.swipeActionLabel,
                    icon = Icons.Default.DeleteSweep,
                ),
            )
        },
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
                        onCheckedChange = null,
                    )
                },
                trailingContent = {
                    Text(
                        text = formatTimestamp(entry.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                content = {
                    Text(
                        text = entry.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerHoverIcon(PointerIcon.Hand),
                colors = if (isSelected) {
                    ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    )
                } else {
                    ListItemDefaults.segmentedColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    )
                },
            )
        } else {
            SegmentedListItem(
                onClick = onTopicSelected,
                onLongClick = onLongPress,
                shapes = shapes,
                leadingContent = {
                    Icon(
                        imageVector = strings.itemLeadingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = formatTimestamp(entry.timestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (adaptiveInfo.showInlineRowActions) {
                            IconButton(
                                onClick = onRemove,
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = strings.removeAccessibilityLabel,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                content = {
                    Text(
                        text = entry.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerHoverIcon(PointerIcon.Hand),
                colors = ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
        }
    }
}
