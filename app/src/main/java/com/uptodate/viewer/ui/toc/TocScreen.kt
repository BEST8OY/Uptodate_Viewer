package com.uptodate.viewer.ui.toc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.uptodate.viewer.domain.TocItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocScreen(
    onTopicSelected: (String) -> Unit,
    viewModel: TocViewModel = hiltViewModel()
) {
    val tocItems by viewModel.tocItems.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contents") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(tocItems) { item ->
                TocItemRow(
                    item = item,
                    onTopicSelected = onTopicSelected,
                    onLoadChildren = { parentId -> viewModel.loadChildren(parentId) }
                )
            }
        }
    }
}

@Composable
fun TocItemRow(
    item: TocItem,
    onTopicSelected: (String) -> Unit,
    onLoadChildren: (String) -> Unit,
    level: Int = 0
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (item.isLeaf) {
                        onTopicSelected(item.id)
                    } else {
                        if (item.childrenInfo == null) {
                            onLoadChildren(item.id)
                        }
                        expanded = !expanded
                    }
                }
                .padding(start = (16 + level * 24).dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!item.isLeaf) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
        }

        if (expanded && item.childrenInfo != null) {
            item.childrenInfo.forEach { child ->
                TocItemRow(
                    item = child,
                    onTopicSelected = onTopicSelected,
                    onLoadChildren = onLoadChildren,
                    level = level + 1
                )
            }
        }
    }
}
