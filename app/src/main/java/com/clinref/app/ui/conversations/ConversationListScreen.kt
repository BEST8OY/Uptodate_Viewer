package com.clinref.app.ui.conversations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onConversationSelected: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showProfileSheet by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val textFieldState = rememberTextFieldState()

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    LaunchedEffect(textFieldState.text) {
        viewModel.onSearchQueryChange(textFieldState.text.toString())
    }

    if (uiState.showDeleteConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete Selected Sessions?") },
            text = { Text("Are you sure you want to delete ${uiState.selectedIds.size} clinical session(s)?") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteSelectedConversations() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirmation() }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(28.dp)
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = { Text("${uiState.selectedIds.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(
                                imageVector = if (uiState.selectedIds.size == uiState.filteredConversations.size && uiState.filteredConversations.isNotEmpty())
                                    Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                                contentDescription = "Select All",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = { viewModel.showDeleteConfirmation() },
                            enabled = uiState.selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Selected",
                                tint = if (uiState.selectedIds.isNotEmpty()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(bottom = 8.dp)
                ) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "Clinical Sessions",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "${uiState.filteredConversations.size} session(s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                                Icon(
                                    imageVector = Icons.Default.SelectAll,
                                    contentDescription = "Selection Mode",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = onOpenSettings) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            androidx.compose.foundation.text.BasicTextField(
                                state = textFieldState,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 12.dp),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                decorator = { innerTextField ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (textFieldState.text.isEmpty()) {
                                            Text(
                                                text = "Search sessions, topics, or patient profiles...",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                            if (textFieldState.text.isNotEmpty()) {
                                IconButton(
                                    onClick = { textFieldState.clearText() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !uiState.isSelectionMode,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut()
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showProfileSheet = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Session", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(bottom = 80.dp)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ConversationFilter.entries.size) { idx ->
                    val filter = ConversationFilter.entries[idx]
                    val isSelected = uiState.activeFilter == filter
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.onFilterSelected(filter) },
                        label = { Text(filter.displayName) },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else null,
                        shape = RoundedCornerShape(16.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    )
                }
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.5.dp)
                    }
                }

                uiState.filteredConversations.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Forum,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = if (uiState.searchQuery.isNotBlank()) "No sessions match \"${uiState.searchQuery}\""
                                else "No Workspaces Found",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (uiState.searchQuery.isNotBlank()) "Try a different search query."
                                else "Tap the button below to start a clinical session.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        itemsIndexed(
                            items = uiState.filteredConversations,
                            key = { _, item -> item.id }
                        ) { index, conversation ->
                            val totalCount = uiState.filteredConversations.size
                            val isItemSelected = uiState.selectedIds.contains(conversation.id)
                            val itemShapes = ListItemDefaults.segmentedShapes(index = index, count = totalCount)

                            val dismissState = rememberSwipeToDismissBoxState()
                            LaunchedEffect(dismissState.currentValue) {
                                if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart || dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
                                    viewModel.deleteConversation(conversation.id)
                                }
                            }

                            SwipeToDismissBox(
                                state = dismissState,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                enableDismissFromStartToEnd = !uiState.isSelectionMode,
                                enableDismissFromEndToStart = !uiState.isSelectionMode,
                                backgroundContent = {
                                    val color by animateColorAsState(
                                        targetValue = when (dismissState.dismissDirection) {
                                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                            SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.errorContainer
                                            else -> Color.Transparent
                                        },
                                        label = "swipeBg"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(itemShapes)
                                            .background(color)
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = Alignment.CenterEnd
                                    ) {
                                        if (dismissState.dismissDirection != SwipeToDismissBoxValue.Settled) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "Delete",
                                                tint = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                },
                                content = {
                                    if (uiState.isSelectionMode) {
                                        SegmentedListItem(
                                            checked = isItemSelected,
                                            onCheckedChange = { viewModel.toggleItemSelection(conversation.id) },
                                            onLongClick = { viewModel.toggleItemSelection(conversation.id) },
                                            shapes = itemShapes,
                                            colors = if (isItemSelected) {
                                                ListItemDefaults.segmentedColors(
                                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                                )
                                            } else {
                                                ListItemDefaults.segmentedColors(
                                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                                )
                                            },
                                            leadingContent = {
                                                Checkbox(
                                                    checked = isItemSelected,
                                                    onCheckedChange = null
                                                )
                                            },
                                            trailingContent = {
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = formatTimestamp(conversation.timestamp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    if (conversation.isPinned) {
                                                        Icon(
                                                            imageVector = Icons.Default.PushPin,
                                                            contentDescription = "Pinned",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(14.dp)
                                                        )
                                                    }
                                                }
                                            },
                                             supportingContent = {
                                                Text(
                                                    text = conversation.lastPreview,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            content = {
                                                Text(
                                                    text = conversation.title,
                                                    fontWeight = if (conversation.isUnread) FontWeight.Bold else FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        )
                                    } else {
                                        SegmentedListItem(
                                            selected = conversation.isPinned,
                                            onClick = {
                                                viewModel.markAsRead(conversation.id)
                                                onConversationSelected(conversation.id)
                                            },
                                            onLongClick = {
                                                viewModel.toggleSelectionMode()
                                                viewModel.toggleItemSelection(conversation.id)
                                            },
                                            shapes = itemShapes,
                                            colors = ListItemDefaults.segmentedColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                                            ),
                                            leadingContent = {
                                                Surface(
                                                    shape = CircleShape,
                                                    color = if (conversation.isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                    modifier = Modifier.size(40.dp)
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        val profile = conversation.patientProfile
                                                        val initials = buildString {
                                                            if (profile.sex.isNotBlank()) append(profile.sex.take(1).uppercase())
                                                            if (profile.age.isNotBlank()) append(profile.age.filter { it.isDigit() }.take(2))
                                                        }.ifBlank { conversation.title.take(1).uppercase() }
                                                        Text(
                                                            text = initials,
                                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                                            color = if (conversation.isPinned) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                }
                                            },
                                            trailingContent = {
                                                Column(horizontalAlignment = Alignment.End) {
                                                    Text(
                                                        text = formatTimestamp(conversation.timestamp),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        if (conversation.isPinned) {
                                                            Icon(
                                                                imageVector = Icons.Default.PushPin,
                                                                contentDescription = "Pinned",
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(14.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(4.dp))
                                                        }
                                                        if (conversation.isUnread) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(8.dp)
                                                                    .clip(CircleShape)
                                                                    .background(MaterialTheme.colorScheme.primary)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            supportingContent = {
                                                Text(
                                                    text = conversation.lastPreview,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            },
                                            content = {
                                                Text(
                                                    text = conversation.title,
                                                    fontWeight = if (conversation.isUnread) FontWeight.Bold else FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showProfileSheet) {
        PatientProfileSheet(
            onDismiss = { showProfileSheet = false },
            onStart = { profile ->
                showProfileSheet = false
                viewModel.createNewSession(profile) { id ->
                    onConversationSelected(id)
                }
            }
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val date = java.util.Date(timestamp)
    val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
    val dateFormat = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())

    return when {
        diff < 24 * 3600 * 1000L -> timeFormat.format(date)
        diff < 48 * 3600 * 1000L -> "Yesterday"
        else -> dateFormat.format(date)
    }
}
