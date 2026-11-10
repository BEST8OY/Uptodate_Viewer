package com.clinref.app.ui.content

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.adaptive.LocalIsWideScreen

private enum class OverviewTab(val label: String) {
    OUTLINE("Outline"),
    GRAPHICS("Graphics"),
    RELATED("Related Topics")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutlineOverlay(
    showOutline: Boolean,
    outlineSections: List<OutlineSection>,
    activeSectionId: String?,
    onSectionClick: (OutlineSection) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!showOutline || outlineSections.isEmpty()) return

    val isWideScreen = LocalIsWideScreen.current

    val allSections = remember(outlineSections) {
        outlineSections.filter { it.sectionType == SectionType.TOPIC }.distinctBy { it.id }
    }
    val allGraphics = remember(outlineSections) {
        outlineSections.filter { it.sectionType == SectionType.GRAPHIC }.distinctBy { it.id }
    }
    val allRelated = remember(outlineSections) {
        outlineSections.filter { it.sectionType == SectionType.RELATED }.distinctBy { it.id }
    }

    val summarySection = remember(allSections) {
        allSections.firstOrNull { sec ->
            val t = sec.title.trim().lowercase()
            t == "summary" ||
                t.startsWith("summary and recommendations") ||
                t.startsWith("summary & recommendations")
        }
    }

    val defaultTab = remember(outlineSections) {
        when {
            allSections.isNotEmpty() -> OverviewTab.OUTLINE
            allGraphics.isNotEmpty() -> OverviewTab.GRAPHICS
            else -> OverviewTab.RELATED
        }
    }

    var selectedTab by rememberSaveable { mutableStateOf(defaultTab) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedGraphicSubtype by rememberSaveable { mutableStateOf<String?>(null) }

    val graphicSubtypes = remember(allGraphics) {
        val present = allGraphics.map { it.graphicSubtype }.distinct().filter { it.isNotBlank() }
        if (present.size > 1) {
            listOf(null) + present
        } else emptyList()
    }

    val filteredSections = remember(allSections, searchQuery) {
        val list = if (searchQuery.isBlank()) allSections
        else allSections.filter { it.title.contains(searchQuery, ignoreCase = true) }
        list.distinctBy { it.id }
    }

    val filteredGraphics = remember(allGraphics, searchQuery, selectedGraphicSubtype) {
        allGraphics.filter { item ->
            val matchesSubtype = selectedGraphicSubtype == null || item.graphicSubtype == selectedGraphicSubtype
            val matchesQuery = searchQuery.isBlank() ||
                item.title.contains(searchQuery, ignoreCase = true) ||
                item.graphicLabel.contains(searchQuery, ignoreCase = true)
            matchesSubtype && matchesQuery
        }.distinctBy { it.id }
    }

    val filteredRelated = remember(allRelated, searchQuery) {
        val list = if (searchQuery.isBlank()) allRelated
        else allRelated.filter { it.title.contains(searchQuery, ignoreCase = true) }
        list.distinctBy { it.id }
    }

    val sheetContent = @Composable {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Instant Search Field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = when (selectedTab) {
                            OverviewTab.OUTLINE -> "Filter sections..."
                            OverviewTab.GRAPHICS -> "Filter tables, figures, algorithms..."
                            OverviewTab.RELATED -> "Filter related topics..."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = CircleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Segmented Pill Navigation Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OverviewTab.entries.forEach { tab ->
                    val count = when (tab) {
                        OverviewTab.OUTLINE -> allSections.size
                        OverviewTab.GRAPHICS -> allGraphics.size
                        OverviewTab.RELATED -> allRelated.size
                    }
                    val isSelected = selectedTab == tab
                    Surface(
                        onClick = {
                            selectedTab = tab
                            searchQuery = ""
                        },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                role = Role.Tab
                                selected = isSelected
                            },
                        shape = CircleShape,
                        color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
                        tonalElevation = if (isSelected) 2.dp else 0.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (count > 0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    }
                                ) {
                                    Text(
                                        text = count.toString(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Subtype filter pills for Graphics tab
            if (selectedTab == OverviewTab.GRAPHICS && graphicSubtypes.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(graphicSubtypes) { subtype ->
                        val label = when (subtype) {
                            null -> "All"
                            "graphic_table" -> "Tables"
                            "graphic_algorithm" -> "Algorithms"
                            "graphic_figure" -> "Figures"
                            "graphic_picture" -> "Pictures"
                            "graphic_diagnosticimage" -> "Images"
                            "graphic_waveform" -> "Waveforms"
                            "graphic_movie" -> "Videos"
                            else -> subtype.removePrefix("graphic_").replaceFirstChar { it.uppercase() }
                        }
                        val count = if (subtype == null) allGraphics.size else allGraphics.count { it.graphicSubtype == subtype }
                        val isSelected = selectedGraphicSubtype == subtype

                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedGraphicSubtype = subtype },
                            label = {
                                Text(
                                    text = "$label ($count)",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Body List per selected tab
            when (selectedTab) {
                OverviewTab.OUTLINE -> {
                    if (filteredSections.isEmpty()) {
                        OverviewEmptyState(
                            icon = if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                            title = if (searchQuery.isNotBlank()) "No matching sections" else "No outline available",
                            subtitle = if (searchQuery.isNotBlank()) "Try another keyword" else "This document has no section outline"
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            if (searchQuery.isBlank() && summarySection != null && allSections.indexOf(summarySection) > 0) {
                                item(key = "shortcut_summary_and_recommendations") {
                                    SummaryQuickJumpCard(
                                        title = summarySection.title,
                                        isActive = summarySection.id == activeSectionId,
                                        onClick = { onSectionClick(summarySection) }
                                    )
                                }
                            }

                            items(
                                items = filteredSections,
                                key = { "sec_${it.id}" }
                            ) { section ->
                                OutlineSectionItem(
                                    section = section,
                                    isActive = section.id == activeSectionId,
                                    onClick = { onSectionClick(section) }
                                )
                            }
                        }
                    }
                }

                OverviewTab.GRAPHICS -> {
                    if (filteredGraphics.isEmpty()) {
                        OverviewEmptyState(
                            icon = if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                            title = if (searchQuery.isNotBlank()) "No matching graphics" else "No graphics available",
                            subtitle = if (searchQuery.isNotBlank()) "Try clearing your filter" else "This topic contains no tables or figures"
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = filteredGraphics,
                                key = { "graphic_${it.id}" }
                            ) { graphic ->
                                GraphicOverviewItem(
                                    graphic = graphic,
                                    onClick = { onSectionClick(graphic) }
                                )
                            }
                        }
                    }
                }

                OverviewTab.RELATED -> {
                    if (filteredRelated.isEmpty()) {
                        OverviewEmptyState(
                            icon = if (searchQuery.isNotBlank()) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                            title = if (searchQuery.isNotBlank()) "No matching topics" else "No related topics",
                            subtitle = if (searchQuery.isNotBlank()) "Try another search term" else "No related topics are indexed for this entry"
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = filteredRelated,
                                key = { "rel_${it.id}" }
                            ) { topic ->
                                RelatedTopicItem(
                                    topic = topic,
                                    onClick = { onSectionClick(topic) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (isWideScreen) {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier
                    .widthIn(max = AdaptiveTokens.MaxDialogWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(vertical = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Topic Outline",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close outline")
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(6.dp))
                    sheetContent()
                }
            }
        }
    } else {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
        )
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .imePadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Topic Outline",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close outline")
                    }
                }
                sheetContent()
            }
        }
    }
}

@Composable
private fun SummaryQuickJumpCard(
    title: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    OutlinedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            overlineContent = {
                Text(
                    text = "QUICK JUMP",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
            },
            leadingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Jump to summary",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            content = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
    }
}

@Composable
private fun OutlineSectionItem(
    section: OutlineSection,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val isRoot = section.depth == 0

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            if (isRoot) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = (12 + section.depth * 14).dp,
                    end = 12.dp,
                    top = if (isRoot) 10.dp else 7.dp,
                    bottom = if (isRoot) 10.dp else 7.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(width = 3.5.dp, height = 18.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.primary)
                )
                Spacer(modifier = Modifier.width(8.dp))
            } else if (!isRoot) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            Text(
                text = section.title,
                style = if (isRoot) {
                    MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (isActive) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else if (isRoot) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun GraphicOverviewItem(
    graphic: OutlineSection,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GraphicSubtypeBadge(subtype = graphic.graphicSubtype)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (graphic.graphicLabel.isNotBlank()) {
                    Text(
                        text = graphic.graphicLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = graphic.cleanGraphicTitle(),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = "Open graphic",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun RelatedTopicItem(
    topic: OutlineSection,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = topic.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Article,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun GraphicSubtypeBadge(
    subtype: String,
    modifier: Modifier = Modifier
) {
    val (icon, tint) = when (subtype) {
        "graphic_table" -> Icons.Default.TableChart to MaterialTheme.colorScheme.primary
        "graphic_algorithm" -> Icons.Default.AccountTree to MaterialTheme.colorScheme.tertiary
        "graphic_diagnosticimage", "graphic_picture", "graphic_figure" -> Icons.Default.Image to MaterialTheme.colorScheme.secondary
        "graphic_movie" -> Icons.Default.PlayCircle to MaterialTheme.colorScheme.error
        "graphic_waveform" -> Icons.AutoMirrored.Filled.ShowChart to MaterialTheme.colorScheme.primary
        else -> Icons.Default.Description to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .size(36.dp)
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun OverviewEmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun OutlineSection.cleanGraphicTitle(): String {
    if (graphicLabel.isNotBlank()) {
        val prefixWithDash = "$graphicLabel - "
        if (title.startsWith(prefixWithDash, ignoreCase = true)) {
            return title.substring(prefixWithDash.length).trim()
        }
        val prefixWithColon = "$graphicLabel: "
        if (title.startsWith(prefixWithColon, ignoreCase = true)) {
            return title.substring(prefixWithColon.length).trim()
        }
    }
    return title
}
