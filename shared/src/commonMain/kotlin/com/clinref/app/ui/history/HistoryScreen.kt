package com.clinref.app.ui.history

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import com.clinref.app.ui.common.TopicCollectionEntry
import com.clinref.app.ui.common.TopicCollectionScreen
import com.clinref.app.ui.common.TopicCollectionStrings
import com.clinref.app.ui.navigation.HistoryRoute
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.compose.viewmodel.koinViewModel

private val HistoryStrings = TopicCollectionStrings(
    title = "History",
    subtitle = { count -> "$count recently opened topics" },
    clearAllDialogTitle = "Clear all history?",
    clearAllDialogText = "This will remove all items from your reading history.",
    clearAllActionDescription = "Clear all history",
    emptyStateText = "No reading history",
    emptyStateIcon = Icons.Default.History,
    itemLeadingIcon = Icons.Default.History,
    removedSnackbarMessage = { count -> if (count == 1) "History removed" else "$count history items removed" },
    removeAccessibilityLabel = "Remove from history",
    swipeActionLabel = "Delete",
)

@Composable
fun HistoryScreen(
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    reselectEvents: Flow<NavKey> = emptyFlow(),
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val entries = remember(history) {
        history.map { TopicCollectionEntry(it.topicId, it.title, it.timestamp) }
    }

    TopicCollectionScreen(
        entries = entries,
        strings = HistoryStrings,
        onTopicSelected = onTopicSelected,
        onGraphicSelected = onGraphicSelected,
        onRemoveEntry = { viewModel.removeHistory(it) },
        onClearAll = { viewModel.clearHistory() },
        onRestoreEntries = { restored ->
            restored.forEach { viewModel.addHistory(it.topicId, it.title, it.timestamp) }
        },
        reselectKey = HistoryRoute,
        modifier = modifier,
        reselectEvents = reselectEvents,
    )
}
