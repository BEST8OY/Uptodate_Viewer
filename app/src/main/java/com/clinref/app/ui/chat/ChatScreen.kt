package com.clinref.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.ui.chat.components.DateSeparator
import com.clinref.app.ui.chat.components.DisclaimerBanner
import com.clinref.app.ui.chat.components.EmptyState
import com.clinref.app.ui.chat.components.GeminiOrchestrationIndicator
import com.clinref.app.ui.chat.components.ModernChatInput
import com.clinref.app.ui.chat.components.ModernMessageItem
import com.clinref.app.ui.chat.components.ScrollToBottomFAB
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
    val config by viewModel.configuration.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val showScrollToBottom by remember {
        derivedStateOf { listState.canScrollForward }
    }

    val isGenerating = agentState is StreamingManager.AgentState.ToolCallInProgress ||
        agentState is StreamingManager.AgentState.WaitingForLlm

    var activeInputText by remember { mutableStateOf("") }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    LaunchedEffect(chatItems.size) {
        if (chatItems.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(chatItems.size - 1)
            }
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, hasMoreMessages) {
        if (listState.firstVisibleItemIndex <= 3 && hasMoreMessages && !isLoadingOlder) {
            viewModel.loadOlderMessages()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Clinical Assistant",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        if (config.isConfigured) {
                            Text(
                                text = "Active Core: ${config.requestsPerMinute} RPM safe",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Workspaces"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(imageVector = Icons.Default.Tune, contentDescription = "Engine settings")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.mediumTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        bottomBar = {
            DisclaimerBanner()
        },
        floatingActionButton = {
            ScrollToBottomFAB(
                visible = showScrollToBottom,
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(chatItems.size - 1)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (messages.isEmpty() && !isLoadingOlder) {
                    EmptyState(
                        templates = viewModel.clinicalTemplates,
                        onSuggestionClick = { selectedSuggestion ->
                            activeInputText = selectedSuggestion
                        }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)
                    ) {
                        if (isLoadingOlder) {
                            item {
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
                                is ChatListItem.Message -> ModernMessageItem(
                                    message = item.uiModel,
                                    onCopyMessage = { content ->
                                        viewModel.copyMessageToClipboard(context, content)
                                    },
                                    onNavigateToContent = onNavigateToContent
                                )
                            }
                        }

                        if (toolProgress != null) {
                            item {
                                GeminiOrchestrationIndicator(progress = toolProgress!!)
                            }
                        }

                        if (isGenerating && toolProgress == null) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    ContainedLoadingIndicator(
                                        modifier = Modifier.size(42.dp),
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        indicatorColor = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                tonalElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                ModernChatInput(
                    textValue = activeInputText,
                    onValueChange = { activeInputText = it },
                    onSendMessage = {
                        viewModel.sendMessage(it)
                        activeInputText = ""
                    },
                    onCancel = { viewModel.cancelGeneration() },
                    isGenerating = isGenerating,
                    patientProfile = patientProfile,
                    clinicalTemplates = viewModel.clinicalTemplates
                )
            }
        }
    }
}
