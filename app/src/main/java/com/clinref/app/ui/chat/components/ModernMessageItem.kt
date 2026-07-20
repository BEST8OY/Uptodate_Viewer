package com.clinref.app.ui.chat.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.ui.chat.MessageUiModel
import com.mikepenz.markdown.m3.Markdown
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ModernMessageItem(
    message: MessageUiModel,
    onCopyMessage: (String) -> Unit = {},
    onNavigateToContent: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val isCancelled = message.role == "cancelled"
    var showMenu by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            showMenu = true
                        }
                    )
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF0D9488), Color(0xFF0284C7), Color(0xFF4F46E5))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "ClinRef Spark",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                showMenu = true
                            }
                        )
                ) {
                    Text(
                        text = "Clinical Engine Output",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    when {
                        message.isError -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                                    .padding(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = "System Block",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = message.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        isCancelled -> {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        else -> {
                            Markdown(
                                content = message.content,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (message.warnings.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        message.warnings.forEach { warning ->
                            ModernWarningAlert(warning = warning)
                        }
                    }

                    if (message.citations.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "CITATIONS & TOPICS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(message.citations) { citation ->
                                ModernCitationChip(
                                    citation = citation,
                                    onClick = { onNavigateToContent(citation.topicId) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (message.showTimestamp) {
            Text(
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(
                    horizontal = if (isUser) 12.dp else 52.dp,
                    vertical = 4.dp
                )
            )
        }

        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Copy workspace content") },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onCopyMessage(message.content)
                }
            )
        }
    }
}

@Composable
fun ModernCitationChip(
    citation: SafetyValidator.Citation,
    onClick: () -> Unit
) {
    InputChip(
        selected = false,
        onClick = onClick,
        label = {
            Text(
                text = "${citation.topicTitle} \u2022 ${citation.sectionTitle}",
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Launch,
                contentDescription = null,
                modifier = Modifier.size(12.dp)
            )
        },
        colors = InputChipDefaults.inputChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            labelColor = MaterialTheme.colorScheme.onSurface
        ),
        border = InputChipDefaults.inputChipBorder(
            selected = false,
            enabled = true,
            borderColor = MaterialTheme.colorScheme.outlineVariant
        )
    )
}

@Composable
fun ModernWarningAlert(warning: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = Color(0xFFF59E0B),
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
            contentDescription = "Clinical safety check warning",
            tint = Color(0xFFF59E0B),
            modifier = Modifier
                .size(18.dp)
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
