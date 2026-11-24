package com.clinref.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.Audience
import com.clinref.app.domain.SearchResult
import com.clinref.app.ui.adaptive.CenteredReadableContainer
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import com.clinref.app.ui.adaptive.rememberContentBottomPadding
import com.clinref.app.ui.common.ClinRefEmptyState
import com.clinref.app.ui.common.ClinRefErrorState

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SearchResultsContent(
    searchResults: List<SearchResult>,
    selectedAudience: Audience,
    isSearchLoading: Boolean,
    searchError: String?,
    onAudienceChanged: (Audience) -> Unit,
    onTopicSelected: (String) -> Unit,
    onGraphicSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val adaptiveInfo = LocalWindowAdaptiveInfo.current
    val bottomInsetPadding = rememberContentBottomPadding(16.dp)

    CenteredReadableContainer(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomInsetPadding),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = adaptiveInfo.horizontalMargin, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Audience.entries.forEach { audience ->
                        FilterChip(
                            selected = selectedAudience == audience,
                            onClick = { onAudienceChanged(audience) },
                            label = { Text(audience.label) },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        )
                    }
                }
            }

            if (searchError != null) {
                item {
                    ClinRefErrorState(
                        message = searchError,
                        scrollable = false,
                        fillViewport = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (searchResults.isEmpty() && searchError == null && !isSearchLoading) {
                item {
                    ClinRefEmptyState(
                        icon = Icons.Default.SearchOff,
                        title = "No results found" +
                            if (selectedAudience != Audience.ALL) " for ${selectedAudience.label}" else "",
                        titleStyle = MaterialTheme.typography.bodyLarge,
                        scrollable = false,
                        fillViewport = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (isSearchLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
                    }
                }
            }

            if (searchResults.isNotEmpty()) {
                item {
                    Text(
                        text = "${searchResults.size} result${if (searchResults.size != 1) "s" else ""} found",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = adaptiveInfo.horizontalMargin, vertical = 8.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
                itemsIndexed(
                    items = searchResults,
                    key = { _, result ->
                        when (result) {
                            is SearchResult.Topic -> "t_${result.topicId}"
                            is SearchResult.Graphic -> "g_${result.graphicId}"
                        }
                    }
                ) { idx, result ->
                    SegmentedListItem(
                        onClick = {
                            when (result) {
                                is SearchResult.Topic -> onTopicSelected(result.topicId)
                                is SearchResult.Graphic -> onGraphicSelected(result.graphicId)
                            }
                        },
                        shapes = ListItemDefaults.segmentedShapes(index = idx, count = searchResults.size),
                        modifier = Modifier
                            .padding(horizontal = adaptiveInfo.horizontalMargin)
                            .pointerHoverIcon(PointerIcon.Hand),
                        content = { Text(result.title) }
                    )
                }
            }
        }
    }
}
