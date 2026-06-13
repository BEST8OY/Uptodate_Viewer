package com.uptodate.viewer.ui.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uptodate.viewer.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onTopicClick: (String) -> Unit,
    viewModel: HistoryViewModel
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (state.items.isNotEmpty()) {
            TextButton(onClick = { viewModel.clear() }) {
                Text(stringResource(R.string.clear_history), color = MaterialTheme.colorScheme.error)
            }
        }

        if (state.items.isEmpty()) {
            Text(
                text = "No history",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn {
                items(state.items, key = { it.topicId }) { entry ->
                    var showDelete by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onTopicClick(entry.topicId) },
                                onLongClick = { showDelete = !showDelete }
                            )
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        if (showDelete) {
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable {
                                        viewModel.remove(entry.topicId)
                                        showDelete = false
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}
