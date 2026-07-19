package com.clinref.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.ui.chat.components.ChatInput
import com.clinref.app.ui.chat.components.ComplexDataWarningCard
import com.clinref.app.ui.chat.components.DisclaimerBanner
import com.clinref.app.ui.chat.components.MessageBubble
import com.clinref.app.ui.chat.components.SourceCitationCard
import com.clinref.app.ui.chat.components.ToolProgressIndicator
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateToContent: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val agentState by viewModel.agentState.collectAsStateWithLifecycle()
    val toolProgress by viewModel.toolProgress.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(conversationId) {
        viewModel.loadConversation(conversationId)
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ClinRef AI") }
            )
        },
        bottomBar = {
            DisclaimerBanner()
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(
                    items = messages,
                    key = { it.id }
                ) { message ->
                    MessageBubble(message = message)

                    // Show citation cards for assistant messages
                    if (message.role == "assistant" && message.citations.isNotEmpty()) {
                        message.citations.forEach { citation ->
                            SourceCitationCard(
                                citation = citation,
                                onClick = { onNavigateToContent(citation.topicId) }
                            )
                        }
                    }

                    // Show warning cards
                    if (message.warnings.isNotEmpty()) {
                        message.warnings.forEach { warning ->
                            ComplexDataWarningCard(warning = warning)
                        }
                    }
                }

                // Show tool progress
                if (toolProgress != null) {
                    item {
                        ToolProgressIndicator(progress = toolProgress!!)
                    }
                }

                // Show generating indicator
                if (agentState is StreamingManager.AgentState.WaitingForLlm ||
                    agentState is StreamingManager.AgentState.ToolCallInProgress
                ) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Generating response\u2026",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            ChatInput(
                onSendMessage = { viewModel.sendMessage(it) },
                onCancel = { viewModel.cancelGeneration() },
                isGenerating = agentState is StreamingManager.AgentState.ToolCallInProgress ||
                    agentState is StreamingManager.AgentState.WaitingForLlm
            )
        }
    }
}
