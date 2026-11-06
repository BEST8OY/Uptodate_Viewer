package com.clinref.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.chat.MessageUiModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatMessageItem(
    message: MessageUiModel,
    isStreaming: Boolean = message.id == "streaming",
    orchestrationPhase: StreamingManager.OrchestrationPhase = StreamingManager.OrchestrationPhase.COMPLETED,
    currentStatusText: String = "",
    liveSources: List<SafetyValidator.TopicRef> = emptyList(),
    sectionsReadCount: Int = 0,
    isDraft: Boolean = false,
    isRevised: Boolean = false,
    onSourceClick: ((SafetyValidator.TopicRef) -> Unit)? = null,
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
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(min = 48.dp, max = 560.dp)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = AdaptiveTokens.MaxChatAssistantWidth)
                    .fillMaxWidth()
            ) {
                if (isStreaming) {
                    ChatCardOrchestrationHeader(
                        phase = orchestrationPhase,
                        currentStatusText = currentStatusText,
                        liveSources = liveSources,
                        sectionsReadCount = sectionsReadCount,
                        isDraft = isDraft,
                        isRevised = isRevised,
                        onSourceClick = { ref -> onSourceClick?.invoke(ref) }
                    )
                }

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
                    if (message.content.isNotBlank()) {
                        val currentUriHandler = LocalUriHandler.current
                        val customUriHandler = remember(currentUriHandler, onNavigateToContent, onGraphicSelected) {
                            object : UriHandler {
                                override fun openUri(uri: String) {
                                    when {
                                        uri.startsWith("topic:") -> {
                                            val topicId = uri.removePrefix("topic:")
                                            onNavigateToContent(topicId, null)
                                        }
                                        uri.startsWith("graphic:") -> {
                                            val graphicId = uri.removePrefix("graphic:").removePrefix("Graphic-")
                                            onGraphicSelected(graphicId)
                                        }
                                        else -> {
                                            try {
                                                currentUriHandler.openUri(uri)
                                            } catch (_: Exception) {
                                                // Ignore malformed or unsupported URI schemas
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val markdownState = rememberMarkdownState(
                            content = message.content,
                            retainState = true
                        )

                        CompositionLocalProvider(LocalUriHandler provides customUriHandler) {
                            Markdown(
                                markdownState = markdownState,
                                modifier = Modifier.fillMaxWidth(),
                                colors = markdownColor(
                                    codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    dividerColor = MaterialTheme.colorScheme.outlineVariant,
                                    tableBackground = Color.Transparent
                                ),
                                typography = clinrefMarkdownTypography(),
                                dimens = clinrefMarkdownDimens(),
                                components = clinrefMarkdownComponents()
                            )
                        }
                    }
                }

                if (message.warnings.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    message.warnings.forEach { warning ->
                        ChatWarningAlert(warning = warning)
                    }
                }

                if (!message.isError && !isCancelled && !isStreaming) {
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(30.dp)
                                    .clickable { onCopyMessage(message.content) }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Content",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GraphicPill(label: String, graphicId: String, onClick: () -> Unit) {
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
                imageVector = Icons.AutoMirrored.Filled.Launch,
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
fun TopicPill(label: String, onClick: () -> Unit) {
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
                imageVector = Icons.AutoMirrored.Filled.Launch,
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
fun ChatWarningAlert(warning: String) {
    val warnColor = MaterialTheme.colorScheme.error
    val annotatedText = remember(warning) {
        val builder = AnnotatedString.Builder()
        val parts = warning.split("**")
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                builder.append(part)
                builder.pop()
            } else {
                builder.append(part)
            }
        }
        builder.toAnnotatedString()
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, warnColor.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .drawBehind {
                    val strokeWidth = 3.5.dp.toPx()
                    val halfStroke = strokeWidth / 2f
                    drawLine(
                        color = warnColor,
                        start = Offset(halfStroke, 0f),
                        end = Offset(halfStroke, size.height),
                        strokeWidth = strokeWidth
                    )
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Safety Warning",
                tint = warnColor,
                modifier = Modifier
                    .size(16.dp)
                    .padding(top = 2.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = annotatedText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

