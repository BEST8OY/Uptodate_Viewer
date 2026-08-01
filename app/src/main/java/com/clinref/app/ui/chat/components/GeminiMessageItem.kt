package com.clinref.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.ui.chat.MessageUiModel
import com.clinref.app.ui.chat.StreamingMarkdownBuffer
import com.clinref.app.ui.chat.ResolvedTopicRef
import com.clinref.app.ui.chat.ResolvedGraphicRef
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.material3.RichText

@Composable
fun GeminiMessageItem(
    message: MessageUiModel,
    onCopyMessage: (String) -> Unit = {},
    onNavigateToContent: (String, String?) -> Unit = { _, _ -> },
    onGraphicSelected: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val isCancelled = message.role == "cancelled"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 16.sp,
                        lineHeight = 22.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (message.isError) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f))
                            .padding(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Internal Exception",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                } else if (isCancelled) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val buffer = remember { StreamingMarkdownBuffer() }
                    val renderState by produceState(
                        initialValue = StreamingMarkdownBuffer.RenderState(message.content, ""),
                        key1 = message.content,
                    ) {
                        value = withContext(Dispatchers.Default) {
                            buffer.process(message.content)
                        }
                    }

                    RichText(modifier = Modifier.fillMaxWidth()) {
                        Markdown(renderState.renderedMarkdown)
                    }

                    if (renderState.pendingPlainText.isNotEmpty()) {
                        Text(
                            text = renderState.pendingPlainText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }

                    val allRefs = message.graphicRefs.map { it.title to { onGraphicSelected(it.graphicId) } } +
                        message.topicRefs.map { it.title to { onNavigateToContent(it.topicId, it.sectionId) } }
                    if (allRefs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(message.graphicRefs) { ref ->
                                GeminiGraphicPill(
                                    label = ref.title,
                                    graphicId = ref.graphicId,
                                    onClick = { onGraphicSelected(ref.graphicId) }
                                )
                            }
                            items(message.topicRefs) { ref ->
                                GeminiTopicPill(
                                    label = ref.title,
                                    onClick = { onNavigateToContent(ref.topicId, ref.sectionId) }
                                )
                            }
                        }
                    }
                }

                if (message.warnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    message.warnings.forEach { warning ->
                        GeminiWarningAlert(warning = warning)
                    }
                }

                if (!message.isError && !isCancelled) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy Content",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onCopyMessage(message.content) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GeminiGraphicPill(label: String, graphicId: String, onClick: () -> Unit) {
    InputChip(
        selected = false,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Launch,
                contentDescription = null,
                modifier = Modifier.size(10.dp)
            )
        },
        colors = InputChipDefaults.inputChipColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            labelColor = MaterialTheme.colorScheme.onTertiaryContainer
        ),
        border = null
    )
}

@Composable
fun GeminiTopicPill(label: String, onClick: () -> Unit) {
    InputChip(
        selected = false,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Launch,
                contentDescription = null,
                modifier = Modifier.size(10.dp)
            )
        },
        colors = InputChipDefaults.inputChipColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
        ),
        border = null
    )
}

@Composable
fun GeminiWarningAlert(warning: String) {
    val warnColor = MaterialTheme.colorScheme.tertiary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = warnColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 3.dp.toPx()
                )
            }
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = "Warning Indicator",
            tint = warnColor,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = warning,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
