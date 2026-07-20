package com.clinref.app.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Healing
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.ui.chat.PromptTemplate

private const val MAX_CHARS = 4000

@Composable
fun ModernChatInput(
    textValue: String,
    onValueChange: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onCancel: () -> Unit,
    isGenerating: Boolean,
    patientProfile: PatientProfile? = null,
    clinicalTemplates: List<PromptTemplate> = emptyList(),
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 12.dp)
    ) {
        // Horizontal floating action bar hosting clinical prompt templates
        if (!isGenerating && clinicalTemplates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                clinicalTemplates.forEach { template ->
                    InputChip(
                        selected = false,
                        onClick = { onValueChange(template.prompt) },
                        label = { Text(template.title) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Healing,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = InputChipDefaults.inputChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Main Input capsule
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                // Attached patient profile summary card
                patientProfile?.let { profile ->
                    val profileSummary = buildString {
                        if (profile.age.isNotBlank()) append(profile.age)
                        if (profile.sex.isNotBlank()) {
                            if (isNotEmpty()) append(" \u2022 ")
                            append(profile.sex)
                        }
                        if (profile.conditions.isNotEmpty()) {
                            if (isNotEmpty()) append(" \u2022 ")
                            append(profile.conditions.first())
                            if (profile.conditions.size > 1) {
                                append(" (+${profile.conditions.size - 1})")
                            }
                        }
                    }
                    if (profileSummary.isNotBlank()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = "Bound Clinical Case",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = profileSummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }

                // Interactive text area
                BasicTextField(
                    value = textValue,
                    onValueChange = { if (it.length <= MAX_CHARS) onValueChange(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 24.dp, max = 140.dp),
                    textStyle = TextStyle(
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxLines = 8,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerTextField ->
                        Box {
                            if (textValue.isEmpty()) {
                                Text(
                                    text = "Verify diagnostics, safe dosages, guidelines...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Dual action controls
            if (isGenerating) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel generation Session",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (textValue.isNotBlank()) {
                            onSendMessage(textValue)
                        }
                    },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (textValue.isNotBlank()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    enabled = textValue.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Transmit Query",
                        tint = if (textValue.isNotBlank()) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
