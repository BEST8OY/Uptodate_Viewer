package com.clinref.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.LocalTableRowIndex
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow

private val MIN_TABLE_CELL_WIDTH = 160.dp

@Composable
fun ClinRefMarkdownTable(
    model: MarkdownComponentModel,
    modifier: Modifier = Modifier
) {
    val node = model.node
    val content = model.content
    val tableStyle = model.typography.table

    val (columnsCount, dataRowCount) = remember(node) {
        val cols = node.children.firstOrNull { it.type.name == "HEADER" }
            ?.children
            ?.count { it.type.name == "CELL" } ?: 0
        val rows = node.children.count { it.type.name == "ROW" }
        cols to rows
    }

    val headerTextStyle = MaterialTheme.typography.bodySmall.copy(
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold
    )
    val rowTextStyle = MaterialTheme.typography.bodySmall.copy(
        color = MaterialTheme.colorScheme.onSurface
    )

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isScrollable = columnsCount > 1 && maxWidth < (MIN_TABLE_CELL_WIDTH * columnsCount)

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(MaterialTheme.shapes.medium)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MarkdownTable(
                    content = content,
                    node = node,
                    style = tableStyle,
                    headerBlock = { c, header, tableWidth, _ ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            MarkdownTableHeader(
                                content = c,
                                header = header,
                                tableWidth = tableWidth,
                                style = headerTextStyle,
                                verticalAlignment = Alignment.Top,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                thickness = 0.75.dp
                            )
                        }
                    },
                    rowBlock = { c, row, tableWidth, _ ->
                        val rowIndex = LocalTableRowIndex.current
                        val isShaded = rowIndex % 2 == 1
                        val isLastRow = dataRowCount > 0 && rowIndex >= dataRowCount - 1
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isShaded) {
                                        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.3f)
                                    } else {
                                        Color.Transparent
                                    }
                                )
                        ) {
                            MarkdownTableRow(
                                content = c,
                                header = row,
                                tableWidth = tableWidth,
                                style = rowTextStyle,
                                verticalAlignment = Alignment.Top,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip
                            )
                            if (!isLastRow) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }
                )

                if (isScrollable) {
                    DisableSelection {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            thickness = 0.5.dp
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Swipe horizontally to view all $columnsCount columns",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }
    }
}
