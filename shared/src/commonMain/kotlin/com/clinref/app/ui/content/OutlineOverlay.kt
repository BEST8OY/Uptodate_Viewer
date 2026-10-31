package com.clinref.app.ui.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.adaptive.LocalIsWideScreen

private sealed class OutlineItem {
    data class Section(val section: OutlineSection) : OutlineItem()
    data class GroupHeader(val title: String, val indented: Boolean = false) : OutlineItem()
    data class Spacer(val dp: Int) : OutlineItem()
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

    val displayItems = remember(outlineSections) {
        buildList {
            var lastType: SectionType? = null
            var lastGraphicGroup = ""
            add(OutlineItem.GroupHeader("Outline"))
            for (section in outlineSections) {
                if (section.sectionType != lastType) {
                    when (section.sectionType) {
                        SectionType.GRAPHIC -> {
                            add(OutlineItem.Spacer(8))
                            add(OutlineItem.GroupHeader("Graphics"))
                        }
                        SectionType.RELATED -> {
                            add(OutlineItem.Spacer(8))
                            add(OutlineItem.GroupHeader("Related Topics"))
                        }
                        else -> {}
                    }
                }
                if (section.sectionType == SectionType.GRAPHIC) {
                    val group = when (section.graphicSubtype) {
                        "graphic_table" -> "Tables"
                        "graphic_figure" -> "Figures"
                        "graphic_algorithm" -> "Algorithms"
                        "graphic_picture" -> "Pictures"
                        "graphic_movie" -> "Movies"
                        "graphic_waveform" -> "Waveforms"
                        "graphic_diagnosticimage" -> "Diagnostic Images"
                        else -> "Other"
                    }
                    if (group != lastGraphicGroup) {
                        if (lastGraphicGroup.isNotEmpty()) add(OutlineItem.Spacer(4))
                        add(OutlineItem.GroupHeader(group, indented = true))
                        lastGraphicGroup = group
                    }
                } else {
                    lastGraphicGroup = ""
                }
                add(OutlineItem.Section(section))
                lastType = section.sectionType
            }
        }
    }

    val outlineListContent = @Composable {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            itemsIndexed(
                items = displayItems,
                key = { index, item ->
                    when (item) {
                        is OutlineItem.Section -> "section_${index}_${item.section.id}"
                        is OutlineItem.GroupHeader -> "header_${index}_${item.title}"
                        is OutlineItem.Spacer -> "spacer_${index}_${item.dp}"
                    }
                }
            ) { _, item ->
                when (item) {
                    is OutlineItem.Spacer -> {
                        Spacer(modifier = Modifier.height(item.dp.dp))
                    }
                    is OutlineItem.GroupHeader -> {
                        Text(
                            text = item.title.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = if (item.indented) 28.dp else 16.dp,
                                top = 12.dp,
                                bottom = 6.dp
                            )
                        )
                    }
                    is OutlineItem.Section -> {
                        val section = item.section
                        val isTopic = section.sectionType == SectionType.TOPIC
                        val isActive = section.id == activeSectionId
                        Surface(
                            onClick = {
                                onSectionClick(section)
                            },
                            color = if (isActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            shape = MaterialTheme.shapes.small
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = (12 + section.depth * 16).dp,
                                        end = 12.dp,
                                        top = 10.dp,
                                        bottom = 10.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isActive) {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 3.dp, height = 16.dp)
                                            .background(
                                                MaterialTheme.colorScheme.primary,
                                                shape = MaterialTheme.shapes.extraSmall
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(
                                    text = section.title,
                                    style = if (isTopic) {
                                        MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                    } else {
                                        MaterialTheme.typography.bodySmall
                                    },
                                    color = if (isActive) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
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
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                modifier = Modifier
                    .widthIn(max = AdaptiveTokens.MaxDialogWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(vertical = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Document Outline",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    outlineListContent()
                }
            }
        }
    } else {
        val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier
        ) {
            outlineListContent()
        }
    }
}

