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
private const val MAX_INPUT_LINES = 8
private const val SINGLE_LINE_CHAR_THRESHOLD = 35

// Material 3 Component & Target Tokens
private val BUTTON_TOUCH_TARGET_SIZE = 48.dp // M3 MinTouchTargetSize
private val ACTION_ICON_SIZE = 24.dp         // M3 Standard Icon Size
private val ACTION_ROW_SPACING = 8.dp        // M3 PaddingSmall

private val MIC_ICON_PADDING = (BUTTON_TOUCH_TARGET_SIZE - ACTION_ICON_SIZE) / 2 // 12.dp

// Material 3 Container Spacing Tokens (Strict 4dp/8dp Grid)
private val CONTENT_VERTICAL_PADDING = 8.dp  // M3 PaddingSmall
private val CONTENT_START_PADDING = 20.dp    // M3 Extra Large Inset Token
private val CONTENT_END_PADDING = 12.dp      // M3 PaddingMedium

private val TEXT_BOX_END_PADDING = 8.dp      // M3 PaddingSmall
private val TEXT_BOX_VERTICAL_PADDING = 4.dp // M3 PaddingExtraSmall
private val MAX_TEXT_BOX_HEIGHT = 200.dp

private val OUTER_HORIZONTAL_PADDING = 16.dp // M3 Screen Edge Margin
private val OUTER_TOP_PADDING = 8.dp         // M3 PaddingSmall
private val OUTER_BOTTOM_PADDING = 16.dp     // M3 PaddingLarge

// Material 3 Surface & Chip Tokens (No drop shadows)
private val SURFACE_SHADOW_ELEVATION = 0.dp  // Clean flat surface
private val SURFACE_TONAL_ELEVATION = 0.dp   // Container color tinting only

private val CHIP_HORIZONTAL_PADDING = 12.dp  // M3 AssistChip Standard Padding
private val CHIP_VERTICAL_PADDING = 6.dp     // M3 AssistChip Standard Padding
private val CHIP_ICON_SIZE = 16.dp           // M3 Small Icon
private val CHIP_BOTTOM_PADDING = 8.dp       // M3 PaddingSmall

// Material 3 Expressive Shape Token: extra-large-increased = 32.dp
// Single-line container height = 8dp (top) + 48dp (action target) + 8dp (bottom) = 64dp.
// Because 32.dp is exactly half of 64dp (64dp / 2 = 32dp), it forms a 100% mathematically exact pill capsule on single-line,
// while remaining strictly 32.dp on multi-line expansion with ZERO corner radius drift or shape popping.
private val CONTAINER_CORNER_RADIUS = 32.dp

@Composable
fun GeminiChatInput(
    state: TextFieldState,
    onSendMessage: (String) -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    patientProfile: PatientProfile? = null,
    modifier: Modifier = Modifier
) {
    val textCharSequence = state.text
    val isTextNotBlank = textCharSequence.isNotBlank()

    val isMultiLine = textCharSequence.contains('\n') || textCharSequence.length > SINGLE_LINE_CHAR_THRESHOLD

    // Single-line container height = 8dp (top) + 48dp (row) + 8dp (bottom) = 64dp.
    // 32dp corner radius (64dp / 2 = 32dp) forms a 100% mathematically exact pill capsule on single-line,
    // while remaining strictly 32dp on multi-line expansion with ZERO corner radius drift.
    val containerShape = RoundedCornerShape(CONTAINER_CORNER_RADIUS)

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
            .padding(horizontal = OUTER_HORIZONTAL_PADDING)
            .padding(top = OUTER_TOP_PADDING, bottom = OUTER_BOTTOM_PADDING)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
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
                        Box(
                            modifier = Modifier
                                .padding(start = 4.dp, bottom = CHIP_BOTTOM_PADDING)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = CHIP_HORIZONTAL_PADDING, vertical = CHIP_VERTICAL_PADDING)
                        ) {
                            Row(
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
                            lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = MAX_INPUT_LINES),
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

                    Box(
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        ActionButtons(
                            isGenerating = isGenerating,
                            isTextNotBlank = isTextNotBlank,
                            onSendMessage = { if (isTextNotBlank) onSendMessage(textCharSequence.toString()) },
                            onCancel = onCancel
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
    onCancel: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ACTION_ROW_SPACING)
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Voice input",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(BUTTON_TOUCH_TARGET_SIZE)
                .clip(CircleShape)
                .padding(MIC_ICON_PADDING)
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
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(ACTION_ICON_SIZE)
                )
            }
        }
    }
}



