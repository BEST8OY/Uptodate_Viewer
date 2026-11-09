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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.SheetValue
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.clinref.app.ui.common.setPlainText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.chat.components.ChatInputBar
import com.clinref.app.ui.chat.components.ChatMessageItem
import com.clinref.app.ui.chat.components.ClinicalReferencesSection
import com.clinref.app.ui.common.BackHandler
import com.clinref.app.ui.common.ScrollToBottomFAB
import com.clinref.app.ui.content.ContentScreen
import com.clinref.app.ui.content.GraphicSheet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

private object ChatListKeys {
    const val LOADING_OLDER = "loading_older_indicator"
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

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    LaunchedEffect(Unit) {
        viewModel.scrollEvents.collect { event ->
            when (event) {
                is ChatScrollEvent.ScrollToBottom -> {
                    if (event.awaitMessageId != null) {
                        snapshotFlow { chatItems }.first { items ->
                            items.any { it is ChatListItem.Message && it.uiModel.id == event.awaitMessageId }
                        }
                    } else {
                        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
                    }
                    val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                    if (event.animated) {
                        listState.animateScrollToItem(lastIndex)
                    } else {
                        listState.scrollToItem(lastIndex)
                    }
                }
            }
        }
    }

    val imeBottom = WindowInsets.ime.getBottom(density)
    val isImeVisible = imeBottom > 0
    var wasImeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(isImeVisible) {
        if (wasImeVisible && !isImeVisible) {
            focusManager.clearFocus()
            if (editingUserMessageId != null) {
                editingUserMessageId = null
                textFieldState.clearText()
            }
        }
        wasImeVisible = isImeVisible
    }

    LaunchedEffect(isImeVisible, isOverlayActive) {
        if (!isOverlayActive && isImeVisible && !listState.canScrollForward) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) {
                listState.scrollToItem(count - 1)
            }
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    LaunchedEffect(isOverlayActive) {
        if (isOverlayActive) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }

    LaunchedEffect(listState, hasMoreMessages) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index <= 1 && hasMoreMessages && !isLoadingOlder) {
                    viewModel.loadOlderMessages()
                }
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
            .nestedScroll(scrollBehavior.nestedScrollConnection),
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
                        text = "${config.provider.displayName} \u00b7 ${config.model.ifBlank { "Default" }}",
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
            } else if (chatItems.isEmpty() && !isLoadingOlder) {
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
                        activeAssistantId != null && chatItems.any { it is ChatListItem.Message && it.uiModel.id == activeAssistantId }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .widthIn(max = AdaptiveTokens.MaxCatalogListWidth)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = if (chatInputHeightDp > 0.dp) chatInputHeightDp + 16.dp else 160.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isLoadingOlder) {
                            item(key = ChatListKeys.LOADING_OLDER) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    LoadingIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
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
                                    isStreaming = item.uiModel.id == activeAssistantId,
                                    isTurnSuccessful = item.isTurnSuccessful,
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
                                    onRetryTurn = { id -> viewModel.retryTurn(id) },
                                    onNavigateToContent = handleNavigateToContent,
                                    onGraphicSelected = handleGraphicSelected
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
                                    onSourceClick = { ref -> handleNavigateToContent(ref.topicId, ref.sectionId.ifBlank { null }) },
                                    onCopyMessage = { content ->
                                        coroutineScope.launch {
                                            clipboard.setPlainText(content)
                                            snackbarHostState.showSnackbar("Copied content to workspace clipboard")
                                        }
                                    },
                                    onNavigateToContent = handleNavigateToContent,
                                    onGraphicSelected = handleGraphicSelected
                                )
                            }
                        }
                    }
                }
            }

            // Background overlay shadowing the chat messages when in editing mode
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
                ScrollToBottomFAB(
                    visible = listState.canScrollForward && !isGenerating && editingUserMessageId == null,
                    onClick = {
                        coroutineScope.launch {
                            val count = listState.layoutInfo.totalItemsCount
                            if (count > 0) {
                                listState.animateScrollToItem(count - 1)
                            }
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                ChatInputBar(
                    state = textFieldState,
                    onSendMessage = { text ->
                        val trimmed = text.trim()
                        if (trimmed.isBlank()) {
                            return@ChatInputBar
                        }
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
    onSourceClick: (SafetyValidator.TopicRef) -> Unit,
    onCopyMessage: (String) -> Unit,
    onNavigateToContent: (String, String?) -> Unit,
    onGraphicSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val orchestrationPhase by viewModel.orchestrationPhase.collectAsStateWithLifecycle()
    val currentStatusText by viewModel.currentStatusText.collectAsStateWithLifecycle()
    val liveSources by viewModel.liveDiscoveredSources.collectAsStateWithLifecycle()
    val sectionsReadCount by viewModel.sectionsReadCount.collectAsStateWithLifecycle()
    val isDraft by viewModel.isDraft.collectAsStateWithLifecycle()
    val isRevised by viewModel.isRevised.collectAsStateWithLifecycle()

    ChatMessageItem(
        message = MessageUiModel(
            id = activeAssistantId,
            role = "assistant",
            content = streamingText,
            timestamp = 0L,
            showTimestamp = false
        ),
        isStreaming = true,
        orchestrationPhase = orchestrationPhase,
        currentStatusText = currentStatusText,
        liveSources = liveSources,
        sectionsReadCount = sectionsReadCount,
        isDraft = isDraft,
        isRevised = isRevised,
        onSourceClick = onSourceClick,
        onCopyMessage = onCopyMessage,
        onNavigateToContent = onNavigateToContent,
        onGraphicSelected = onGraphicSelected,
        modifier = modifier
    )
}


