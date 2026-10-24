package com.clinref.app.ui.chat

import android.content.ClipData
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material3.minimumInteractiveComponentSize
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
import com.clinref.app.ui.chat.components.ChatInputBar
import com.clinref.app.ui.chat.components.ChatMessageItem
import com.clinref.app.ui.chat.components.ChatOrchestrationIndicator
import com.clinref.app.ui.common.ScrollToBottomFAB
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.listSaver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.clinref.app.ui.content.ContentScreen
import com.clinref.app.ui.content.GraphicSheet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Non-message keys used by the conversation LazyColumn. */
private object ChatListKeys {
    const val LOADING_OLDER = "loading_older_indicator"
    const val ORCHESTRATION = "orchestration_card"
    const val STREAMING = "streaming"
    const val BOTTOM_ANCHOR = "bottom_anchor"
}

data class ArticleSheetRequest(
    val topicId: String,
    val sectionId: String? = null
)

// Operates on the nullable value so the sheet's open/close state
// (`activeArticle = null`) can be delegated from rememberSaveable; a closed
// sheet saves as an empty list and restores back to null.
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
    onNavigateToSettings: (() -> Unit)? = null,
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
    var isFollowingBottom by remember { mutableStateOf(false) }

    val isGenerating = activeAssistantId != null ||
        agentState is StreamingManager.AgentState.ToolCallInProgress ||
        agentState is StreamingManager.AgentState.WaitingForLlm

    val textFieldState = rememberTextFieldState()
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

    val handleNavigateToContent: (String, String?) -> Unit = { topicId, sectionId ->
        activeArticle = ArticleSheetRequest(topicId, sectionId)
    }
    val handleGraphicSelected: (String) -> Unit = { graphicId ->
        activeGraphicId = graphicId.removePrefix("Graphic-")
    }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    // Pause auto-follow if the user manually drags up
    LaunchedEffect(isUserDragging) {
        if (isUserDragging && listState.canScrollForward) {
            isFollowingBottom = false
        }
    }

    // Resume auto-follow when the user scrolls back to the bottom
    LaunchedEffect(listState.canScrollForward) {
        if (!listState.canScrollForward) {
            isFollowingBottom = true
        }
    }

    // Scroll events emitted by ViewModel (e.g. user sends a message)
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

    // Auto-scroll stream deltas when following the bottom during active generation
    LaunchedEffect(streamingText, isGenerating) {
        if (isFollowingBottom && (isGenerating || streamingText.isNotEmpty())) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) {
                listState.scrollToItem(count - 1)
            }
        }
    }

    // Keep bottom turn visible when soft keyboard opens (track animation height directly)
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && isFollowingBottom) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) {
                listState.scrollToItem(count - 1)
            }
        }
    }

    // Prepend pagination: load older messages when scrolled near top
    LaunchedEffect(listState.firstVisibleItemIndex, hasMoreMessages) {
        if (listState.firstVisibleItemIndex <= 1 && hasMoreMessages && !isLoadingOlder) {
            viewModel.loadOlderMessages()
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
                actions = {
                    if (onNavigateToSettings != null) {
                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "AI Orchestration Settings",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
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
                            is ChatListItem.Message -> ChatMessageItem(
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
                            ChatOrchestrationIndicator(
                                steps = orchestrationSteps,
                                currentStatusText = currentStatusText,
                                liveSources = liveSources
                            )
                        }
                    }

                    if (streamingText.isNotEmpty()) {
                        item(key = activeAssistantId ?: ChatListKeys.STREAMING) {
                            ChatMessageItem(
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

                ChatInputBar(
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

    val currentArticle = activeArticle
    if (currentArticle != null) {
        val articleSheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true
        )
        val articleStoreOwner = remember(currentArticle) {
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

/**
 * Modern, clinical welcome view for empty chat conversations.
 */
@Composable
private fun ChatEmptyState(
    onSuggestionSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
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
            SuggestionItem("First-line asthma management in adults", Icons.Default.MedicalServices, "Pulmonology"),
            SuggestionItem("Anticoagulation dosing & duration for acute DVT", Icons.Default.Medication, "Hematology"),
            SuggestionItem("Blood pressure goals in diabetic patients", Icons.Default.CheckCircle, "Cardiology"),
            SuggestionItem("Empiric antibiotic therapy for community-acquired pneumonia", Icons.Default.Science, "Infectious Disease")
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
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.domain.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
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
}

private data class SuggestionItem(val query: String, val icon: ImageVector, val domain: String)
