package com.clinref.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.ui.chat.ChatDimens

private const val MAX_CHARS = 4000
private const val COUNTER_WARN_THRESHOLD = MAX_CHARS - 100

/**
 * The composer. State is hoisted ([value]/[onValueChange]) so the empty-state's example
 * prompts (and, in future, edited/retried messages) can populate the field from outside.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    patientProfile: PatientProfile? = null,
    onPatientProfileClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        patientProfile?.let { profile ->
            val profileText = buildString {
                if (profile.age.isNotBlank()) append(profile.age)
                if (profile.sex.isNotBlank()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(profile.sex)
                }
                if (profile.conditions.isNotEmpty()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(profile.conditions.first())
                    if (profile.conditions.size > 1) append(" +${profile.conditions.size - 1}")
                }
            }
            if (profileText.isNotBlank()) {
                AssistChip(
                    onClick = onPatientProfileClick,
                    label = {
                        Text(
                            text = profileText,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    border = null,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(ChatDimens.InputCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)) {
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = "Ask a clinical question\u2026",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { if (it.length <= MAX_CHARS) onValueChange(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp, max = 160.dp),
                        textStyle = TextStyle(
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        maxLines = 8,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Reserved for future attachments; disabled for now so it doesn't
                    // promise functionality the app doesn't have yet.
                    IconButton(onClick = {}, enabled = false, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.AttachFile,
                            contentDescription = "Attach (coming soon)",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    if (value.length >= COUNTER_WARN_THRESHOLD) {
                        Text(
                            text = "${value.length}/$MAX_CHARS",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (value.length >= MAX_CHARS) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }

                    SendButton(
                        isGenerating = isGenerating,
                        enabled = value.isNotBlank() || isGenerating,
                        onClick = { if (isGenerating) onCancel() else onSend() }
                    )
                }
            }
        }

        DisclaimerBanner()
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SendButton(
    isGenerating: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val corner by animateDpAsState(
        targetValue = if (isGenerating) ChatDimens.SendButtonCornerRadiusActive else ChatDimens.SendButtonCornerRadiusIdle,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "send_button_corner"
    )
    val containerColor by animateColorAsState(
        targetValue = when {
            isGenerating -> MaterialTheme.colorScheme.errorContainer
            enabled -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        label = "send_button_container_color"
    )
    val contentColor = when {
        isGenerating -> MaterialTheme.colorScheme.onErrorContainer
        enabled -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Surface(
        onClick = onClick,
        enabled = enabled || isGenerating,
        shape = RoundedCornerShape(corner),
        color = containerColor,
        modifier = Modifier.size(ChatDimens.SendButtonSize)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = isGenerating,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "send_button_icon"
            ) { generating ->
                Icon(
                    imageVector = if (generating) Icons.Default.Stop else Icons.Default.ArrowUpward,
                    contentDescription = if (generating) "Stop generating" else "Send message",
                    tint = contentColor,
                    modifier = Modifier.size(if (generating) 18.dp else 20.dp)
                )
            }
        }
    }
}
