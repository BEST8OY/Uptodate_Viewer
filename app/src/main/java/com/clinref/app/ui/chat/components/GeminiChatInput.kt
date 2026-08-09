package com.clinref.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.PatientProfile

private const val MAX_CHARS = 4000
private const val MAX_INPUT_LINES = 8

// Material 3 Component & Target Tokens
private val BUTTON_TOUCH_TARGET_SIZE = 48.dp // M3 MinTouchTargetSize
private val ACTION_ICON_SIZE = 18.dp         // Sleek, refined action icon size
private val ACTION_ROW_SPACING = 8.dp        // M3 PaddingSmall

// Material 3 Container Spacing Tokens (Official M3 Scale: 4dp, 8dp, 12dp, 16dp, 24dp, 32dp)
private val CONTENT_VERTICAL_PADDING = 12.dp // M3 Grid Spacing
private val CONTENT_START_PADDING = 24.dp    // M3 Grid Spacing (Large inset)
private val CONTENT_END_PADDING = 12.dp      // M3 Grid Spacing

private val TEXT_BOX_END_PADDING = 8.dp      // M3 PaddingSmall
private val TEXT_BOX_VERTICAL_PADDING = 8.dp // M3 PaddingSmall (Corrected from non-standard 6.dp)
private val MAX_TEXT_BOX_HEIGHT = 200.dp

private val OUTER_HORIZONTAL_PADDING = 16.dp // M3 Screen Edge Margin (PaddingMedium)
private val OUTER_TOP_PADDING = 12.dp        // M3 Grid Spacing
private val OUTER_BOTTOM_PADDING = 16.dp     // M3 Screen Edge Margin (PaddingMedium)

// Material 3 Surface & Chip Tokens (No drop shadows)
private val SURFACE_SHADOW_ELEVATION = 0.dp  // Clean flat surface
private val SURFACE_TONAL_ELEVATION = 0.dp   // Container color tinting only

private val CHIP_HORIZONTAL_PADDING = 12.dp  // M3 AssistChip Standard Padding
private val CHIP_VERTICAL_PADDING = 4.dp     // M3 PaddingExtraSmall (Corrected from non-standard 6.dp)
private val CHIP_ICON_SIZE = 16.dp           // M3 Small Icon
private val CHIP_BOTTOM_PADDING = 8.dp       // M3 PaddingSmall

// Material 3 Expressive Shape Token: 36.dp
// Single-line container height = 12dp (top) + 48dp (action target) + 12dp (bottom) = 72dp.
// 36.dp is exactly half of 72dp (72dp / 2 = 36dp), forming a 100% mathematically exact pill capsule on single-line.
private val CONTAINER_CORNER_RADIUS = 36.dp

@Composable
fun GeminiChatInput(
    state: TextFieldState,
    onSendMessage: (String) -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    patientProfile: PatientProfile? = null,
    onVoiceInput: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val textCharSequence = state.text
    val isTextNotBlank = textCharSequence.isNotBlank()
    val isMultiLine = textCharSequence.contains('\n')

    val containerShape = RoundedCornerShape(CONTAINER_CORNER_RADIUS)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    )
                )
            )
            .padding(horizontal = OUTER_HORIZONTAL_PADDING)
            .padding(top = OUTER_TOP_PADDING, bottom = OUTER_BOTTOM_PADDING)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(),
            shape = containerShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = SURFACE_TONAL_ELEVATION,
            shadowElevation = SURFACE_SHADOW_ELEVATION
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = CONTENT_START_PADDING,
                        end = CONTENT_END_PADDING,
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
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.padding(start = 4.dp, bottom = CHIP_BOTTOM_PADDING)
                        ) {
                            Row(
                                modifier = Modifier.padding(
                                    horizontal = CHIP_HORIZONTAL_PADDING,
                                    vertical = CHIP_VERTICAL_PADDING
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(ACTION_ROW_SPACING)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(CHIP_ICON_SIZE),
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

                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                end = if (isMultiLine) 0.dp else (BUTTON_TOUCH_TARGET_SIZE * 2 + ACTION_ROW_SPACING + TEXT_BOX_END_PADDING),
                                bottom = if (isMultiLine) (BUTTON_TOUCH_TARGET_SIZE + 8.dp) else 0.dp,
                                top = if (isMultiLine) TEXT_BOX_VERTICAL_PADDING else 0.dp
                            )
                            .heightIn(
                                min = if (isMultiLine) 60.dp else BUTTON_TOUCH_TARGET_SIZE,
                                max = MAX_TEXT_BOX_HEIGHT
                            ),
                        contentAlignment = if (isMultiLine) Alignment.TopStart else Alignment.CenterStart
                    ) {
                        BasicTextField(
                            state = state,
                            inputTransformation = InputTransformation.maxLength(MAX_CHARS),
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = MAX_INPUT_LINES),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Default
                            ),
                            decorator = { innerTextField ->
                                Box(contentAlignment = if (isMultiLine) Alignment.TopStart else Alignment.CenterStart) {
                                    if (textCharSequence.isEmpty()) {
                                        Text(
                                            text = "Ask a clinical question...",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                        )
                                    }
                                    innerTextField()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Box(
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        ActionButtons(
                            isGenerating = isGenerating,
                            isTextNotBlank = isTextNotBlank,
                            onSendMessage = { if (isTextNotBlank) onSendMessage(textCharSequence.toString()) },
                            onCancel = onCancel,
                            onVoiceInput = onVoiceInput
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    isGenerating: Boolean,
    isTextNotBlank: Boolean,
    onSendMessage: () -> Unit,
    onCancel: () -> Unit,
    onVoiceInput: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ACTION_ROW_SPACING)
    ) {
        IconButton(
            onClick = { onVoiceInput?.invoke() },
            enabled = onVoiceInput != null,
            modifier = Modifier.size(BUTTON_TOUCH_TARGET_SIZE)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = "Voice input",
                tint = if (onVoiceInput != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(ACTION_ICON_SIZE)
            )
        }

        AnimatedContent(
            targetState = isGenerating,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            label = "ActionButtonTransition"
        ) { generating ->
            if (generating) {
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
                    onClick = onSendMessage,
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
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                        },
                        modifier = Modifier.size(ACTION_ICON_SIZE)
                    )
                }
            }
        }
    }
}
