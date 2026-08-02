package com.clinref.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.PatientProfile

private const val MAX_CHARS = 4000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiChatInput(
    textValue: String,
    onValueChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    patientProfile: PatientProfile? = null,
    modifier: Modifier = Modifier
) {
    val maxLines = 8
    val isMultiLine = textValue.contains('\n') || textValue.length > 40
    val containerShape = if (isMultiLine) RoundedCornerShape(36.dp) else CircleShape

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.10f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.30f)
                    )
                )
            )
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 20.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = containerShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = if (isMultiLine) 20.dp else 22.dp,
                        end = if (isMultiLine) 14.dp else 10.dp,
                        top = if (isMultiLine) 14.dp else 14.dp,
                        bottom = if (isMultiLine) 10.dp else 14.dp
                    )
            ) {
                patientProfile?.let { profile ->
                    val profileText = buildString {
                        if (profile.age.isNotBlank()) append(profile.age)
                        if (profile.sex.isNotBlank()) {
                            if (isNotEmpty()) append(" | ")
                            append(profile.sex)
                        }
                        if (profile.conditions.isNotEmpty()) {
                            if (isNotEmpty()) append(" | ")
                            append(profile.conditions.first())
                            if (profile.conditions.size > 1) append(" +${profile.conditions.size - 1}")
                        }
                    }
                    if (profileText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = profileText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = if (isMultiLine) Alignment.Top else Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 32.dp, max = 180.dp),
                        contentAlignment = if (isMultiLine) Alignment.TopStart else Alignment.CenterStart
                    ) {
                        BasicTextField(
                            value = textValue,
                            onValueChange = { if (it.length <= MAX_CHARS) onValueChange(it) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = false,
                            maxLines = maxLines,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = if (isMultiLine) Alignment.TopStart else Alignment.CenterStart) {
                                    if (textValue.isEmpty()) {
                                        Text(
                                            text = "Ask a clinical question...",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    if (!isMultiLine) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Voice input",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )

                            if (isGenerating) {
                                FilledIconButton(
                                    onClick = onCancel,
                                    modifier = Modifier.size(40.dp),
                                    shape = CircleShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Cancel generation",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else {
                                FilledIconButton(
                                    onClick = {
                                        if (textValue.isNotBlank()) {
                                            onSendMessage(textValue)
                                        }
                                    },
                                    modifier = Modifier.size(40.dp),
                                    shape = CircleShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = if (textValue.isNotBlank()) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHighest
                                        },
                                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                    ),
                                    enabled = textValue.isNotBlank()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ArrowUpward,
                                        contentDescription = "Send message",
                                        tint = if (textValue.isNotBlank()) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                if (isMultiLine) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End
                    ) {
                        Spacer(modifier = Modifier.weight(1f))

                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice input",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(24.dp)
                                .padding(end = 2.dp)
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        if (isGenerating) {
                            FilledIconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel generation",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    if (textValue.isNotBlank()) {
                                        onSendMessage(textValue)
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (textValue.isNotBlank()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                ),
                                enabled = textValue.isNotBlank()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Send message",
                                    tint = if (textValue.isNotBlank()) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    },
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
