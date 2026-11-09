package com.clinref.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.LocalTableRowIndex
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownDimens

@Composable
fun rememberClinrefMarkdownComponents(): MarkdownComponents {
    val shapes = MaterialTheme.shapes
    val containerLow = MaterialTheme.colorScheme.surfaceContainerLow
    return remember(shapes, containerLow) {
        markdownComponents(
            table = { model ->
                ClinRefMarkdownTable(model = model)
            },
            blockQuote = { model ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shapes.small)
                        .background(containerLow)
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                ) {
                    MarkdownBlockQuote(
                        content = model.content,
                        node = model.node,
                        style = model.typography.quote
                    )
                }
            }
        )
    }
}

@Composable
fun clinrefMarkdownComponents(): MarkdownComponents = rememberClinrefMarkdownComponents()

@Composable
fun clinrefMarkdownDimens(): MarkdownDimens = markdownDimens(
    blockQuoteThickness = 3.dp,
    codeBackgroundCornerSize = 4.dp,
    tableCellPadding = 10.dp,
    tableCellWidth = 160.dp,
    tableCornerSize = 12.dp
)

@Composable
fun clinrefMarkdownTypography(): MarkdownTypography = markdownTypography(
    h1 = MaterialTheme.typography.titleLarge.copy(
        fontWeight = FontWeight.Bold
    ),
    h2 = MaterialTheme.typography.titleMedium.copy(
        fontWeight = FontWeight.Bold
    ),
    h3 = MaterialTheme.typography.titleSmall.copy(
        fontWeight = FontWeight.Bold
    ),
    h4 = MaterialTheme.typography.labelLarge.copy(
        fontWeight = FontWeight.SemiBold
    ),
    h5 = MaterialTheme.typography.labelMedium.copy(
        fontWeight = FontWeight.SemiBold
    ),
    h6 = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.Medium
    ),
    paragraph = MaterialTheme.typography.bodyLarge,
    text = MaterialTheme.typography.bodyLarge,
    bullet = MaterialTheme.typography.bodyLarge,
    ordered = MaterialTheme.typography.bodyLarge,
    list = MaterialTheme.typography.bodyLarge,
    quote = MaterialTheme.typography.bodyMedium.copy(
        fontStyle = FontStyle.Italic,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    ),
    code = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace
    ),
    inlineCode = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace
    )
)

@Composable
fun ClinRefMarkdownTable(
    model: MarkdownComponentModel,
    modifier: Modifier = Modifier
) {
    val node = model.node
    val content = model.content
    val tableStyle = model.typography.table

    // Compute column count to determine scrollability hint
    val columnsCount = remember(node) {
        node.children.firstOrNull { it.type.name == "HEADER" }
            ?.children
            ?.count { it.type.name == "CELL" } ?: 0
    }

    val cellWidth = 160.dp

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val isScrollable = columnsCount > 1 && maxWidth < (160 * columnsCount).dp

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
                    headerBlock = { c, header, tableWidth, style ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            MarkdownTableHeader(
                                content = c,
                                header = header,
                                tableWidth = tableWidth,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                verticalAlignment = Alignment.Top,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip
                            )
                        }
                    },
                    rowBlock = { c, row, tableWidth, style ->
                        val rowIndex = LocalTableRowIndex.current
                        val isEven = rowIndex % 2 == 0
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isEven) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f)
                                    else Color.Transparent
                                )
                        ) {
                            MarkdownTableRow(
                                content = c,
                                header = row,
                                tableWidth = tableWidth,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                verticalAlignment = Alignment.Top,
                                maxLines = Int.MAX_VALUE,
                                overflow = TextOverflow.Clip
                            )
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                thickness = 0.5.dp
                            )
                        }
                    }
                )

                if (isScrollable) {
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Scroll horizontally to view all $columnsCount columns",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}
