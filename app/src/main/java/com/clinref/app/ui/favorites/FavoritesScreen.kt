package com.clinref.app.ui.favorites

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.R
import com.clinref.app.domain.FavoriteEntry
import com.clinref.app.ui.common.showUndoSnackbar
import com.clinref.app.util.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onTopicSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val isSelectionMode = selectedIds.isNotEmpty()
    var showClearAllDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<FavoriteEntry?>(null) }

    BackHandler(enabled = isSelectionMode) {
        selectedIds = emptySet()
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Clear all favorites?") },
            text = { Text("This will remove all items from your favorites.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearFavorites()
                    showClearAllDialog = false
                }) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = {
            Box(modifier = Modifier.padding(bottom = 80.dp)) {
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    actions = {
                        IconButton(onClick = { showClearAllDialog = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = "Delete selected"
                            )
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = { Text("Favorites") },
                    actions = {
                        if (favorites.isNotEmpty()) {
                            IconButton(onClick = { showClearAllDialog = true }) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = "Clear all favorites"
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = isSelectionMode,
                modifier = Modifier.padding(bottom = 80.dp),
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                    slideInHorizontally(MaterialTheme.motionScheme.defaultEffectsSpec()) { it / 2 },
                exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()) +
                    slideOutHorizontally(MaterialTheme.motionScheme.defaultEffectsSpec()) { it / 2 }
            ) {
                FloatingActionButton(
                    onClick = {
                        selectedIds.forEach { viewModel.removeFavorite(it) }
                        selectedIds = emptySet()
                    },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete selected"
                    )
                }
            }
        }
    ) { padding ->
        if (favorites.isEmpty()) {
            EmptyFavoritesState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                itemsIndexed(
                    items = favorites,
                    key = { _, entry -> entry.topicId }
                ) { index, entry ->
                    val isSelected = entry.topicId in selectedIds
                    FavoriteItem(
                        entry = entry,
                        index = index,
                        totalCount = favorites.size,
                        isSelected = isSelected,
                        isSelectionMode = isSelectionMode,
                        onTopicSelected = {
                            if (isSelectionMode) {
                                selectedIds = if (isSelected) {
                                    selectedIds - entry.topicId
                                } else {
                                    selectedIds + entry.topicId
                                }
                            } else {
                                onTopicSelected(entry.topicId)
                            }
                        },
                        onLongPress = {
                            selectedIds = selectedIds + entry.topicId
                        },
                        onSwipeToRemove = {
                            viewModel.removeFavorite(entry.topicId)
                            pendingDelete = entry
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    LaunchedEffect(pendingDelete) {
        pendingDelete?.let { entry ->
            showUndoSnackbar(
                snackbarHostState = snackbarHostState,
                message = "Favorite removed"
            ) {
                viewModel.addFavorite(entry.topicId, entry.title)
            }
            pendingDelete = null
        }
    }
}

@Composable
private fun EmptyFavoritesState(modifier: Modifier = Modifier) {
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
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            )
            Text(
                text = "No favorites yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Tap the heart icon on any article\nto add it here",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavoriteItem(
    entry: FavoriteEntry,
    index: Int,
    totalCount: Int,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onTopicSelected: () -> Unit,
    onLongPress: () -> Unit,
    onSwipeToRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState()
    val removeFavoriteDescription = stringResource(R.string.remove_favorite)

    LaunchedEffect(entry.topicId) {
        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !isSelectionMode,
        modifier = modifier.semantics { contentDescription = removeFavoriteDescription },
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                onSwipeToRemove()
            }
        },
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> Color.Transparent
                },
                label = "swipeBg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    ) {
        SegmentedListItem(
            onClick = onTopicSelected,
            onLongClick = onLongPress,
            shapes = ListItemDefaults.segmentedShapes(index = index, count = totalCount),
            leadingContent = {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            },
            supportingContent = {
                Text(
                    text = formatTimestamp(entry.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = null
                    )
                }
            },
            content = {
                Text(
                    text = entry.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            modifier = modifier.fillMaxWidth(),
            colors = if (isSelected) {
                ListItemDefaults.segmentedColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            } else {
                ListItemDefaults.segmentedColors()
            }
        )
    }
}
