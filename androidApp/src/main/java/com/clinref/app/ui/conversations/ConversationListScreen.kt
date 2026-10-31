package com.clinref.app.ui.conversations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.repository.ConversationBackup
import com.clinref.app.ui.common.BackHandler
import com.clinref.app.ui.common.ScrollToTopFAB
import com.clinref.app.ui.common.showUndoSnackbar
import com.clinref.app.ui.navigation.AiRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Redesigned Clinical Workspace Sessions screen.
 *
 * Provides a streamlined medical consultation catalog with demographic monograms,
 * clinical condition tags, real-time filtering, swipe-to-delete with undo,
 * and Material 3 Expressive motion physics.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun ConversationListScreen(
    onConversationSelected: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    reselectEvents: Flow<NavKey>? = null,
    viewModel: ConversationListViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val motionScheme = MaterialTheme.motionScheme

    LaunchedEffect(reselectEvents) {
        reselectEvents?.collect { route ->
            if (route == AiRoute) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var showProfileSheet by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<List<ConversationBackup>>(emptyList()) }
    var isFabDelete by remember { mutableStateOf(false) }

    val textFieldState = rememberTextFieldState()

    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            pendingDelete = emptyList()
        }
    }

    LaunchedEffect(textFieldState.text) {
        viewModel.onSearchQueryChange(textFieldState.text.toString())
    }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.toggleSelectionMode()
    }

    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    // Confirmation Dialog for Batch Deletions
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
                    onClick = {
                        scope.launch {
                            val backups = viewModel.deleteSelectedAndReturnBackups()
                            pendingDelete = backups
                            isFabDelete = true
                        }
                    },
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
            shape = RoundedCornerShape(24.dp)
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        },
        topBar = {
            if (uiState.isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${uiState.selectedIds.size} selected",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            val allSelected = uiState.selectedIds.size == uiState.filteredConversations.size &&
                                uiState.filteredConversations.isNotEmpty()
                            Icon(
                                imageVector = if (allSelected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                                contentDescription = "Select All",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(
                            onClick = {
                                val ids = uiState.selectedIds.toList()
                                ids.forEach { viewModel.togglePin(it) }
                                viewModel.toggleSelectionMode()
                            },
                            enabled = uiState.selectedIds.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Toggle Pin",
                                tint = if (uiState.selectedIds.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                        .padding(bottom = 6.dp)
                ) {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = "Clinical Sessions",
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                Text(
                                    text = "${uiState.filteredConversations.size} active workspace(s)",
                                    style = MaterialTheme.typography.labelSmall,
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
                                    contentDescription = "AI Settings",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        )
                    )

                    // Integrated M3 Expressive Search Pill
                    var isSearchFocused by remember { mutableStateOf(false) }
                    val animatedBorderColor by animateColorAsState(
                        targetValue = if (isSearchFocused) MaterialTheme.colorScheme.primary
                                      else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        animationSpec = motionScheme.fastEffectsSpec(),
                        label = "searchBorder"
                    )
                    val searchContainerColor by animateColorAsState(
                        targetValue = if (isSearchFocused) MaterialTheme.colorScheme.surfaceContainerHighest
                                      else MaterialTheme.colorScheme.surfaceContainerHigh,
                        animationSpec = motionScheme.fastEffectsSpec(),
                        label = "searchBg"
                    )

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = searchContainerColor,
                        border = BorderStroke(if (isSearchFocused) 1.5.dp else 1.dp, animatedBorderColor)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = if (isSearchFocused || textFieldState.text.isNotEmpty())
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            TextField(
                                state = textFieldState,
                                placeholder = {
                                    Text(
                                        text = "Search sessions, topics, or conditions...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                    )
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    disabledContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .onFocusChanged { isSearchFocused = it.isFocused }
                            )
                            AnimatedVisibility(
                                visible = textFieldState.text.isNotEmpty(),
                                enter = fadeIn(motionScheme.fastEffectsSpec()) + scaleIn(motionScheme.fastSpatialSpec()),
                                exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(motionScheme.fastSpatialSpec())
                            ) {
                                IconButton(
                                    onClick = { textFieldState.clearText() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear search",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    // M3 Expressive Filter Chips Row
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(ConversationFilter.entries) { filter ->
                            val isSelected = uiState.activeFilter == filter
                            val count = when (filter) {
                                ConversationFilter.ALL -> uiState.conversations.size
                                ConversationFilter.PINNED -> uiState.conversations.count { it.isPinned }
                                ConversationFilter.UNREAD -> uiState.conversations.count { it.isUnread }
                            }
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.onFilterSelected(filter) },
                                label = {
                                    Text(
                                        text = "${filter.displayName} ($count)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                },
                                leadingIcon = if (isSelected) {
                                    {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                } else null,
                                shape = RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            )
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !uiState.isSelectionMode,
                enter = scaleIn(animationSpec = motionScheme.fastSpatialSpec()) +
                    fadeIn(animationSpec = motionScheme.fastEffectsSpec()),
                exit = scaleOut(animationSpec = motionScheme.fastSpatialSpec()) +
                    fadeOut(animationSpec = motionScheme.fastEffectsSpec())
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showProfileSheet = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Consultation", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.padding(bottom = 80.dp)
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.5.dp)
                    }
                }

                uiState.filteredConversations.isEmpty() -> {
                    EmptySessionsView(
                        searchQuery = uiState.searchQuery,
                        onNewSessionClick = { showProfileSheet = true }
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = uiState.filteredConversations,
                            key = { it.id }
                        ) { conversation ->
                            val isItemSelected = uiState.selectedIds.contains(conversation.id)

                            val dismissState = rememberSwipeToDismissBoxState()
                            LaunchedEffect(conversation.id, conversation.isPinned) {
                                dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                            }

                            SwipeToDismissBox(
                                state = dismissState,
                                enableDismissFromStartToEnd = !uiState.isSelectionMode,
                                enableDismissFromEndToStart = !uiState.isSelectionMode,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .animateItem(
                                        fadeInSpec = motionScheme.fastEffectsSpec(),
                                        fadeOutSpec = motionScheme.fastEffectsSpec(),
                                        placementSpec = motionScheme.fastSpatialSpec()
                                    ),
                                onDismiss = { direction ->
                                    if (direction == SwipeToDismissBoxValue.EndToStart) {
                                        val targetId = conversation.id
                                        scope.launch {
                                            val backup = viewModel.deleteSingleAndReturnBackup(targetId)
                                            if (backup != null) {
                                                pendingDelete = listOf(backup)
                                            }
                                        }
                                    } else if (direction == SwipeToDismissBoxValue.StartToEnd) {
                                        viewModel.togglePin(conversation.id)
                                        scope.launch {
                                            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                                        }
                                    }
                                },
                                backgroundContent = {
                                    val direction = dismissState.dismissDirection
                                    val isStartToEnd = direction == SwipeToDismissBoxValue.StartToEnd
                                    val color by animateColorAsState(
                                        targetValue = when (direction) {
                                            SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                            SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                                            else -> Color.Transparent
                                        },
                                        label = "swipeBg"
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(color)
                                            .padding(horizontal = 20.dp),
                                        contentAlignment = if (isStartToEnd) Alignment.CenterStart else Alignment.CenterEnd
                                    ) {
                                        if (isStartToEnd) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.PushPin,
                                                    contentDescription = if (conversation.isPinned) "Unpin" else "Pin",
                                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                                Text(
                                                    text = if (conversation.isPinned) "Unpin" else "Pin",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                                )
                                            }
                                        } else if (direction == SwipeToDismissBoxValue.EndToStart) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "Delete",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                    color = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "Delete conversation",
                                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                                )
                                            }
                                        }
                                    }
                                },
                                content = {
                                    ClinicalSessionCard(
                                        conversation = conversation,
                                        isSelectionMode = uiState.isSelectionMode,
                                        isSelected = isItemSelected,
                                        onClick = {
                                            viewModel.markAsRead(conversation.id)
                                            onConversationSelected(conversation.id)
                                        },
                                        onLongClick = {
                                            viewModel.toggleSelectionMode()
                                            viewModel.toggleItemSelection(conversation.id)
                                        },
                                        onToggleSelect = {
                                            viewModel.toggleItemSelection(conversation.id)
                                        }
                                    )
                                }
                            )
                        }
                    }
                }
            }

            ScrollToTopFAB(
                visible = showScrollToTop,
                onClick = {
                    scope.launch { listState.animateScrollToItem(0) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
            )
        }
    }

    // Snackbar Undo Handler
    LaunchedEffect(pendingDelete) {
        pendingDelete.takeIf { it.isNotEmpty() }?.let { backups ->
            if (isFabDelete) {
                delay(300)
                isFabDelete = false
            }
            val message = if (backups.size == 1) "Session deleted" else "${backups.size} sessions deleted"
            showUndoSnackbar(
                snackbarHostState = snackbarHostState,
                message = message
            ) {
                backups.forEach { viewModel.restoreConversationBackup(it) }
            }
            pendingDelete = emptyList()
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

/**
 * Individual Clinical Consultation Card with Patient Profile Metadata.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ClinicalSessionCard(
    conversation: ConversationUiModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val profile = conversation.patientProfile
    val demographicBadge = buildString {
        if (profile.age.isNotBlank()) append(profile.age.filter { it.isDigit() }.take(2))
        if (profile.sex.isNotBlank()) append(profile.sex.take(1).uppercase())
    }.ifBlank {
        conversation.title.filter { it.isLetter() }.take(2).uppercase().ifBlank { "CS" }
    }

    val cardColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else if (conversation.isPinned) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = if (isSelectionMode) onToggleSelect else onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Checkbox (if selection) + Demographic Badge + Title & Status + Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (conversation.isPinned) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = demographicBadge,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            ),
                            color = if (conversation.isPinned) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = conversation.title,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (conversation.isUnread) FontWeight.Bold else FontWeight.SemiBold,
                                    fontSize = 14.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (conversation.isPinned) {
                                Icon(
                                    imageVector = Icons.Default.PushPin,
                                    contentDescription = "Pinned",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }

                            if (conversation.isUnread) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }

                        Text(
                            text = formatTimestamp(conversation.timestamp),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Row 2: Clinical Condition Micro-Chips
            if (profile.conditions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    profile.conditions.take(3).forEach { cond ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = cond,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (profile.conditions.size > 3) {
                        Text(
                            text = "+${profile.conditions.size - 3}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }

            // Row 3: Last Message Preview
            Text(
                text = conversation.lastPreview,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Empty state for when no consultations exist or search matches zero items.
 */
@Composable
private fun EmptySessionsView(
    searchQuery: String,
    onNewSessionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Forum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Text(
                text = if (searchQuery.isNotBlank()) "No Matching Sessions" else "No Consultations Yet",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = if (searchQuery.isNotBlank()) {
                    "No sessions match \"$searchQuery\". Try checking the spelling or search by condition."
                } else {
                    "Start a clinical session to synthesize UpToDate evidence, calculate dosages, and evaluate drug interactions."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            if (searchQuery.isBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onNewSessionClick,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Consultation", fontWeight = FontWeight.SemiBold)
                }
            }
        }
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
