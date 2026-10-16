package com.clinref.app.ui.chat

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.ui.chat.components.ClinicalReferencesSection
import com.clinref.app.ui.chat.components.GeminiChatInput
import com.clinref.app.ui.chat.components.GeminiMessageItem
import com.clinref.app.ui.chat.components.GeminiOrchestrationIndicator
import com.clinref.app.ui.common.ScrollToBottomFAB
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Non-message keys used by the conversation LazyColumn. */
private object ChatListKeys {
    const val LOADING_OLDER = "loading_older_indicator"
    const val ORCHESTRATION = "orchestration_card"
    const val STREAMING = "streaming"
    const val BOTTOM_ANCHOR = "bottom_anchor"
}

/**
 * Accurately captures the visual reading position in the LazyColumn.
 */
private fun LazyListState.captureExactScrollPosition(): ChatScrollPosition {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) {
        return ChatScrollPosition(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            anchorMessageId = null,
            isAtBottom = true
        )
    }
    val atBottom = !canScrollForward
    val firstVisibleKey = visible.firstOrNull()?.key as? String

    return ChatScrollPosition(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        anchorMessageId = firstVisibleKey,
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
    viewModel: ChatViewModel = org.koin.compose.viewmodel.koinViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val chatItems by viewModel.chatItems.collectAsStateWithLifecycle()
    val activeAssistantId by viewModel.activeAssistantMessageId.collectAsStateWithLifecycle()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
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
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var isFollowingBottom by rememberSaveable(conversationId) { mutableStateOf(true) }
    var hasRestoredScroll by rememberSaveable(conversationId) { mutableStateOf(false) }

    val isGenerating = activeAssistantId != null ||
        agentState is StreamingManager.AgentState.ToolCallInProgress ||
        agentState is StreamingManager.AgentState.WaitingForLlm

    val textFieldState = rememberTextFieldState()
    var chatInputHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val chatInputHeightDp = remember(chatInputHeightPx, density) {
        with(density) { chatInputHeightPx.toDp() }
    }

    // Intercept back navigation (gesture or button) to reliably snapshot the scroll offset
    BackHandler {
        viewModel.saveScrollPosition(conversationId, listState.captureExactScrollPosition())
        onBack()
    }

    val handleNavigateToContent: (String, String?) -> Unit = { topicId, sectionId ->
        viewModel.saveScrollPosition(conversationId, listState.captureExactScrollPosition())
        onNavigateToContent(topicId, sectionId)
    }

    val handleGraphicSelected: (String) -> Unit = { graphicId ->
        viewModel.saveScrollPosition(conversationId, listState.captureExactScrollPosition())
        onGraphicSelected(graphicId)
    }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    // Restore scroll position when conversation items are available
    LaunchedEffect(conversationId, chatItems.isNotEmpty()) {
        if (chatItems.isEmpty() || hasRestoredScroll) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }

        val saved = viewModel.getSavedScrollPosition(conversationId)
        if (saved == null || saved.isAtBottom) {
            val totalCount = listState.layoutInfo.totalItemsCount
            if (totalCount > 0) {
                listState.scrollToItem(totalCount - 1)
            }
            isFollowingBottom = true
        } else {
            val items = chatItems
            val targetIndex = saved.anchorMessageId?.let { key ->
                val idx = items.indexOfFirst { it.key == key }
                if (idx >= 0) idx else null
            } ?: saved.firstVisibleItemIndex.coerceIn(0, (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))

            listState.scrollToItem(targetIndex, saved.firstVisibleItemScrollOffset)
            isFollowingBottom = false
        }
        hasRestoredScroll = true
    }

    // Track user drag vs programmatic scroll to prevent fighting user scrolling
    LaunchedEffect(isUserDragging) {
        if (isUserDragging && listState.canScrollForward) {
            isFollowingBottom = false
        }
    }

    LaunchedEffect(listState.canScrollForward) {
        if (!listState.canScrollForward) {
            isFollowingBottom = true
        }
    }

    // ViewModel scroll events (e.g. user sends a message or completed assistant response)
    LaunchedEffect(Unit) {
        viewModel.scrollEvents.collect { event ->
            when (event) {
                is ChatScrollEvent.ScrollToBottom -> {
                    isFollowingBottom = true
                    snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
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

    // Real-time stream auto-scroll: continuously tracks latest tokens without fighting manual drag
    LaunchedEffect(streamingText, orchestrationSteps.size, isGenerating) {
        if (isFollowingBottom && (isGenerating || streamingText.isNotEmpty() || orchestrationSteps.isNotEmpty())) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) {
                listState.scrollToItem(count - 1)
            }
        }
    }

    // Keyboard / IME adjustment: keep bottom message in view when soft keyboard opens
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom > 0) {
        if (imeBottom > 0 && isFollowingBottom) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) {
                listState.scrollToItem(count - 1)
            }
        }
    }

    // Prepend pagination: load older messages when scrolled near the top
    LaunchedEffect(listState.firstVisibleItemIndex, hasMoreMessages) {
        if (listState.firstVisibleItemIndex <= 1 && hasMoreMessages && !isLoadingOlder) {
            viewModel.loadOlderMessages()
        }
    }

    // Save scroll position when leaving composition (navigating to an article or conversation list)
    DisposableEffect(conversationId) {
        onDispose {
            if (listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                viewModel.saveScrollPosition(conversationId, listState.captureExactScrollPosition())
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
                            viewModel.saveScrollPosition(conversationId, listState.captureExactScrollPosition())
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
                ChatEmptyState(
                    onSuggestionSelected = { query ->
                        isFollowingBottom = true
                        viewModel.sendMessage(query)
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + (if (chatInputHeightDp > 0.dp) chatInputHeightDp + 16.dp else 160.dp)
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

                    if (isGenerating) {
                        item(key = activeAssistantId?.let { "orchestration_$it" } ?: ChatListKeys.ORCHESTRATION) {
                            GeminiOrchestrationIndicator(
                                steps = orchestrationSteps,
                                currentStatusText = currentStatusText,
                                liveSources = liveSources
                            )
                        }
                    }

                    if (streamingText.isNotEmpty()) {
                        item(key = activeAssistantId ?: ChatListKeys.STREAMING) {
                            GeminiMessageItem(
                                message = MessageUiModel(
                                    id = activeAssistantId ?: "streaming",
                                    role = "assistant",
                                    content = streamingText,
                                    timestamp = System.currentTimeMillis(),
                                    showTimestamp = false
                                ),
                                isStreaming = true,
                                onCopyMessage = { content ->
                                    coroutineScope.launch {
                                        val clipData = ClipData.newPlainText("chat_message", content)
                                        clipboard.setClipEntry(clipData.toClipEntry())
                                        snackbarHostState.showSnackbar("Copied content to workspace clipboard")
                                    }
                                },
                                onNavigateToContent = handleNavigateToContent,
                                onGraphicSelected = handleGraphicSelected
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
                    visible = listState.canScrollForward,
                    onClick = {
                        isFollowingBottom = true
                        coroutineScope.launch {
                            val count = listState.layoutInfo.totalItemsCount
                            if (count > 0) {
                                listState.animateScrollToItem(count - 1)
                            }
                        }
                    },
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                GeminiChatInput(
                    state = textFieldState,
                    onSendMessage = { text ->
                        isFollowingBottom = true
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

/**
 * Modern, clinical welcome view for empty chat conversations.
 */
@Composable
private fun ChatEmptyState(
    onSuggestionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
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

        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "Suggested Clinical Queries",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(12.dp))

        val suggestions = listOf(
            SuggestionItem("First-line asthma management in adults", Icons.Default.MedicalServices),
            SuggestionItem("Anticoagulation dosing & duration for acute DVT", Icons.Default.Medication),
            SuggestionItem("Blood pressure goals in diabetic patients", Icons.Default.CheckCircle),
            SuggestionItem("Empiric antibiotic therapy for community-acquired pneumonia", Icons.Default.Science)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { item ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSuggestionSelected(item.query) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = item.query,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

private data class SuggestionItem(val query: String, val icon: ImageVector)
