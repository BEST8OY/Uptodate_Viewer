package com.clinref.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.clinref.app.domain.ai.TopicRef
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.chat.components.ChatInputBar
import com.clinref.app.ui.chat.components.ChatMessageItem
import com.clinref.app.ui.chat.components.ClinicalReferencesSection
import com.clinref.app.ui.common.BackHandler
import com.clinref.app.ui.common.ScrollToBottomFAB
import com.clinref.app.ui.common.setPlainText
import com.clinref.app.ui.content.ContentScreen
import com.clinref.app.ui.content.GraphicSheet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

private object ChatListKeys {
    const val TOP_PAGINATION_SLOT = "top_pagination_slot"
    const val BOTTOM_ANCHOR = "bottom_anchor"
}

data class ArticleSheetRequest(
    val topicId: String,
    val sectionId: String? = null
)

private val ArticleSheetRequestSaver = listSaver<ArticleSheetRequest?, String?>(
    save = { value ->
        if (value == null) emptyList() else listOf(value.topicId, value.sectionId)
    },
    restore = { list ->
        val topic = list.getOrNull(0) ?: return@listSaver null
        ArticleSheetRequest(topicId = topic, sectionId = list.getOrNull(1))
    }
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateToHome: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToSettings: (() -> Unit)? = null,
    viewModel: ChatViewModel = koinViewModel()
) {
    val chatItems by viewModel.chatItems.collectAsStateWithLifecycle()
    val conversationTitle by viewModel.conversationTitle.collectAsStateWithLifecycle()
    val loadedConversationId by viewModel.currentConversationId.collectAsStateWithLifecycle()
    val isLoadingConversation by viewModel.isLoadingConversation.collectAsStateWithLifecycle()
    val activeAssistantId by viewModel.activeAssistantMessageId.collectAsStateWithLifecycle()
    val isAwaitingResponse by viewModel.isAwaitingResponse.collectAsStateWithLifecycle()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsStateWithLifecycle()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsStateWithLifecycle()
    val patientProfile by viewModel.patientProfile.collectAsStateWithLifecycle()
    val config by viewModel.configuration.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val inputFocusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scrollController = rememberChatScrollController(conversationId, listState, scrollBehavior)

    val isGenerating = activeAssistantId != null || isAwaitingResponse

    val textFieldState = rememberTextFieldState()
    var editingUserMessageId by remember { mutableStateOf<String?>(null) }
    var chatInputHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val chatInputHeightDp = remember(chatInputHeightPx, density) {
        with(density) { chatInputHeightPx.toDp() }
    }

    var activeArticle by rememberSaveable(stateSaver = ArticleSheetRequestSaver) {
        mutableStateOf<ArticleSheetRequest?>(null)
    }
    var activeGraphicId by rememberSaveable { mutableStateOf<String?>(null) }
    var inArticleGraphicId by rememberSaveable { mutableStateOf<String?>(null) }
    val isOverlayActive = activeArticle != null || activeGraphicId != null

    val handleNavigateToContent: (String, String?) -> Unit = remember {
        { topicId, sectionId ->
            activeArticle = ArticleSheetRequest(topicId, sectionId)
        }
    }
    val handleGraphicSelected: (String) -> Unit = remember {
        { graphicId ->
            activeGraphicId = graphicId.removePrefix("Graphic-")
        }
    }

    // 1. Conversation load lifecycle & resilient initial scroll
    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    LaunchedEffect(conversationId, loadedConversationId, isLoadingConversation) {
        if (loadedConversationId == conversationId &&
            !isLoadingConversation &&
            !scrollController.hasCompletedInitialScroll
        ) {
            if (chatItems.isNotEmpty() || activeAssistantId != null) {
                snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
            }
            scrollController.jumpToBottomForInitialLoad()
        }
    }

    // 2. Track sticky-bottom pin state + auto-dismiss text selection toolbar when scrolling
    LaunchedEffect(listState, scrollController) {
        snapshotFlow { listState.isScrollInProgress to scrollController.isAtBottom }
            .distinctUntilChanged()
            .collect { (inProgress, atBottom) ->
                scrollController.onScrollStateObserved(inProgress, atBottom)
            }
    }

    // 3. Explicit scroll events from ViewModel (new user message, generation start)
    LaunchedEffect(scrollController) {
        viewModel.scrollEvents.collectLatest { event ->
            when (event) {
                ChatScrollEvent.InitialLoad -> {
                    if (!scrollController.hasCompletedInitialScroll) {
                        if (chatItems.isNotEmpty() || activeAssistantId != null) {
                            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                        }
                        scrollController.jumpToBottomForInitialLoad()
                    }
                }
                is ChatScrollEvent.ScrollToBottom -> {
                    if (event.awaitMessageId != null) {
                        snapshotFlow { chatItems }.first { items ->
                            items.any { it is ChatListItem.Message && it.uiModel.id == event.awaitMessageId }
                        }
                    } else {
                        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                    }
                    scrollController.scrollToBottom(animated = event.animated)
                }
            }
        }
    }

    // 4. Restore exact pixel anchor after older messages are prepended; auto-follow new bottom items if pinned
    val topHeaderCount = if (hasMoreMessages) 1 else 0
    LaunchedEffect(chatItems, topHeaderCount) {
        val didRestore = scrollController.restorePrependAnchorIfNeeded(chatItems, topHeaderCount)
        if (!didRestore && scrollController.hasCompletedInitialScroll && scrollController.isPinnedToBottom) {
            scrollController.followStreamingIfPinned()
        }
    }

    // 5. Sticky-bottom auto-follow during live streaming (only observes while activeAssistantId != null)
    LaunchedEffect(activeAssistantId) {
        if (activeAssistantId == null) return@LaunchedEffect
        viewModel.streamingUiState.collect {
            scrollController.followStreamingIfPinned()
        }
    }

    // 6. Top-edge pagination (strictly gated behind hasCompletedInitialScroll to prevent load-on-open cascade)
    val currentChatItems by rememberUpdatedState(chatItems)
    val currentIsLoadingOlder by rememberUpdatedState(isLoadingOlder)
    val currentHasMoreMessages by rememberUpdatedState(hasMoreMessages)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (scrollController.hasCompletedInitialScroll &&
                    index <= 1 &&
                    currentHasMoreMessages &&
                    !currentIsLoadingOlder
                ) {
                    scrollController.capturePrependAnchor(currentChatItems, topHeaderCount = 1)
                    viewModel.loadOlderMessages()
                }
            }
    }

    // 7. Zero-recomposition IME visibility observation via snapshotFlow
    val currentOverlayActive by rememberUpdatedState(isOverlayActive)
    val imeInsets = WindowInsets.ime
    LaunchedEffect(imeInsets, density) {
        var previousHeight = 0
        snapshotFlow { imeInsets.getBottom(density) }
            .distinctUntilChanged()
            .collect { height ->
                val opening = height > previousHeight && height > 0
                val closing = height == 0 && previousHeight > 0
                if (opening && !currentOverlayActive) {
                    scrollController.onImeOpenedIfPinned()
                } else if (closing) {
                    focusManager.clearFocus()
                    if (editingUserMessageId != null) {
                        editingUserMessageId = null
                        textFieldState.clearText()
                    }
                }
                previousHeight = height
            }
    }

    // 8. Dismiss keyboard when overlays activate
    LaunchedEffect(isOverlayActive) {
        if (isOverlayActive) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    LaunchedEffect(editingUserMessageId) {
        if (editingUserMessageId != null) {
            try {
                inputFocusRequester.requestFocus()
            } catch (_: Throwable) {
            }
            keyboardController?.show()
        }
    }

    BackHandler(enabled = editingUserMessageId != null) {
        editingUserMessageId = null
        textFieldState.clearText()
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    if (editingUserMessageId != null) {
                        editingUserMessageId = null
                        textFieldState.clearText()
                        focusManager.clearFocus()
                        true
                    } else false
                } else false
            },
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        text = conversationTitle.ifBlank { "Clinical Workspace" },
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                subtitle = {
                    Text(
                        text = "${config.provider.displayName} · ${config.model.ifBlank { "Default" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (onNavigateToSettings != null) {
                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "AI Settings",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .then(if (!isOverlayActive) Modifier.imePadding() else Modifier)
        ) {
            if (isLoadingConversation) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            } else if (chatItems.isEmpty() && !isLoadingOlder && activeAssistantId == null) {
                ChatEmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    val hasAssistantInPersisted = remember(chatItems, activeAssistantId) {
                        activeAssistantId != null && chatItems.any {
                            it is ChatListItem.Message && it.uiModel.id == activeAssistantId
                        }
                    }

                    val userScrollUnpinConnection = remember(scrollController) {
                        object : NestedScrollConnection {
                            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                                    scrollController.unpin()
                                }
                                return Offset.Zero
                            }
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(max = AdaptiveTokens.MaxCatalogListWidth)
                            .fillMaxWidth()
                            .suppressTransientSelectAllContextMenu()
                            .nestedScroll(userScrollUnpinConnection),
                            contentPadding = PaddingValues(
                                top = 8.dp,
                                bottom = if (chatInputHeightDp > 0.dp) chatInputHeightDp + 16.dp else 160.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Permanent top pagination slot while hasMoreMessages is true so
                            // toggling isLoadingOlder never shifts item indices by 1.
                            if (hasMoreMessages) {
                                item(key = ChatListKeys.TOP_PAGINATION_SLOT) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isLoadingOlder) {
                                            LoadingIndicator(
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            items(
                                items = chatItems,
                                key = { it.key }
                            ) { item ->
                                when (item) {
                                    is ChatListItem.Message -> ChatMessageItem(
                                        message = item.uiModel,
                                        isStreaming = false,
                                        isTurnSuccessful = item.isTurnSuccessful,
                                        onSourceClick = { ref ->
                                            if (ref.isGraphic) {
                                                handleGraphicSelected(ref.topicId)
                                            } else {
                                                handleNavigateToContent(ref.topicId, ref.sectionId.ifBlank { null })
                                            }
                                        },
                                        onCopyMessage = { content ->
                                            coroutineScope.launch {
                                                clipboard.setPlainText(content)
                                                snackbarHostState.showSnackbar("Copied content to workspace clipboard")
                                            }
                                        },
                                        onEditMessage = { msg ->
                                            editingUserMessageId = msg.id
                                            textFieldState.edit {
                                                replace(0, length, msg.content)
                                                placeCursorAtEnd()
                                            }
                                        },
                                        onRetryTurn = { id -> viewModel.retryTurn(id) }
                                    )

                                    is ChatListItem.ClinicalReferences -> ClinicalReferencesSection(
                                        articles = item.articles,
                                        tables = item.tables,
                                        onNavigateToContent = handleNavigateToContent,
                                        onGraphicSelected = handleGraphicSelected,
                                        modifier = Modifier.padding(horizontal = 20.dp)
                                    )
                                }
                            }

                            if (activeAssistantId != null && !hasAssistantInPersisted) {
                                item(key = activeAssistantId) {
                                    StreamingAssistantMessage(
                                        activeAssistantId = activeAssistantId!!,
                                        viewModel = viewModel,
                                        onSourceClick = { ref ->
                                            if (ref.isGraphic) {
                                                handleGraphicSelected(ref.topicId)
                                            } else {
                                                handleNavigateToContent(ref.topicId, ref.sectionId.ifBlank { null })
                                            }
                                        },
                                        onCopyMessage = { content ->
                                            coroutineScope.launch {
                                                clipboard.setPlainText(content)
                                                snackbarHostState.showSnackbar("Copied content to workspace clipboard")
                                            }
                                        }
                                    )
                                }
                            }

                            item(key = ChatListKeys.BOTTOM_ANCHOR) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(1.dp)
                                )
                            }
                        }
                    }
                }

            // Edit-mode scrim
            AnimatedVisibility(
                visible = editingUserMessageId != null,
                enter = fadeIn(animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            editingUserMessageId = null
                            textFieldState.clearText()
                            focusManager.clearFocus()
                            keyboardController?.hide()
                        }
                        .semantics {
                            onClick(label = "Cancel editing") {
                                editingUserMessageId = null
                                textFieldState.clearText()
                                focusManager.clearFocus()
                                keyboardController?.hide()
                                true
                            }
                        }
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Remains visible during generation whenever the clinician has scrolled up
                ScrollToBottomFAB(
                    visible = scrollController.showScrollToBottomFab && editingUserMessageId == null,
                    onClick = {
                        coroutineScope.launch {
                            scrollController.scrollToBottom(animated = true)
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                ChatInputBar(
                    state = textFieldState,
                    onSendMessage = { text ->
                        val trimmed = text.trim()
                        if (trimmed.isBlank()) return@ChatInputBar
                        val editId = editingUserMessageId
                        val accepted = if (editId != null) {
                            viewModel.submitEditedMessage(editId, trimmed).also { ok ->
                                if (ok) {
                                    editingUserMessageId = null
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            }
                        } else {
                            viewModel.sendMessage(trimmed).also { ok ->
                                if (ok) {
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                }
                            }
                        }
                        if (accepted) {
                            textFieldState.clearText()
                        } else {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "AI generation is currently in progress. Please wait for it to finish.",
                                    withDismissAction = true
                                )
                            }
                        }
                    },
                    onCancel = { viewModel.cancelGeneration() },
                    isGenerating = isGenerating,
                    patientProfile = patientProfile,
                    focusRequester = inputFocusRequester,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { chatInputHeightPx = it.height }
                )
            }
        }
    }

    val currentArticle = activeArticle
    if (currentArticle != null) {
        val articleSheetState = rememberBottomSheetState(
            initialValue = SheetValue.Hidden,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
        val articleStoreOwner = remember(currentArticle.topicId) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
        DisposableEffect(articleStoreOwner) {
            onDispose {
                articleStoreOwner.viewModelStore.clear()
            }
        }

        ModalBottomSheet(
            onDismissRequest = {
                activeArticle = null
                inArticleGraphicId = null
            },
            sheetState = articleSheetState,
            dragHandle = null,
            sheetGesturesEnabled = false,
            modifier = Modifier.fillMaxSize()
        ) {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides articleStoreOwner
            ) {
                ContentScreen(
                    topicId = currentArticle.topicId,
                    sectionId = currentArticle.sectionId,
                    isModal = true,
                    onBack = {
                        activeArticle = null
                        inArticleGraphicId = null
                    },
                    onHome = {
                        activeArticle = null
                        inArticleGraphicId = null
                        onNavigateToHome()
                    },
                    onGraphicSelected = { graphicId ->
                        inArticleGraphicId = graphicId.removePrefix("Graphic-")
                    }
                )
            }

            if (inArticleGraphicId != null) {
                GraphicSheet(
                    graphicId = inArticleGraphicId!!,
                    onDismiss = { inArticleGraphicId = null }
                )
            }
        }
    }

    if (activeGraphicId != null) {
        GraphicSheet(
            graphicId = activeGraphicId!!,
            onDismiss = { activeGraphicId = null }
        )
    }
}

@Composable
private fun ChatEmptyState(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(56.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "ClinRef Clinical Workspace",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Evidence-based decision support powered by UpToDate clinical reference topics and calculators.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
private fun StreamingAssistantMessage(
    activeAssistantId: String,
    viewModel: ChatViewModel,
    onSourceClick: (TopicRef) -> Unit,
    onCopyMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Single atomic StateFlow collection (no torn reads across phase/steps/sources/text)
    val streamingState by viewModel.streamingUiState.collectAsStateWithLifecycle()

    ChatMessageItem(
        message = MessageUiModel(
            id = activeAssistantId,
            role = "assistant",
            content = streamingState.streamingText,
            timestamp = 0L,
            showTimestamp = false
        ),
        isStreaming = true,
        streamingState = streamingState,
        onSourceClick = onSourceClick,
        onCopyMessage = onCopyMessage,
        modifier = modifier
    )
}

/**
 * Workaround for Compose Foundation bug b/519816995:
 * https://issuetracker.google.com/issues/519816995
 * "New Context Menu implementation flickers on item changes, showing 'select all' on disposing"
 *
 * When tapping outside a [androidx.compose.foundation.text.selection.SelectionContainer] to dismiss,
 * SelectionManager clears the selection a frame before ActionMode.finish() runs, causing Android to
 * briefly flash a "Select all"-only floating toolbar (Copy drops out, Select all stays eligible).
 *
 * Compose guarantees all [appendTextContextMenuComponents] run before any [filterTextContextMenuComponents]
 * on every menu build pass, and SelectionManager always emits [TextContextMenuKeys.CopyKey] before
 * [TextContextMenuKeys.SelectAllKey]. We use the append block to reset a per-pass flag and drop
 * [TextContextMenuKeys.SelectAllKey] whenever [TextContextMenuKeys.CopyKey] is absent, yielding an empty
 * menu on the dismiss frame while keeping Copy, Select all, and system actions (Share, Translate,
 * Web search, PROCESS_TEXT) intact while text is selected.
 *
 * Scoped to the message list only (must not wrap ChatInputBar). Safe to remove once upstream
 * b/519816995 is fixed in Compose Foundation.
 */
@Composable
private fun Modifier.suppressTransientSelectAllContextMenu(): Modifier {
    val sawCopyInCurrentPass = remember { booleanArrayOf(false) }
    return this
        .appendTextContextMenuComponents {
            sawCopyInCurrentPass[0] = false
        }
        .filterTextContextMenuComponents { component ->
            when (component.key) {
                TextContextMenuKeys.CopyKey -> {
                    sawCopyInCurrentPass[0] = true
                    true
                }
                TextContextMenuKeys.SelectAllKey -> sawCopyInCurrentPass[0]
                else -> true
            }
        }
}
