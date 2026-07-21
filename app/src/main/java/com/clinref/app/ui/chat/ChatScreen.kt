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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.clinref.app.ui.chat.components.GeminiChatInput
import com.clinref.app.ui.chat.components.GeminiMessageItem
import com.clinref.app.ui.chat.components.GeminiOrchestrationIndicator
import com.clinref.app.ui.chat.components.ScrollToBottomFAB
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateToContent: (String, String?) -> Unit,
    onGraphicSelected: (String) -> Unit,
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
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val showScrollToBottom by remember {
        derivedStateOf { listState.canScrollForward }
    }

    val isGenerating = agentState is StreamingManager.AgentState.ToolCallInProgress ||
        agentState is StreamingManager.AgentState.WaitingForLlm

    var activeInputText by remember { mutableStateOf("") }

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(chatItems.size - 1)
            }
        }
    }

    LaunchedEffect(listState.firstVisibleItemIndex, hasMoreMessages) {
        if (listState.firstVisibleItemIndex <= 2 && hasMoreMessages && !isLoadingOlder) {
            viewModel.loadOlderMessages()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
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
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.85f),
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f)
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
                        bottom = padding.calculateBottomPadding() + 180.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isLoadingOlder) {
                        item {
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
                                    viewModel.copyMessageToClipboard(context, content)
                                },
                                onNavigateToContent = onNavigateToContent,
                                onGraphicSelected = onGraphicSelected
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
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            GeminiChatInput(
                textValue = activeInputText,
                onValueChange = { activeInputText = it },
                onSendMessage = {
                    viewModel.sendMessage(it)
                    activeInputText = ""
                },
                onCancel = { viewModel.cancelGeneration() },
                isGenerating = isGenerating,
                patientProfile = patientProfile,
                modifier = Modifier.align(Alignment.BottomCenter)
            )

            ScrollToBottomFAB(
                visible = showScrollToBottom,
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(chatItems.size - 1)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
            )
        }
    }
}
