package com.uptodate.viewer.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onTopicSelected: (String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val suggestions by viewModel.suggestions.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.onQueryChanged(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search UpToDate...") },
                singleLine = true
            )

            LazyColumn {
                items(suggestions) { suggestion ->
                    ListItem(
                        headlineContent = { Text(suggestion) },
                        modifier = Modifier.clickable {
                            query = suggestion
                            viewModel.search(suggestion)
                        }
                    )
                }

                items(searchResults) { result ->
                    ListItem(
                        headlineContent = { Text(result.title) },
                        modifier = Modifier.clickable {
                            onTopicSelected(result.topicId)
                        }
                    )
                }
            }
        }
    }
}
