package com.clinref.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLengthTrim
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.ui.adaptive.AdaptiveTokens

private const val MAX_CHARS = 4000
private const val MAX_INPUT_LINES = 8

// Material 3 Component & Target Tokens (Accessibility compliant touch targets >= 48.dp)
private val BUTTON_INTERACTIVE_TARGET_SIZE = 48.dp // Accessibility compliant touch target (48.dp)
private val ACTION_ICON_SIZE = 20.dp               // Proportional action icon size
private val ACTION_ROW_SPACING = 8.dp              // M3 PaddingSmall

// Material 3 Container Spacing Tokens
private val CONTENT_VERTICAL_PADDING = 8.dp        // 8.dp vertical padding + 48.dp touch target = 64.dp height
private val CONTENT_START_PADDING = 24.dp          // M3 Grid Spacing (Large inset)
private val CONTENT_END_PADDING = 12.dp            // M3 Grid Spacing

private val TEXT_BOX_VERTICAL_PADDING = 8.dp       // Centered text offset
private val MAX_TEXT_BOX_HEIGHT = 200.dp

private val OUTER_HORIZONTAL_PADDING = 16.dp       // M3 Screen Edge Margin (PaddingMedium)
private val OUTER_TOP_PADDING = 12.dp              // M3 Grid Spacing
private val OUTER_BOTTOM_PADDING = 16.dp           // M3 Screen Edge Margin (PaddingMedium)

private val CHIP_HORIZONTAL_PADDING = 12.dp        // M3 AssistChip Standard Padding
private val CHIP_VERTICAL_PADDING = 4.dp           // M3 PaddingExtraSmall
private val CHIP_ICON_SIZE = 16.dp                 // M3 Small Icon
private val CHIP_BOTTOM_PADDING = 8.dp             // M3 PaddingSmall

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatInputBar(
    state: TextFieldState,
    onSendMessage: (String) -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    patientProfile: PatientProfile? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onVoiceInput: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val textCharSequence = state.text
    val isTextNotBlank = textCharSequence.isNotBlank()

    val containerShape = MaterialTheme.shapes.extraLarge
    val surfaceColor = MaterialTheme.colorScheme.surface
    val gradientBrush = remember(surfaceColor) {
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                surfaceColor.copy(alpha = 0.35f),
                surfaceColor.copy(alpha = 0.85f)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(brush = gradientBrush)
            .windowInsetsPadding(
                WindowInsets.safeDrawing
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                    .exclude(WindowInsets.ime)
            )
            .padding(horizontal = OUTER_HORIZONTAL_PADDING)
            .padding(top = OUTER_TOP_PADDING, bottom = OUTER_BOTTOM_PADDING),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = AdaptiveTokens.MaxChatInputWidth)
                .fillMaxWidth(),
            shape = containerShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
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
                            modifier = Modifier
                                .padding(start = 4.dp, bottom = CHIP_BOTTOM_PADDING)
                                .semantics(mergeDescendants = true) { }
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(ACTION_ROW_SPACING)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = BUTTON_INTERACTIVE_TARGET_SIZE, max = MAX_TEXT_BOX_HEIGHT)
                            .padding(vertical = TEXT_BOX_VERTICAL_PADDING),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        BasicTextField(
                            state = state,
                            inputTransformation = InputTransformation.maxLengthTrim(MAX_CHARS),
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
                                Box(contentAlignment = Alignment.CenterStart) {
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .onPreviewKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Enter) {
                                        if (keyEvent.isShiftPressed) {
                                            false
                                        } else {
                                            if (!isGenerating && isTextNotBlank) {
                                                onSendMessage(textCharSequence.toString())
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    } else {
                                        false
                                    }
                                }
                        )
                    }

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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionButtons(
    isGenerating: Boolean,
    isTextNotBlank: Boolean,
    onSendMessage: () -> Unit,
    onCancel: () -> Unit,
    onVoiceInput: (() -> Unit)? = null
) {
    val motionScheme = MaterialTheme.motionScheme

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ACTION_ROW_SPACING)
    ) {
        if (onVoiceInput != null) {
            IconButton(
                onClick = onVoiceInput,
                modifier = Modifier.size(BUTTON_INTERACTIVE_TARGET_SIZE)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = "Voice input",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(ACTION_ICON_SIZE)
                )
            }
        }

        AnimatedContent(
            targetState = isGenerating,
            transitionSpec = {
                fadeIn(animationSpec = motionScheme.fastEffectsSpec()) togetherWith
                    fadeOut(animationSpec = motionScheme.fastEffectsSpec())
            },
            label = "ActionButtonTransition"
        ) { generating ->
            if (generating) {
                FilledIconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(BUTTON_INTERACTIVE_TARGET_SIZE),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
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
                    modifier = Modifier.size(BUTTON_INTERACTIVE_TARGET_SIZE),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isTextNotBlank) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
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

