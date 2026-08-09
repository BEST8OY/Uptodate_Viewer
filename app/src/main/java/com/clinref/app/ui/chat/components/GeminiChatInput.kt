package com.clinref.app.ui.chat.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.PatientProfile

private const val MAX_CHARS = 4000

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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

    // Material 3 Expressive spring shape morphing:
    // Single line: CircleShape / extraExtraLarge (48.dp) -> Expanded: extraLargeIncreased (32.dp)
    val cornerRadius by animateDpAsState(
        targetValue = if (isMultiLine) 48.dp else 48.dp, // 32.dp = extraLargeIncreased, 48.dp = extraExtraLarge
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "m3ExpressiveShapeMorph"
    )
    val containerShape = if (!isMultiLine && cornerRadius >= 46.dp) CircleShape else RoundedCornerShape(cornerRadius)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.75f)
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
                        start = 20.dp,
                        end = 20.dp,
                        top = 14.dp,
                        bottom = 14.dp
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
                                .padding(bottom = 6.dp)
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
                    verticalAlignment = Alignment.Bottom
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 32.dp, max = 180.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        TextField(
                            value = textValue,
                            onValueChange = { if (it.length <= MAX_CHARS) onValueChange(it) },
                            placeholder = {
                                Text(
                                    text = "Ask a clinical question...",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            singleLine = false,
                            maxLines = maxLines,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        modifier = Modifier.padding(bottom = 4.dp),
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
        }
    }
}
