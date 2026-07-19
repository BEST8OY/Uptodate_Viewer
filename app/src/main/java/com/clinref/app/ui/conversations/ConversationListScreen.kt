package com.clinref.app.ui.conversations

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.ai.PatientProfile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onConversationSelected: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val uiConversations by viewModel.uiConversations.collectAsStateWithLifecycle()
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var showProfileSheet by remember { mutableStateOf(false) }

    pendingDeleteId?.let { deleteId ->
        val conversation = uiConversations.find { it.entity.id == deleteId }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete Conversation") },
            text = {
                Text("Permanently delete \"${conversation?.entity.title ?: ""}\"? This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(deleteId)
                    pendingDeleteId = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showProfileSheet) {
        PatientProfileSheet(
            onDismiss = { showProfileSheet = false },
            onStart = { profile ->
                viewModel.createConversation(
                    title = buildConversationTitle(profile),
                    patientProfile = profile,
                    onCreated = onConversationSelected
                )
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize().padding(bottom = 80.dp)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Conversations") },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "AI Settings"
                            )
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showProfileSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New Conversation")
                }
            }
        ) { padding ->
            if (uiConversations.isEmpty()) {
                // Better empty state
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        modifier = Modifier.size(80.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No conversations yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Start a new conversation to ask clinical questions",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    items(
                        items = uiConversations,
                        key = { it.entity.id }
                    ) { uiModel ->
                        ConversationItemWithSwipe(
                            uiModel = uiModel,
                            onClick = {
                                viewModel.markAsRead(uiModel.entity.id)
                                onConversationSelected(uiModel.entity.id)
                            },
                            onDelete = { pendingDeleteId = uiModel.entity.id }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationItemWithSwipe(
    uiModel: ConversationUiModel,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                false // Don't actually dismiss, show dialog
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surface
                },
                label = "swipe_color"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier
    ) {
        ConversationItem(
            uiModel = uiModel,
            onClick = onClick
        )
    }
}

@Composable
private fun ConversationItem(
    uiModel: ConversationUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar circle
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = uiModel.entity.title.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = uiModel.entity.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (uiModel.hasUnread) FontWeight.Bold else FontWeight.Normal
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = uiModel.lastMessagePreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    text = formatRelativeDate(uiModel.entity.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // Unread badge
            if (uiModel.hasUnread) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        // Just show a dot for unread
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(8.dp)
                        ) {}
                    }
                }
            }
        }
    }
}

private fun buildConversationTitle(profile: PatientProfile): String {
    val parts = mutableListOf<String>()
    if (profile.age.isNotBlank()) parts.add(profile.age)
    if (profile.sex.isNotBlank()) parts.add(profile.sex)
    if (profile.conditions.isNotEmpty()) parts.add(profile.conditions.first())
    return if (parts.isNotEmpty()) parts.joinToString(", ") else "New Conversation"
}

private fun formatRelativeDate(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msgDate = Calendar.getInstance().apply { timeInMillis = timestamp }

    return when {
        now.get(Calendar.YEAR) == msgDate.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == msgDate.get(Calendar.DAY_OF_YEAR) -> {
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
        now.get(Calendar.YEAR) == msgDate.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) - msgDate.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
        now.get(Calendar.YEAR) == msgDate.get(Calendar.YEAR) -> {
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
