package com.clinref.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.ClipData
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.ui.chat.components.ClinicalReferencesSection
import com.clinref.app.ui.chat.components.GeminiChatInput
import com.clinref.app.ui.chat.components.GeminiMessageItem
import com.clinref.app.ui.chat.components.GeminiOrchestrationIndicator
import com.clinref.app.ui.common.ScrollToBottomFAB
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Upper bound for waiting on the lazy list to lay out items that were just added. */
private const val LIST_LAYOUT_TIMEOUT_MS = 1_000L

/** Non-message keys used by the conversation LazyColumn. */
private object ChatListKeys {
    const val LOADING_OLDER = "loading_older_indicator"
    const val ORCHESTRATION = "orchestration_card"
    const val STREAMING = "streaming"
    const val BOTTOM_ANCHOR = "bottom_anchor"
    val NON_MESSAGE = setOf(LOADING_OLDER, ORCHESTRATION, STREAMING, BOTTOM_ANCHOR)
}

/** True when the user is scrolled to (or within [thresholdPx] of) the bottom of the list. */
private fun LazyListState.isNearBottom(thresholdPx: Int = 600): Boolean {
    if (!canScrollForward) return true
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val totalCount = info.totalItemsCount
    if (totalCount <= 0 || visible.isEmpty()) return false
    val lastVisible = visible.last()
    // If the bottom anchor itself is in the visible items, the list is at the bottom
    if (lastVisible.key == ChatListKeys.BOTTOM_ANCHOR) return true
    // If neither the bottom anchor nor the last content item is visible, we are not near bottom
    if (lastVisible.index < totalCount - 2) return false
    val viewportEnd = info.viewportEndOffset - info.afterContentPadding
    val itemBottom = lastVisible.offset + lastVisible.size
    return (itemBottom - viewportEnd) <= thresholdPx
}

/**
 * Scrolls to the absolute bottom of the list.
 *
 * Targets [ChatListKeys.BOTTOM_ANCHOR] at the tail of the LazyColumn. Since the anchor has
 * no content below it, Compose scrolls forward until the bottom content padding is reached,
 * cleanly positioning the entire message and its clinical references in view above the chat input.
 */
private suspend fun LazyListState.scrollToBottom(animated: Boolean = false) {
    val totalCount = layoutInfo.totalItemsCount
    if (totalCount <= 0) return
    val lastIndex = totalCount - 1
    if (animated) {
        animateScrollToItem(lastIndex)
    } else {
        scrollToItem(lastIndex)
    }
}

/**
 * Suspends until the list has laid out at least [minItems] items. Effects run before the measure
 * pass, so [LazyListState.layoutInfo] is stale right after the backing data changes.
 */
private suspend fun LazyListState.awaitItemCount(minItems: Int) {
    withTimeoutOrNull(LIST_LAYOUT_TIMEOUT_MS) {
        snapshotFlow { layoutInfo.totalItemsCount }.first { it >= minItems }
    }
}

/** Anchors on the visible message or references section by key. */
private fun LazyListState.captureScrollPosition(
    preferredAnchorId: String? = null,
    forceAtBottom: Boolean = false
): ChatScrollPosition {
    val visible = layoutInfo.visibleItemsInfo
    val atBottom = forceAtBottom || (visible.isNotEmpty() && (!canScrollForward || isNearBottom()))
    val anchor = preferredAnchorId ?: if (atBottom) {
        visible.mapNotNull { it.key as? String }.lastOrNull { it !in ChatListKeys.NON_MESSAGE }
    } else {
        visible.mapNotNull { it.key as? String }.firstOrNull { it !in ChatListKeys.NON_MESSAGE }
    }

    return ChatScrollPosition(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        anchorMessageId = anchor,
        isAtBottom = atBottom
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateToContent: (String, String?) -> Unit,
    onGraphicSelected: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ChatViewModel = koinViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val chatItems by viewModel.chatItems.collectAsStateWithLifecycle()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val toolProgress by viewModel.toolProgress.collectAsStateWithLifecycle()
    val orchestrationSteps by viewModel.orchestrationSteps.collectAsStateWithLifecycle()
    val currentStatusText by viewModel.currentStatusText.collectAsStateWithLifecycle()
    val liveSources by viewModel.liveDiscoveredSources.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsStateWithLifecycle()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsStateWithLifecycle()
    val patientProfile by viewModel.patientProfile.collectAsStateWithLifecycle()
    val config by viewModel.configuration.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboard.current

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val showScrollToBottom by remember {
        derivedStateOf { listState.canScrollForward }
    }

    val isAtBottom by remember {
        derivedStateOf { !listState.canScrollForward || listState.isNearBottom() }
    }

    var isFollowingStream by remember { mutableStateOf(true) }
    var hasNavigatedAway by remember { mutableStateOf(false) }

    val isGenerating = agentState is StreamingManager.AgentState.ToolCallInProgress ||
        agentState is StreamingManager.AgentState.WaitingForLlm

    val textFieldState = rememberTextFieldState()
    var chatInputHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val chatInputHeightDp = remember(chatInputHeightPx, density) {
        with(density) { chatInputHeightPx.toDp() }
    }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    // Restore scroll position on initial conversation open or return from an article.
    LaunchedEffect(conversationId, chatItems.isNotEmpty()) {
        if (chatItems.isEmpty()) return@LaunchedEffect
        val items = chatItems
        val saved = viewModel.getSavedScrollPosition(conversationId) ?: ChatScrollPosition(
            firstVisibleItemIndex = (items.size - 1).coerceAtLeast(0),
            firstVisibleItemScrollOffset = 0,
            anchorMessageId = items.lastOrNull()?.key,
            isAtBottom = true
        )

        // Wait until LazyColumn has laid out items
        listState.awaitItemCount(items.size + 1)

        if (saved.isAtBottom) {
            listState.scrollToBottom(animated = false)
            isFollowingStream = true
        } else {
            val anchorId = saved.anchorMessageId
            val totalCount = listState.layoutInfo.totalItemsCount
            val maxIndex = (totalCount - 1).coerceAtLeast(0)
            val anchorIndex = anchorId
                ?.let { id ->
                    val exactIndex = items.indexOfFirst { it.key == id }
                    if (exactIndex >= 0) exactIndex
                    else {
                        val cleanId = id.removePrefix("refs_")
                        items.indexOfFirst { it.key == cleanId }
                    }
                }
                ?.takeIf { it >= 0 }
                ?.let { if (isLoadingOlder) it + 1 else it }

            val targetIndex = (anchorIndex ?: saved.firstVisibleItemIndex).coerceIn(0, maxIndex)
            val targetOffset = if (anchorId?.startsWith("refs_") == true) 0 else saved.firstVisibleItemScrollOffset

            listState.scrollToItem(targetIndex, targetOffset)

            if (targetOffset > 0) {
                withTimeoutOrNull(500) {
                    snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }?.size ?: 0
                    }.first { it >= targetOffset }
                    listState.scrollToItem(targetIndex, targetOffset)
                }
            }
        }
    }

    // Scroll to bottom when the user sends a message or when generation completes.
    LaunchedEffect(Unit) {
        viewModel.scrollEvents.collect { event ->
            when (event) {
                is ChatScrollEvent.ScrollToBottom -> {
                    isFollowingStream = true
                    event.awaitMessageId?.let { id ->
                        withTimeoutOrNull(LIST_LAYOUT_TIMEOUT_MS) {
                            snapshotFlow {
                                when (val last = chatItems.lastOrNull()) {
                                    is ChatListItem.Message -> last.uiModel.id == id
                                    is ChatListItem.ClinicalReferences -> last.messageId == id
                                    null -> false
                                }
                            }.first { it }
                            snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > chatItems.size }
                        }
                    }
                    listState.scrollToBottom(animated = false)
                }
            }
        }
    }

    // Stream auto-scroll: keep list pinned to bottom while user is following the stream
    LaunchedEffect(streamingText, orchestrationSteps.size, isGenerating) {
        if (isFollowingStream && (isGenerating || streamingText.isNotEmpty() || orchestrationSteps.isNotEmpty())) {
            listState.scrollToBottom(animated = false)
        }
    }

    // Pause auto-follow when user manually drags/scrolls up away from the bottom
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && listState.canScrollForward) {
            isFollowingStream = false
        }
    }

    // Prepend pagination: fetch older messages when scrolled near top
    LaunchedEffect(listState.firstVisibleItemIndex, hasMoreMessages) {
        if (listState.firstVisibleItemIndex <= 2 && hasMoreMessages && !isLoadingOlder) {
            viewModel.loadOlderMessages()
        }
    }

    // Preserve scroll position when leaving composition (navigating to an article citation,
    // switching tabs).
    DisposableEffect(conversationId) {
        onDispose {
            if (!hasNavigatedAway && listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                viewModel.saveScrollPosition(conversationId, listState.captureScrollPosition())
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            MediumFlexibleTopAppBar(
                title = {
                    Text(
                        text = "Clinical Workspace",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold
                        )
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
                    IconButton(
                        onClick = {
                            hasNavigatedAway = true
                            viewModel.saveScrollPosition(conversationId, listState.captureScrollPosition())
                            onBack()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (messages.isEmpty() && !isLoadingOlder) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "ClinRef AI",
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Clinical support model ready",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 10.dp,
                        bottom = padding.calculateBottomPadding() + (if (chatInputHeightDp > 0.dp) chatInputHeightDp + 16.dp else 180.dp)
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
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    items(
                        items = chatItems,
                        key = { it.key }
                    ) { item ->
                        when (item) {
                            is ChatListItem.Message -> GeminiMessageItem(
                                message = item.uiModel,
                                onCopyMessage = { content ->
                                    coroutineScope.launch {
                                        val clipData = ClipData.newPlainText("chat_message", content)
                                        clipboard.setClipEntry(clipData.toClipEntry())
                                        snackbarHostState.showSnackbar("Copied content to workspace clipboard")
                                    }
                                },
                                onNavigateToContent = { topicId, sectionId ->
                                    hasNavigatedAway = true
                                    viewModel.saveScrollPosition(
                                        conversationId,
                                        listState.captureScrollPosition(
                                            preferredAnchorId = item.key
                                        )
                                    )
                                    onNavigateToContent(topicId, sectionId)
                                },
                                onGraphicSelected = { graphicId ->
                                    hasNavigatedAway = true
                                    viewModel.saveScrollPosition(
                                        conversationId,
                                        listState.captureScrollPosition(
                                            preferredAnchorId = item.key
                                        )
                                    )
                                    onGraphicSelected(graphicId)
                                }
                            )
                            is ChatListItem.ClinicalReferences -> ClinicalReferencesSection(
                                articles = item.articles,
                                tables = item.tables,
                                onNavigateToContent = { topicId, sectionId ->
                                    val isTail = chatItems.lastOrNull()?.key == item.key
                                    hasNavigatedAway = true
                                    viewModel.saveScrollPosition(
                                        conversationId,
                                        listState.captureScrollPosition(
                                            preferredAnchorId = item.key,
                                            forceAtBottom = isTail
                                        )
                                    )
                                    onNavigateToContent(topicId, sectionId)
                                },
                                onGraphicSelected = { graphicId ->
                                    val isTail = chatItems.lastOrNull()?.key == item.key
                                    hasNavigatedAway = true
                                    viewModel.saveScrollPosition(
                                        conversationId,
                                        listState.captureScrollPosition(
                                            preferredAnchorId = item.key,
                                            forceAtBottom = isTail
                                        )
                                    )
                                    onGraphicSelected(graphicId)
                                },
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                    }

                    if (isGenerating) {
                        item(key = ChatListKeys.ORCHESTRATION) {
                            GeminiOrchestrationIndicator(
                                steps = orchestrationSteps,
                                currentStatusText = currentStatusText,
                                liveSources = liveSources
                            )
                        }
                    }

                    if (streamingText.isNotEmpty()) {
                        item(key = ChatListKeys.STREAMING) {
                            GeminiMessageItem(
                                message = MessageUiModel(
                                    id = "streaming",
                                    role = "assistant",
                                    content = streamingText,
                                    timestamp = System.currentTimeMillis(),
                                    showTimestamp = false
                                ),
                                onCopyMessage = { content ->
                                    coroutineScope.launch {
                                        val clipData = ClipData.newPlainText("chat_message", content)
                                        clipboard.setClipEntry(clipData.toClipEntry())
                                        snackbarHostState.showSnackbar("Copied content to workspace clipboard")
                                    }
                                },
                                onNavigateToContent = { topicId, sectionId ->
                                    hasNavigatedAway = true
                                    viewModel.saveScrollPosition(
                                        conversationId,
                                        listState.captureScrollPosition(
                                            preferredAnchorId = "streaming"
                                        )
                                    )
                                    onNavigateToContent(topicId, sectionId)
                                },
                                onGraphicSelected = { graphicId ->
                                    hasNavigatedAway = true
                                    viewModel.saveScrollPosition(
                                        conversationId,
                                        listState.captureScrollPosition(
                                            preferredAnchorId = "streaming"
                                        )
                                    )
                                    onGraphicSelected(graphicId)
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

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ScrollToBottomFAB(
                    visible = showScrollToBottom,
                    onClick = {
                        isFollowingStream = true
                        coroutineScope.launch {
                            listState.scrollToBottom(animated = true)
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GeminiChatInput(
                    state = textFieldState,
                    onSendMessage = { text ->
                        isFollowingStream = true
                        viewModel.sendMessage(text)
                        textFieldState.clearText()
                    },
                    onCancel = { viewModel.cancelGeneration() },
                    isGenerating = isGenerating,
                    patientProfile = patientProfile,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { chatInputHeightPx = it.height }
                )
            }
        }
    }
}
