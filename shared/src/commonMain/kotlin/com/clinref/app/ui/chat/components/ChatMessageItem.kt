package com.clinref.app.ui.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.domain.ai.TopicRef
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.chat.MessageUiModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.model.rememberMarkdownState
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun ChatMessageItem(
    message: MessageUiModel,
    isStreaming: Boolean = message.id == "streaming",
    orchestrationPhase: StreamingManager.OrchestrationPhase = StreamingManager.OrchestrationPhase.COMPLETED,
    currentStatusText: String = "",
    liveSources: List<TopicRef> = emptyList(),
    sectionsReadCount: Int = 0,
    isDraft: Boolean = false,
    isRevised: Boolean = false,
    isTurnSuccessful: Boolean = true,
    onSourceClick: ((TopicRef) -> Unit)? = null,
    onCopyMessage: (String) -> Unit = {},
    onEditMessage: ((MessageUiModel) -> Unit)? = null,
    onRetryTurn: ((String) -> Unit)? = null,
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
            val bubbleColor = MaterialTheme.colorScheme.surfaceContainerHighest
            val bubbleTextColor = MaterialTheme.colorScheme.onSurface

            var isExpanded by remember(message.id) { mutableStateOf(false) }
            var hasOverflow by remember(message.id) { mutableStateOf(false) }
            var showContextMenu by remember(message.id) { mutableStateOf(false) }

            Box {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = bubbleColor,
                    modifier = Modifier
                        .widthIn(min = 48.dp, max = AdaptiveTokens.MaxDialogWidth)
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                        .clip(MaterialTheme.shapes.large)
                        .combinedClickable(
                            onClick = {
                                if (hasOverflow || isExpanded) {
                                    isExpanded = !isExpanded
                                }
                            },
                            onLongClick = {
                                showContextMenu = true
                            }
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = bubbleTextColor,
                            maxLines = if (isExpanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { textLayoutResult ->
                                if (!isExpanded) {
                                    hasOverflow = textLayoutResult.hasVisualOverflow || textLayoutResult.lineCount > 5
                                }
                            }
                        )

                        if (hasOverflow || isExpanded) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                Text(
                                    text = if (isExpanded) "Show less" else "Show more",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = bubbleTextColor.copy(alpha = 0.85f)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = bubbleTextColor.copy(alpha = 0.85f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                val itemCount = if (!isTurnSuccessful) 3 else 2
                DropdownMenuPopup(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false }
                ) {
                    DropdownMenuGroup(
                        shapes = MenuDefaults.groupShape(0, 1),
                        containerColor = MenuDefaults.groupStandardContainerColor
                    ) {
                        DropdownMenuItem(
                            onClick = {
                                showContextMenu = false
                                onCopyMessage(message.content)
                            },
                            text = { Text("Copy", style = MaterialTheme.typography.bodyMedium) },
                            shape = MenuDefaults.leadingItemShape,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                        DropdownMenuItem(
                            onClick = {
                                showContextMenu = false
                                onEditMessage?.invoke(message)
                            },
                            text = { Text("Edit", style = MaterialTheme.typography.bodyMedium) },
                            shape = if (itemCount == 2) MenuDefaults.trailingItemShape else MenuDefaults.middleItemShape,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                        if (!isTurnSuccessful) {
                            DropdownMenuItem(
                                onClick = {
                                    showContextMenu = false
                                    onRetryTurn?.invoke(message.id)
                                },
                                text = { Text("Retry", style = MaterialTheme.typography.bodyMedium) },
                                shape = MenuDefaults.trailingItemShape,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        } else {
            val deduplicatedTextToolbar = rememberDeduplicatedTextToolbar()
            CompositionLocalProvider(LocalTextToolbar provides deduplicatedTextToolbar) {
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

                if (message.isError && !isCancelled) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Error,
                                contentDescription = "Internal Exception",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            SelectionContainer(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                } else if (isCancelled) {
                    SelectionContainer {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                            retainState = true,
                            immediate = !isStreaming
                        )

                        val markdownColors = markdownColor(
                            codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                            inlineCodeBackground = MaterialTheme.colorScheme.surfaceContainerHighest,
                            dividerColor = MaterialTheme.colorScheme.outlineVariant,
                            tableBackground = Color.Transparent
                        )
                        val markdownTypography = clinrefMarkdownTypography()
                        val markdownDimens = clinrefMarkdownDimens()
                        val markdownComponents = rememberClinrefMarkdownComponents()

                        SelectionContainer {
                            CompositionLocalProvider(LocalUriHandler provides customUriHandler) {
                                Markdown(
                                    markdownState = markdownState,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = markdownColors,
                                    typography = markdownTypography,
                                    dimens = markdownDimens,
                                    components = markdownComponents
                                )
                            }
                        }
                    }
                }

                if (!message.isError && !isCancelled && !isStreaming) {
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        IconButton(
                            onClick = { onCopyMessage(message.content) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy message content",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

