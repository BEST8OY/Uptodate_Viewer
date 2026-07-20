package com.clinref.app.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.ui.chat.components.ChatInput
import com.clinref.app.ui.chat.components.ChatLoadingPlaceholder
import com.clinref.app.ui.chat.components.DateSeparator
import com.clinref.app.ui.chat.components.EmptyState
import com.clinref.app.ui.chat.components.MessageBubble
import com.clinref.app.ui.chat.components.ScrollToBottomFAB
import com.clinref.app.ui.chat.components.ToolProgressIndicator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateToContent: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val chatItems by viewModel.chatItems.collectAsStateWithLifecycle()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val toolProgress by viewModel.toolProgress.collectAsStateWithLifecycle()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsStateWithLifecycle()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsStateWithLifecycle()
    val patientProfile by viewModel.patientProfile.collectAsStateWithLifecycle()
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Composer text is hoisted to screen level so the empty-state suggestions can
    // populate it directly, rather than auto-sending on tap.
    var inputText by rememberSaveable(conversationId) { mutableStateOf("") }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val showScrollToBottom by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val isScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }

    val isGenerating = agentState is StreamingManager.AgentState.ToolCallInProgress ||
        agentState is StreamingManager.AgentState.WaitingForLlm

    // One label drives one activity row, whatever the underlying state — a specific
    // tool description while a tool call is running, a generic "Thinking…" while the
    // model is between tool calls. See ToolProgressIndicator for why this is unified.
    val activityLabel: String? = when (agentState) {
        is StreamingManager.AgentState.ToolCallInProgress -> toolProgress?.description ?: "Working\u2026"
        is StreamingManager.AgentState.WaitingForLlm -> "Thinking\u2026"
        else -> null
    }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem((chatItems.size - 1).coerceAtLeast(0))
            }
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, hasMoreMessages) {
        if (listState.firstVisibleItemIndex <= 2 && hasMoreMessages && !isLoadingOlder) {
            viewModel.loadOlderMessages()
        }
    }

    Scaffold(
        topBar = {
            ChatTopBar(
                onBack = onBack,
                subtitle = configuration.model.ifBlank { configuration.provider.displayName }
                    .takeIf { configuration.isConfigured },
                elevated = isScrolled
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                when {
                    messages.isEmpty() && !isLoadingOlder -> {
                        EmptyState(
                            onSuggestionClick = { inputText = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    messages.isEmpty() && isLoadingOlder -> {
                        ChatLoadingPlaceholder(modifier = Modifier.fillMaxSize())
                    }

                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 12.dp)
                        ) {
                            if (isLoadingOlder) {
                                item(key = "loading-older") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ContainedLoadingIndicator()
                                    }
                                }
                            }

                            items(
                                items = chatItems,
                                key = {
                                    when (it) {
                                        is ChatListItem.DateSeparator -> "date-${it.label}"
                                        is ChatListItem.Message -> it.uiModel.id
                                    }
                                }
                            ) { item ->
                                when (item) {
                                    is ChatListItem.DateSeparator -> DateSeparator(label = item.label)
                                    is ChatListItem.Message -> MessageBubble(
                                        message = item.uiModel,
                                        onCopyMessage = { content -> viewModel.copyMessageToClipboard(context, content) },
                                        onCitationClick = onNavigateToContent
                                    )
                                }
                            }

                            if (activityLabel != null) {
                                item(key = "activity-indicator") {
                                    ToolProgressIndicator(label = activityLabel)
                                }
                            }
                        }
                    }
                }

                ScrollToBottomFAB(
                    visible = showScrollToBottom,
                    onClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem((chatItems.size - 1).coerceAtLeast(0))
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                )
            }

            ChatInput(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    val toSend = inputText
                    if (toSend.isNotBlank()) {
                        inputText = ""
                        viewModel.sendMessage(toSend)
                    }
                },
                onCancel = { viewModel.cancelGeneration() },
                isGenerating = isGenerating,
                patientProfile = patientProfile
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    onBack: () -> Unit,
    subtitle: String?,
    elevated: Boolean
) {
    TopAppBar(
        title = {
            Column {
                Text("ClinRef AI", style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = if (elevated) MaterialTheme.colorScheme.surfaceContainer
            else MaterialTheme.colorScheme.surface
        )
    )
}
