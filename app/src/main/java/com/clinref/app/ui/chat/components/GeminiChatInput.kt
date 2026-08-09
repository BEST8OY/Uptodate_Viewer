package com.clinref.app.ui.chat.components

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.PatientProfile

private const val MAX_CHARS = 4000
private val BUTTON_TOUCH_TARGET_SIZE = 48.dp
private val ACTION_ICON_SIZE = 24.dp
private val CONTENT_VERTICAL_PADDING = 12.dp

@Composable
fun GeminiChatInput(
    state: TextFieldState,
    onSendMessage: (String) -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    patientProfile: PatientProfile? = null,
    modifier: Modifier = Modifier
) {
    val maxLines = 8
    val textCharSequence = state.text
    val isTextNotBlank = textCharSequence.isNotBlank()

    // 36.dp corner radius (M3 Expressive ExtraLarge token):
    // On single-line (container height ~72dp), 36.dp guarantees a 100% full plush pill capsule at any font scale.
    // On multi-line (container height expands to 200dp), 36.dp remains constant, providing a rich, bulky M3 container with ZERO rounding drift.
    val containerShape = RoundedCornerShape(36.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
                    )
                )
            )
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = 16.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = containerShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 22.dp,
                        end = 12.dp,
                        top = CONTENT_VERTICAL_PADDING,
                        bottom = CONTENT_VERTICAL_PADDING
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
                                .padding(start = 4.dp, bottom = 10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 12.dp, vertical = 5.dp)
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
                            .padding(end = 10.dp, top = CONTENT_VERTICAL_PADDING, bottom = CONTENT_VERTICAL_PADDING)
                            .heightIn(min = BUTTON_TOUCH_TARGET_SIZE, max = 200.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (textCharSequence.isEmpty()) {
                            Text(
                                text = "Ask a clinical question...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                            )
                        }
                        BasicTextField(
                            state = state,
                            inputTransformation = InputTransformation.maxLength(MAX_CHARS),
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = maxLines),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Voice input",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(BUTTON_TOUCH_TARGET_SIZE)
                                .clip(CircleShape)
                                .padding(12.dp)
                        )

                        if (isGenerating) {
                            FilledIconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(BUTTON_TOUCH_TARGET_SIZE),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel generation",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(ACTION_ICON_SIZE)
                                )
                            }
                        } else {
                            FilledIconButton(
                                onClick = {
                                    if (isTextNotBlank) {
                                        onSendMessage(textCharSequence.toString())
                                    }
                                },
                                modifier = Modifier.size(BUTTON_TOUCH_TARGET_SIZE),
                                shape = CircleShape,
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = if (isTextNotBlank) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                ),
                                enabled = isTextNotBlank
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Send message",
                                    tint = if (isTextNotBlank) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    },
                                    modifier = Modifier.size(ACTION_ICON_SIZE)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}



