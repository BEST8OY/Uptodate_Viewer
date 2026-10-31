package com.clinref.app.ui.conversations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
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
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.adaptive.LocalBottomBarVisible
import com.clinref.app.ui.common.BackHandler
import com.clinref.app.ui.common.ScrollToTopFAB
import com.clinref.app.ui.common.showUndoSnackbar
import com.clinref.app.ui.navigation.AiRoute
import com.clinref.app.util.formatTimestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Streamlined Clinical Consultations (Chat List) Screen.
 *
 * Designed for fast clinical decision support: compact top bar with docked search,
 * high-density cards with demographic squircle avatars and condition micro-chips,
 * dual-direction swipe gestures, 1-tap instant consultation start, and native TopAppBar
 * batch selection.
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

    val isBottomBarVisible = LocalBottomBarVisible.current
    val bottomInsetPadding = if (isBottomBarVisible) 80.dp else 16.dp

    LaunchedEffect(reselectEvents) {
        reselectEvents?.collect { route ->
            if (route == AiRoute) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var sessionToRename by remember { mutableStateOf<ConversationUiModel?>(null) }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<List<ConversationBackup>>(emptyList()) }

    val textFieldState = rememberTextFieldState()

    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            pendingDelete = emptyList()
        }
    }

    LaunchedEffect(textFieldState.text) {
        viewModel.onSearchQueryChange(textFieldState.text.toString())
    }

    // Snackbar Undo Handler for Deleted Sessions
    LaunchedEffect(pendingDelete) {
        pendingDelete.takeIf { it.isNotEmpty() }?.let { backups ->
            val message = if (backups.size == 1) "Consultation deleted" else "${backups.size} consultations deleted"
            showUndoSnackbar(
                snackbarHostState = snackbarHostState,
                message = message
            ) {
                backups.forEach { viewModel.restoreConversationBackup(it) }
            }
            pendingDelete = emptyList()
        }
    }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.toggleSelectionMode()
    }

    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    val isScrollingDown by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 && listState.isScrollInProgress }
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
            title = { Text("Delete Selected Consultations?") },
            text = { Text("Are you sure you want to delete ${uiState.selectedIds.size} clinical consultation(s)?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val backups = viewModel.deleteSelectedAndReturnBackups()
                            pendingDelete = backups
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

    // Rename Consultation Dialog
    sessionToRename?.let { target ->
        RenameSessionDialog(
            initialTitle = target.title,
            onDismiss = { sessionToRename = null },
            onConfirm = { newTitle ->
                viewModel.renameConversation(target.id, newTitle)
                sessionToRename = null
            }
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("conversation_list_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = bottomInsetPadding)
            )
        },
        topBar = {
            ClinicalMessengerHeader(
                uiState = uiState,
                textFieldState = textFieldState,
                onToggleSelectionMode = { viewModel.toggleSelectionMode() },
                onSelectAll = { viewModel.selectAll() },
                onTogglePinSelected = { viewModel.togglePinSelected() },
                onMarkSelectedAsRead = { viewModel.markSelectedAsRead() },
                onDeleteSelected = { viewModel.showDeleteConfirmation() },
                onRenameSingleSelected = {
                    val singleId = uiState.selectedIds.singleOrNull()
                    if (singleId != null) {
                        sessionToRename = uiState.conversations.find { it.id == singleId }
                    }
                },
                onOpenSettings = onOpenSettings,
                onFilterSelected = { viewModel.onFilterSelected(it) }
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !uiState.isSelectionMode,
                enter = scaleIn(animationSpec = motionScheme.fastSpatialSpec()) +
                    fadeIn(animationSpec = motionScheme.fastEffectsSpec()),
                exit = scaleOut(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                ) + fadeOut(animationSpec = motionScheme.fastEffectsSpec())
            ) {
                ExtendedFloatingActionButton(
                    expanded = !isScrollingDown,
                    onClick = {
                        // 1-Tap Instant Start: Immediate clinical consultation without modal friction
                        viewModel.createNewSession(PatientProfile()) { newId ->
                            onConversationSelected(newId)
                        }
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New Consultation", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .padding(bottom = bottomInsetPadding)
                        .testTag("new_consultation_fab")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
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
                        activeFilter = uiState.activeFilter,
                        onNewSessionClick = {
                            viewModel.createNewSession(PatientProfile()) { newId ->
                                onConversationSelected(newId)
                            }
                        }
                    )
                }

                else -> {
                    val isFilteringPinnedOnly = uiState.activeFilter == ConversationFilter.PINNED
                    val pinnedConversations = remember(uiState.filteredConversations) {
                        uiState.filteredConversations.filter { it.isPinned }
                    }
                    val recentConversations = remember(uiState.filteredConversations) {
                        uiState.filteredConversations.filter { !it.isPinned }
                    }
                    val showSectionHeaders = !isFilteringPinnedOnly &&
                        pinnedConversations.isNotEmpty() &&
                        recentConversations.isNotEmpty()

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(max = AdaptiveTokens.MaxCatalogListWidth)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = bottomInsetPadding + 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Section 1: Pinned Consultations
                        if (pinnedConversations.isNotEmpty()) {
                            if (showSectionHeaders || isFilteringPinnedOnly) {
                                item(key = "header_pinned") {
                                    SectionHeader(
                                        title = "PINNED",
                                        count = pinnedConversations.size,
                                        icon = Icons.Default.PushPin
                                    )
                                }
                            }

                            items(
                                items = pinnedConversations,
                                key = { it.id }
                            ) { conversation ->
                                ConversationSwipeableItem(
                                    conversation = conversation,
                                    uiState = uiState,
                                    onConversationSelected = onConversationSelected,
                                    onPinClick = { viewModel.togglePin(conversation.id) },
                                    onMarkAsReadClick = { viewModel.markAsRead(conversation.id) },
                                    onDeleteClick = {
                                        scope.launch {
                                            val backup = viewModel.deleteSingleAndReturnBackup(conversation.id)
                                            if (backup != null) {
                                                pendingDelete = listOf(backup)
                                            }
                                        }
                                    },
                                    onToggleItemSelection = { viewModel.toggleItemSelection(conversation.id) },
                                    onEnterSelectionMode = {
                                        viewModel.toggleSelectionMode()
                                        viewModel.toggleItemSelection(conversation.id)
                                    }
                                )
                            }
                        }

                        // Section 2: Recent Consultations
                        if (recentConversations.isNotEmpty()) {
                            if (showSectionHeaders) {
                                item(key = "header_recent") {
                                    SectionHeader(
                                        title = "RECENT SESSIONS",
                                        count = recentConversations.size,
                                        icon = Icons.Default.History,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            items(
                                items = recentConversations,
                                key = { it.id }
                            ) { conversation ->
                                ConversationSwipeableItem(
                                    conversation = conversation,
                                    uiState = uiState,
                                    onConversationSelected = onConversationSelected,
                                    onPinClick = { viewModel.togglePin(conversation.id) },
                                    onMarkAsReadClick = { viewModel.markAsRead(conversation.id) },
                                    onDeleteClick = {
                                        scope.launch {
                                            val backup = viewModel.deleteSingleAndReturnBackup(conversation.id)
                                            if (backup != null) {
                                                pendingDelete = listOf(backup)
                                            }
                                        }
                                    },
                                    onToggleItemSelection = { viewModel.toggleItemSelection(conversation.id) },
                                    onEnterSelectionMode = {
                                        viewModel.toggleSelectionMode()
                                        viewModel.toggleItemSelection(conversation.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Scroll to Top Floating Action Button
            ScrollToTopFAB(
                visible = showScrollToTop && !uiState.isSelectionMode,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInsetPadding + 16.dp)
                    .testTag("scroll_to_top_fab")
            )
        }
    }
}

/**
 * Clean Top app bar with docked search and standard M3 multi-selection bar.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ClinicalMessengerHeader(
    uiState: ConversationListUiState,
    textFieldState: androidx.compose.foundation.text.input.TextFieldState,
    onToggleSelectionMode: () -> Unit,
    onSelectAll: () -> Unit,
    onTogglePinSelected: () -> Unit,
    onMarkSelectedAsRead: () -> Unit,
    onDeleteSelected: () -> Unit,
    onRenameSingleSelected: () -> Unit,
    onOpenSettings: () -> Unit,
    onFilterSelected: (ConversationFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val motionScheme = MaterialTheme.motionScheme

    if (uiState.isSelectionMode) {
        TopAppBar(
            title = {
                Text(
                    text = "${uiState.selectedIds.size} selected",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            navigationIcon = {
                IconButton(onClick = onToggleSelectionMode) {
                    Icon(Icons.Default.Close, contentDescription = "Exit selection")
                }
            },
            actions = {
                // If exactly 1 item selected, allow renaming
                if (uiState.selectedIds.size == 1) {
                    IconButton(onClick = onRenameSingleSelected) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Rename consultation",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                IconButton(onClick = onTogglePinSelected) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pin or unpin selected",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = onMarkSelectedAsRead) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Mark selected as read",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = onDeleteSelected) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete selected",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                val allSelected = uiState.selectedIds.size == uiState.filteredConversations.size &&
                    uiState.filteredConversations.isNotEmpty()
                IconButton(onClick = onSelectAll) {
                    Icon(
                        imageVector = if (allSelected) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                        contentDescription = "Select All",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = modifier
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Consultations",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        if (uiState.filteredConversations.isNotEmpty()) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest
                            ) {
                                Text(
                                    text = uiState.filteredConversations.size.toString(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                },
                actions = {
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

            // Sleek M3 Expressive Search Pill
            var isSearchFocused by remember { mutableStateOf(false) }
            val animatedBorderColor by animateColorAsState(
                targetValue = if (isSearchFocused) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
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
                    .widthIn(max = AdaptiveTokens.MaxCatalogListWidth)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                shape = RoundedCornerShape(20.dp),
                color = searchContainerColor,
                border = BorderStroke(if (isSearchFocused) 1.5.dp else 1.dp, animatedBorderColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = if (isSearchFocused || textFieldState.text.isNotEmpty())
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextField(
                        state = textFieldState,
                        placeholder = {
                            Text(
                                text = "Search consultations, conditions, topics...",
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
                            .testTag("conversations_search_field")
                            .onFocusChanged { isSearchFocused = it.isFocused }
                    )
                    AnimatedVisibility(
                        visible = textFieldState.text.isNotEmpty(),
                        enter = fadeIn(motionScheme.fastEffectsSpec()) + scaleIn(motionScheme.fastSpatialSpec()),
                        exit = fadeOut(motionScheme.fastEffectsSpec()) + scaleOut(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                    ) {
                        IconButton(
                            onClick = { textFieldState.clearText() },
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            // Compact Filter Chips Row
            Box(
                modifier = Modifier
                    .widthIn(max = AdaptiveTokens.MaxCatalogListWidth)
                    .fillMaxWidth(),
                contentAlignment = Alignment.CenterStart
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp),
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
                            onClick = { onFilterSelected(filter) },
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
                            shape = RoundedCornerShape(10.dp),
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
    }
}

/**
 * Section Header for Pinned / Recent consultation groups.
 */
@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            ),
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.padding(start = 2.dp)
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
            )
        }
    }
}

/**
 * Swipe-to-dismiss wrapper around clinical session card.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LazyItemScope.ConversationSwipeableItem(
    conversation: ConversationUiModel,
    uiState: ConversationListUiState,
    onConversationSelected: (String) -> Unit,
    onPinClick: () -> Unit,
    onMarkAsReadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onToggleItemSelection: () -> Unit,
    onEnterSelectionMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motionScheme = MaterialTheme.motionScheme
    val dismissState = rememberSwipeToDismissBoxState()

    LaunchedEffect(conversation.id, conversation.isPinned) {
        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !uiState.isSelectionMode,
        enableDismissFromEndToStart = !uiState.isSelectionMode,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .animateItem(
                fadeInSpec = motionScheme.fastEffectsSpec(),
                fadeOutSpec = motionScheme.fastEffectsSpec(),
                placementSpec = motionScheme.fastSpatialSpec()
            ),
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                onDeleteClick()
            } else if (direction == SwipeToDismissBoxValue.StartToEnd) {
                onPinClick()
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
                            contentDescription = "Delete consultation",
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
                isSelected = uiState.selectedIds.contains(conversation.id),
                onClick = {
                    onMarkAsReadClick()
                    onConversationSelected(conversation.id)
                },
                onLongClick = {
                    if (!uiState.isSelectionMode) {
                        onEnterSelectionMode()
                    }
                },
                onToggleSelect = onToggleItemSelection,
                modifier = Modifier.testTag("conversation_card_${conversation.id}")
            )
        }
    )
}

/**
 * Clean Clinical session list item card without 3-dot clutter,
 * featuring demographic avatar, single unread dot, micro-chips, and clean preview.
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

    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        conversation.isPinned -> MaterialTheme.colorScheme.surfaceContainerHigh
        conversation.isUnread -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        conversation.isPinned -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        conversation.isUnread -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
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
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Row 1: Monogram Avatar + Title + Timestamp & Pin
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ClinicalDemographicAvatar(
                    conversation = conversation,
                    isSelectionMode = isSelectionMode,
                    isSelected = isSelected,
                    onToggleSelect = onToggleSelect
                )

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Single, authoritative unread indicator dot
                        if (conversation.isUnread) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                        Text(
                            text = conversation.title.ifBlank { "Clinical Consultation" },
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = if (conversation.isUnread) FontWeight.Bold else FontWeight.SemiBold
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (conversation.timestamp > 0) {
                        Text(
                            text = formatTimestamp(conversation.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (conversation.isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
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
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ) {
                            Text(
                                text = cond,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (profile.conditions.size > 3) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = "+${profile.conditions.size - 3}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Row 3: Clean Message Preview without Synthetic App Prefixes
            Text(
                text = conversation.lastPreview.ifBlank { "No messages yet" },
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Unified Demographics Avatar with consistent styling or clinical chat glyph.
 */
@Composable
private fun ClinicalDemographicAvatar(
    conversation: ConversationUiModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isSelectionMode) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggleSelect() },
            modifier = modifier.size(40.dp)
        )
    } else {
        val profile = conversation.patientProfile
        val ageDigits = profile.age.filter { it.isDigit() }.take(2)
        val sexChar = profile.sex.trim().take(1).uppercase()
        val demographicBadge = buildString {
            if (ageDigits.isNotBlank()) append(ageDigits)
            if (sexChar.isNotBlank()) append(sexChar)
        }

        val hasDemographics = demographicBadge.isNotBlank()
        val containerColor = if (conversation.isPinned) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
        val contentColor = if (conversation.isPinned) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = containerColor,
            modifier = modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (hasDemographics) {
                    Text(
                        text = demographicBadge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        ),
                        color = contentColor
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * Dialog to rename an existing consultation session.
 */
@Composable
private fun RenameSessionDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Rename Consultation") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Consultation Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank()) {
                        onConfirm(title.trim())
                    }
                },
                enabled = title.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        shape = RoundedCornerShape(24.dp)
    )
}

/**
 * Minimalist medical placeholder when no consultations exist or search matches zero items.
 */
@Composable
private fun EmptySessionsView(
    searchQuery: String,
    activeFilter: ConversationFilter,
    onNewSessionClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when {
        searchQuery.isNotBlank() -> Icons.Default.Search
        activeFilter == ConversationFilter.PINNED -> Icons.Default.PushPin
        activeFilter == ConversationFilter.UNREAD -> Icons.Default.CheckCircle
        else -> Icons.Default.Forum
    }

    val title = when {
        searchQuery.isNotBlank() -> "No Matching Consultations"
        activeFilter == ConversationFilter.PINNED -> "No Pinned Consultations"
        activeFilter == ConversationFilter.UNREAD -> "No Unread Consultations"
        else -> "No Consultations Yet"
    }

    val description = when {
        searchQuery.isNotBlank() -> "No consultations match \"$searchQuery\". Check your search term or try searching by clinical condition."
        activeFilter == ConversationFilter.PINNED -> "Swipe right on a consultation to pin critical clinical cases for immediate access."
        activeFilter == ConversationFilter.UNREAD -> "All clinical consultations have been reviewed."
        else -> "Start a clinical consultation to synthesize UpToDate evidence, calculate dosages, and evaluate drug interactions."
    }

    val showStartButton = searchQuery.isBlank() && activeFilter == ConversationFilter.ALL

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            if (showStartButton) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onNewSessionClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Consultation", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
