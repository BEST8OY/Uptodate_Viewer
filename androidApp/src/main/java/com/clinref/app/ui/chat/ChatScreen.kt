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
    val NON_MESSAGE = setOf(LOADING_OLDER, ORCHESTRATION, STREAMING)
}

/** True when the last visible item is (nearly) the tail of the list. */
private fun LazyListState.isNearBottom(): Boolean {
    val info = layoutInfo
    val lastVisible = info.visibleItemsInfo.lastOrNull() ?: return true
    return lastVisible.index >= info.totalItemsCount - 2
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

/** Anchors on the first visible message by key, which is stable across prepends. */
private fun LazyListState.captureScrollPosition() = ChatScrollPosition(
    firstVisibleItemIndex = firstVisibleItemIndex,
    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
    anchorMessageId = (layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String)
        ?.takeIf { it !in ChatListKeys.NON_MESSAGE },
    isAtBottom = isNearBottom()
)

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

    val isAtBottom by remember { derivedStateOf { listState.isNearBottom() } }

    val isGenerating = agentState is StreamingManager.AgentState.ToolCallInProgress ||
        agentState is StreamingManager.AgentState.WaitingForLlm

    val textFieldState = rememberTextFieldState()
    var chatInputHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val chatInputHeightDp = remember(chatInputHeightPx, density) {
        with(density) { chatInputHeightPx.toDp() }
    }

    var hasRestoredScrollPosition by remember(conversationId) { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    // Restore scroll position on initial conversation open or return from an article.
    LaunchedEffect(conversationId, chatItems.isNotEmpty()) {
        if (chatItems.isEmpty() || hasRestoredScrollPosition) return@LaunchedEffect
        val items = chatItems
        listState.awaitItemCount(items.size)
        val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
        val saved = viewModel.getSavedScrollPosition(conversationId)
        if (saved == null || saved.isAtBottom) {
            listState.scrollToItem(lastIndex)
        } else {
            val anchorIndex = saved.anchorMessageId
                ?.let { id -> items.indexOfFirst { it is ChatListItem.Message && it.uiModel.id == id } }
                ?.takeIf { it >= 0 }
            val index = (anchorIndex ?: saved.firstVisibleItemIndex).coerceIn(0, lastIndex)
            listState.scrollToItem(index, saved.firstVisibleItemScrollOffset)
        }
        hasRestoredScrollPosition = true
    }

    // Scroll to bottom when the user sends a message.
    LaunchedEffect(Unit) {
        viewModel.scrollEvents.collect { event ->
            when (event) {
                is ChatScrollEvent.ScrollToBottom -> {
                    // The event is emitted before chatItems and the list layout catch up.
                    event.awaitMessageId?.let { id ->
                        withTimeoutOrNull(LIST_LAYOUT_TIMEOUT_MS) {
                            snapshotFlow {
                                (chatItems.lastOrNull() as? ChatListItem.Message)?.uiModel?.id
                            }.first { it == id }
                        }
                    }
                    listState.awaitItemCount(chatItems.size)
                    val lastIndex = listState.layoutInfo.totalItemsCount - 1
                    if (lastIndex >= 0) {
                        if (event.animated) {
                            listState.animateScrollToItem(lastIndex)
                        } else {
                            listState.scrollToItem(lastIndex)
                        }
                    }
                }
            }
        }
    }

    // Stream auto-scroll: follow stream ONLY if user is already at the bottom
    LaunchedEffect(streamingText, orchestrationSteps.size) {
        if (streamingText.isNotEmpty() || orchestrationSteps.isNotEmpty()) {
            if (isAtBottom) {
                val count = listState.layoutInfo.totalItemsCount
                if (count > 0) {
                    listState.scrollToItem(count - 1)
                }
            }
        }
    }

    // Prepend pagination: fetch older messages when scrolled near top
    LaunchedEffect(listState.firstVisibleItemIndex, hasMoreMessages, hasRestoredScrollPosition) {
        if (hasRestoredScrollPosition && listState.firstVisibleItemIndex <= 2 && hasMoreMessages && !isLoadingOlder) {
            viewModel.loadOlderMessages()
        }
    }

    // Preserve scroll position when leaving composition (navigating to an article citation,
    // switching tabs). A list that was never restored must not overwrite the saved position.
    DisposableEffect(conversationId) {
        onDispose {
            if (hasRestoredScrollPosition) {
                viewModel.saveScrollPosition(listState.captureScrollPosition())
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
                    IconButton(onClick = onBack) {
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
                        key = {
                            when (it) {
                                is ChatListItem.Message -> it.uiModel.id
                            }
                        }
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
                                onNavigateToContent = onNavigateToContent,
                                onGraphicSelected = onGraphicSelected
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
                                onNavigateToContent = onNavigateToContent,
                                onGraphicSelected = onGraphicSelected
                            )
                        }
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
                        coroutineScope.launch {
                            val totalCount = listState.layoutInfo.totalItemsCount
                            if (totalCount > 0) {
                                listState.animateScrollToItem(totalCount - 1)
                                scrollBehavior.state.heightOffset = scrollBehavior.state.heightOffsetLimit
                            }
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GeminiChatInput(
                    state = textFieldState,
                    onSendMessage = { text ->
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
