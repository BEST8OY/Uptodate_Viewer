package com.clinref.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownBlockQuote
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * Clinical Markdown renderer tuned for ClinRef's point-of-care synthesis structure:
 * - Stabilizes partial tokens while [isStreaming] is true.
 * - Retains previous parsed AST across streaming updates (zero blank-frame flashes).
 */
@Composable
fun ClinRefMarkdownContent(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    if (content.isBlank()) return

    val renderedContent = remember(content, isStreaming) {
        if (isStreaming) StreamingMarkdownStabilizer.stabilize(content) else content
    }

    val markdownState = rememberMarkdownState(
        content = renderedContent,
        retainState = true,
        immediate = !isStreaming
    )

    Markdown(
        markdownState = markdownState,
        modifier = modifier.fillMaxWidth(),
        colors = rememberClinrefMarkdownColors(),
        typography = rememberClinrefMarkdownTypography(),
        dimens = rememberClinrefMarkdownDimens(),
        components = rememberClinrefMarkdownComponents()
    )
}

@Composable
fun rememberClinrefMarkdownColors(): MarkdownColors {
    val scheme = MaterialTheme.colorScheme
    return markdownColor(
        text = scheme.onSurface,
        codeBackground = scheme.surfaceContainerHighest,
        inlineCodeBackground = scheme.surfaceContainerHighest,
        dividerColor = scheme.outlineVariant.copy(alpha = 0.5f),
        tableBackground = Color.Transparent
    )
}

@Composable
fun rememberClinrefMarkdownDimens(): MarkdownDimens = markdownDimens(
    blockQuoteThickness = 0.dp, // Drawn cleanly by our custom clinical callout container
    codeBackgroundCornerSize = 6.dp,
    tableCellPadding = 10.dp,
    tableCellWidth = MarkdownTableCellWidth,
    tableCornerSize = 12.dp
)

@Composable
fun rememberClinrefMarkdownTypography(): MarkdownTypography {
    val t = MaterialTheme.typography
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    return markdownTypography(
        h1 = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        h2 = t.titleMedium.copy(fontWeight = FontWeight.Bold),
        // Primary clinical subheading used by SystemPrompt (### Subheading)
        h3 = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        h4 = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        h5 = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        h6 = t.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        paragraph = t.bodyLarge,
        text = t.bodyLarge,
        bullet = t.bodyLarge,
        ordered = t.bodyLarge,
        list = t.bodyLarge,
        quote = t.bodyMedium.copy(
            fontStyle = FontStyle.Italic,
            color = onSurfaceVariant
        ),
        code = t.bodySmall.copy(fontFamily = FontFamily.Monospace),
        inlineCode = t.bodySmall.copy(fontFamily = FontFamily.Monospace),
        table = t.bodySmall,
        alertTitle = t.titleSmall.copy(fontWeight = FontWeight.Bold)
    )
}

@Composable
fun rememberClinrefMarkdownComponents(): MarkdownComponents {
    val shapes = MaterialTheme.shapes
    val containerLow = MaterialTheme.colorScheme.surfaceContainerLow
    val accentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    return remember(shapes, containerLow, accentColor) {
        markdownComponents(
            table = { model ->
                ClinRefMarkdownTable(model = model)
            },
            blockQuote = { model ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(shapes.small)
                        .background(containerLow)
                        .drawBehind {
                            val strokePx = 3.dp.toPx()
                            drawLine(
                                color = accentColor,
                                start = Offset(strokePx / 2f, 0f),
                                end = Offset(strokePx / 2f, size.height),
                                strokeWidth = strokePx
                            )
                        }
                        .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
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
