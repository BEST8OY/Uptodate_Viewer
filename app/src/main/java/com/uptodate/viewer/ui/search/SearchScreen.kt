package com.uptodate.viewer.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.uptodate.viewer.R

@Composable
fun SearchScreen(
    onTopicClick: (String) -> Unit,
    viewModel: SearchViewModel
) {
    val state by viewModel.state.collectAsState()
    val prefs = viewModel.getPreferences()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { viewModel.onQueryChange(it) },
            placeholder = { Text(stringResource(R.string.search_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors()
        )

        Spacer(Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            prefs.forEach { (value, label) ->
                FilterChip(
                    selected = state.preference == value,
                    onClick = { viewModel.onPreferenceChange(value) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.padding(end = 4.dp),
                    colors = FilterChipDefaults.filterChipColors()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (state.isSearching) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }

        if (state.suggestions.isNotEmpty()) {
            LazyColumn {
                items(state.suggestions) { word ->
                    Text(
                        text = word,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onSuggestionClick(word) }
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (state.results.isNotEmpty()) {
            LazyColumn {
                items(state.results, key = { it.topicId }) { result ->
                    Text(
                        text = result.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTopicClick(result.topicId) }
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
