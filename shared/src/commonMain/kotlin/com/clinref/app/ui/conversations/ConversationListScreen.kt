package com.clinref.app.ui.conversations

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PersonAdd
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
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
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
import com.clinref.app.ui.common.showUndoSnackbar
import com.clinref.app.ui.navigation.AiRoute
import com.clinref.app.ui.navigation.bindReselectScrollToTop
import com.clinref.app.util.formatTimestamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * Streamlined Clinical Consultations (Chat List) Screen.
 *
 * Designed for fast clinical decision support: compact top bar with docked search,
 * high-density cards with demographic squircle avatars and condition micro-chips,
 * dual-direction swipe gestures, Speed Dial FAB with fast start vs patient profile,
 * and native TopAppBar batch selection.
 */
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
    listState.bindReselectScrollToTop(reselectEvents, AiRoute)
    val snackbarHostState = remember { SnackbarHostState() }
    var sessionToRename by remember { mutableStateOf<ConversationUiModel?>(null) }
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<List<ConversationBackup>>(emptyList()) }

    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            pendingDelete = emptyList()
        }
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
        viewModel.clearSelectionMode()
    }

    ConversationListContent(
        uiState = uiState,
        listState = listState,
        snackbarHostState = snackbarHostState,
        sessionToRename = sessionToRename,
        onSessionToRenameChange = { sessionToRename = it },
        onSearchQueryChange = { viewModel.onSearchQueryChange(it) },
        onToggleSelectionMode = { viewModel.toggleSelectionMode() },
        onClearSelectionMode = { viewModel.clearSelectionMode() },
        onToggleItemSelection = { viewModel.toggleItemSelection(it) },
        onSelectAll = { viewModel.selectAll() },
        onTogglePinSelected = { viewModel.togglePinSelected() },
        onShowDeleteConfirmation = { viewModel.showDeleteConfirmation() },
        onDismissDeleteConfirmation = { viewModel.dismissDeleteConfirmation() },
        onConfirmDeleteSelected = {
            scope.launch {
                val backups = viewModel.deleteSelectedAndReturnBackups()
                pendingDelete = backups
            }
        },
        onRenameConversation = { id, newTitle ->
            viewModel.renameConversation(id, newTitle)
            viewModel.clearSelectionMode()
        },
        onConversationSelected = onConversationSelected,
        onPinClick = { viewModel.togglePin(it) },
        onDeleteSingleClick = { id ->
            scope.launch {
                val backup = viewModel.deleteSingleAndReturnBackup(id)
                if (backup != null) {
                    pendingDelete = listOf(backup)
                }
            }
        },
        onNewSessionClick = {
            viewModel.createNewSession(PatientProfile()) { newId ->
                onConversationSelected(newId)
            }
        },
        onNewSessionWithProfileClick = { profile ->
            viewModel.createNewSession(profile) { newId ->
                onConversationSelected(newId)
            }
        },
        onOpenSettings = onOpenSettings,
        modifier = modifier
    )
}

/**
 * Stateless content composable for the Clinical Consultations screen.
 * Decoupled from ViewModel for easy previewing and isolated UI testing.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun ConversationListContent(
    uiState: ConversationListUiState,
    listState: LazyListState = rememberLazyListState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    sessionToRename: ConversationUiModel? = null,
    onSessionToRenameChange: (ConversationUiModel?) -> Unit = {},
    onSearchQueryChange: (String) -> Unit = {},
    onToggleSelectionMode: () -> Unit = {},
    onClearSelectionMode: () -> Unit = {},
    onToggleItemSelection: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onTogglePinSelected: () -> Unit = {},
    onShowDeleteConfirmation: () -> Unit = {},
    onDismissDeleteConfirmation: () -> Unit = {},
    onConfirmDeleteSelected: () -> Unit = {},
    onRenameConversation: (String, String) -> Unit = { _, _ -> },
    onConversationSelected: (String) -> Unit = {},
    onPinClick: (String) -> Unit = {},
    onDeleteSingleClick: (String) -> Unit = {},
    onNewSessionClick: () -> Unit = {},
    onNewSessionWithProfileClick: (PatientProfile) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val motionScheme = MaterialTheme.motionScheme
    val scope = rememberCoroutineScope()

    val isBottomBarVisible = LocalBottomBarVisible.current

    val textFieldState = rememberTextFieldState(initialText = uiState.searchQuery)

    // Speed Dial Menu State
    var isFabMenuExpanded by remember { mutableStateOf(false) }
    var showPatientProfileSheet by remember { mutableStateOf(false) }

    // Close FAB menu on system back
    BackHandler(enabled = isFabMenuExpanded) {
        isFabMenuExpanded = false
    }

    // Debounced query emission to prevent UI thrashing during rapid typing
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collectLatest { query ->
                if (query.isNotBlank()) {
                    delay(200)
                }
                onSearchQueryChange(query)
            }
    }

    // Confirmation Dialog for Batch Deletions
    if (uiState.showDeleteConfirmationDialog) {
        AlertDialog(
            onDismissRequest = onDismissDeleteConfirmation,
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
                    onClick = onConfirmDeleteSelected,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDeleteConfirmation) {
                    Text("Cancel")
                }
            },
            shape = MaterialTheme.shapes.extraLarge
        )
    }

    // Rename Consultation Dialog
    sessionToRename?.let { target ->
        RenameSessionDialog(
            initialTitle = target.title,
            onDismiss = { onSessionToRenameChange(null) },
            onConfirm = { newTitle ->
                onRenameConversation(target.id, newTitle)
                onSessionToRenameChange(null)
            }
        )
    }

    // Patient Profile Input Bottom Sheet / Dialog
    if (showPatientProfileSheet) {
        PatientProfileSheet(
            onDismiss = { showPatientProfileSheet = false },
            onStart = { profile ->
                showPatientProfileSheet = false
                onNewSessionWithProfileClick(profile)
            }
        )
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("conversation_list_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        bottomBar = {
            if (isBottomBarVisible) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .height(80.dp)
                )
            }
        },
        topBar = {
            ClinicalMessengerHeader(
                uiState = uiState,
                textFieldState = textFieldState,
                onToggleSelectionMode = onToggleSelectionMode,
                onSelectAll = onSelectAll,
                onTogglePinSelected = onTogglePinSelected,
                onDeleteSelected = onShowDeleteConfirmation,
                onRenameSingleSelected = {
                    val singleId = uiState.selectedIds.singleOrNull()
                    if (singleId != null) {
                        val session = uiState.conversations.find { it.id == singleId }
                        onSessionToRenameChange(session)
                    }
                },
                onOpenSettings = onOpenSettings
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
                FloatingActionButtonMenu(
                    expanded = isFabMenuExpanded,
                    button = {
                        ToggleFloatingActionButton(
                            checked = isFabMenuExpanded,
                            onCheckedChange = { isFabMenuExpanded = !isFabMenuExpanded },
                            containerColor = ToggleFloatingActionButtonDefaults.containerColor(
                                initialColor = MaterialTheme.colorScheme.primary,
                                finalColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.testTag("new_consultation_fab")
                        ) {
                            val imageVector by remember {
                                derivedStateOf {
                                    if (checkedProgress > 0.5f) Icons.Default.Close else Icons.Default.Add
                                }
                            }
                            Icon(
                                painter = rememberVectorPainter(imageVector),
                                contentDescription = if (isFabMenuExpanded) "Close menu" else "New Consultation",
                                modifier = Modifier.animateIcon(
                                    checkedProgress = { checkedProgress },
                                    color = ToggleFloatingActionButtonDefaults.iconColor(
                                        initialColor = MaterialTheme.colorScheme.onPrimary,
                                        finalColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                )
                            )
                        }
                    }
                ) {
                    FloatingActionButtonMenuItem(
                        onClick = {
                            isFabMenuExpanded = false
                            showPatientProfileSheet = true
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.PersonAdd,
                                contentDescription = null
                            )
                        },
                        text = { Text("Patient Case") },
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("fab_with_profile")
                    )

                    FloatingActionButtonMenuItem(
                        onClick = {
                            isFabMenuExpanded = false
                            onNewSessionClick()
                        },
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null
                            )
                        },
                        text = { Text("Quick Consultation") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.testTag("fab_fast_consultation")
                    )
                }
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
                        onNewSessionClick = onNewSessionClick,
                        onClearSearch = { textFieldState.clearText() }
                    )
                }

                else -> {
                    val showSectionHeaders = uiState.pinnedConversations.isNotEmpty() &&
                        uiState.recentConversations.isNotEmpty()

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(max = AdaptiveTokens.MaxCatalogListWidth)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Section 1: Pinned Consultations
                        if (uiState.pinnedConversations.isNotEmpty()) {
                            if (showSectionHeaders) {
                                item(key = "header_pinned") {
                                    SectionHeader(
                                        title = "PINNED",
                                        count = uiState.pinnedConversations.size,
                                        icon = Icons.Default.PushPin
                                    )
                                }
                            }

                            items(
                                items = uiState.pinnedConversations,
                                key = { it.id }
                            ) { conversation ->
                                ConversationSwipeableItem(
                                    conversation = conversation,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = conversation.id in uiState.selectedIds,
                                    onConversationSelected = onConversationSelected,
                                    onPinClick = { onPinClick(conversation.id) },
                                    onDeleteClick = { onDeleteSingleClick(conversation.id) },
                                    onToggleItemSelection = { onToggleItemSelection(conversation.id) },
                                    onEnterSelectionMode = {
                                        onToggleSelectionMode()
                                        onToggleItemSelection(conversation.id)
                                    }
                                )
                            }
                        }

                        // Section 2: Recent Consultations
                        if (uiState.recentConversations.isNotEmpty()) {
                            if (showSectionHeaders) {
                                item(key = "header_recent") {
                                    SectionHeader(
                                        title = "RECENT SESSIONS",
                                        count = uiState.recentConversations.size,
                                        icon = Icons.Default.History,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }

                            items(
                                items = uiState.recentConversations,
                                key = { it.id }
                            ) { conversation ->
                                ConversationSwipeableItem(
                                    conversation = conversation,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = conversation.id in uiState.selectedIds,
                                    onConversationSelected = onConversationSelected,
                                    onPinClick = { onPinClick(conversation.id) },
                                    onDeleteClick = { onDeleteSingleClick(conversation.id) },
                                    onToggleItemSelection = { onToggleItemSelection(conversation.id) },
                                    onEnterSelectionMode = {
                                        onToggleSelectionMode()
                                        onToggleItemSelection(conversation.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Dismissible Scrim when Speed Dial is expanded
            if (isFabMenuExpanded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { isFabMenuExpanded = false }
                )
            }
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
    onDeleteSelected: () -> Unit,
    onRenameSingleSelected: () -> Unit,
    onOpenSettings: () -> Unit,
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

                IconButton(onClick = onDeleteSelected) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete selected",
                        tint = MaterialTheme.colorScheme.error
                    )
                }

                // Show Select All ONLY when partially selected (Close X handles deselect/exit)
                val notAllSelected = uiState.selectedIds.size < uiState.filteredConversations.size &&
                    uiState.filteredConversations.isNotEmpty()
                if (notAllSelected) {
                    IconButton(onClick = onSelectAll) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = "Select All",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
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
                .padding(bottom = 6.dp),
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
                else MaterialTheme.colorScheme.outlineVariant,
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
                shape = MaterialTheme.shapes.extraLarge,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                            modifier = Modifier
                                .size(36.dp)
                                .minimumInteractiveComponentSize()
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
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
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
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onConversationSelected: (String) -> Unit,
    onPinClick: () -> Unit,
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
        enableDismissFromStartToEnd = !isSelectionMode,
        enableDismissFromEndToStart = !isSelectionMode,
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
                    .clip(MaterialTheme.shapes.large)
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
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                onClick = { onConversationSelected(conversation.id) },
                onLongClick = {
                    if (!isSelectionMode) {
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
 * featuring demographic avatar, micro-chips, and clean preview.
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
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        conversation.isPinned -> MaterialTheme.colorScheme.outlineVariant
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
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
                    Text(
                        text = conversation.title.ifBlank { "Clinical Consultation" },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )

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
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = cond,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (profile.conditions.size > 3) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = "+${profile.conditions.size - 3}",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
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
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            onCheckedChange = null,
            modifier = modifier
                .size(32.dp)
                .minimumInteractiveComponentSize()
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
            shape = MaterialTheme.shapes.medium,
            color = containerColor,
            modifier = modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (hasDemographics) {
                    Text(
                        text = demographicBadge,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
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
        shape = MaterialTheme.shapes.extraLarge
    )
}

/**
 * Minimalist medical placeholder when no consultations exist or search matches zero items.
 */
@Composable
private fun EmptySessionsView(
    searchQuery: String,
    onNewSessionClick: () -> Unit,
    onClearSearch: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isSearch = searchQuery.isNotBlank()
    val icon = if (isSearch) Icons.Default.Search else Icons.Default.Forum
    val title = if (isSearch) "No Matching Consultations" else "No Consultations Yet"
    val description = if (isSearch) {
        "No consultations match \"$searchQuery\". Check your search term or try searching by clinical condition."
    } else {
        "Start a clinical consultation to synthesize UpToDate evidence, calculate dosages, and evaluate drug interactions."
    }

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
                color = MaterialTheme.colorScheme.primaryContainer,
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

            if (isSearch) {
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onClearSearch,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Clear Search")
                }
            } else {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = onNewSessionClick,
                    shape = MaterialTheme.shapes.medium,
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
