package com.clinref.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.SafetyValidator
import com.clinref.app.domain.ai.StreamingManager

/**
 * High-precision, clinical 3-phase orchestration header embedded directly
 * inside the active Assistant Message card.
 *
 * Implements the deterministic sequence: Evidence -> Synthesis -> Verify
 * with real evidence counts, live topic chips, and an explicit draft safety tag.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatCardOrchestrationHeader(
    phase: StreamingManager.OrchestrationPhase,
    currentStatusText: String,
    liveSources: List<SafetyValidator.TopicRef>,
    sectionsReadCount: Int,
    isDraft: Boolean,
    isRevised: Boolean,
    onSourceClick: (SafetyValidator.TopicRef) -> Unit,
    modifier: Modifier = Modifier
) {
    val motionScheme = MaterialTheme.motionScheme

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: 3-Phase Stepper + Draft Safety Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 3-Phase Stepper
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Step 1: Evidence
                    PhaseStepItem(
                        stepNumber = "1",
                        label = "Evidence",
                        isCompleted = phase == StreamingManager.OrchestrationPhase.SYNTHESIS ||
                            phase == StreamingManager.OrchestrationPhase.VERIFICATION ||
                            phase == StreamingManager.OrchestrationPhase.COMPLETED,
                        isActive = phase == StreamingManager.OrchestrationPhase.EVIDENCE_RETRIEVAL
                    )

                    StepperSeparator()

                    // Step 2: Synthesis
                    PhaseStepItem(
                        stepNumber = "2",
                        label = "Synthesis",
                        isCompleted = phase == StreamingManager.OrchestrationPhase.VERIFICATION ||
                            phase == StreamingManager.OrchestrationPhase.COMPLETED,
                        isActive = phase == StreamingManager.OrchestrationPhase.SYNTHESIS
                    )

                    StepperSeparator()

                    // Step 3: Verify
                    PhaseStepItem(
                        stepNumber = "3",
                        label = "Verify",
                        isCompleted = phase == StreamingManager.OrchestrationPhase.COMPLETED,
                        isActive = phase == StreamingManager.OrchestrationPhase.VERIFICATION
                    )
                }

                // Draft / Revised Safety Badge
                AnimatedVisibility(
                    visible = isDraft,
                    enter = fadeIn(animationSpec = motionScheme.fastEffectsSpec()),
                    exit = fadeOut(animationSpec = motionScheme.fastEffectsSpec())
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(10.dp)
                            )
                            Text(
                                text = if (isRevised) "Revised after check" else "Draft: Verifying",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            // Row 2: Dynamic Clinician Activity Label
            val statusSummary = when (phase) {
                StreamingManager.OrchestrationPhase.EVIDENCE_RETRIEVAL -> {
                    currentStatusText.ifBlank { "Searching UpToDate medical library…" }
                }
                StreamingManager.OrchestrationPhase.SYNTHESIS -> {
                    if (liveSources.isNotEmpty()) {
                        val topicWord = if (liveSources.size == 1) "topic" else "topics"
                        val sectionWord = if (sectionsReadCount == 1) "section" else "sections"
                        val countSuffix = if (sectionsReadCount > 0) " · $sectionsReadCount $sectionWord" else ""
                        "Drafting point-of-care guidance from ${liveSources.size} $topicWord$countSuffix"
                    } else {
                        "Drafting point-of-care guidance…"
                    }
                }
                StreamingManager.OrchestrationPhase.VERIFICATION -> {
                    "Verifying clinical quantities and dosages against evidence…"
                }
                StreamingManager.OrchestrationPhase.COMPLETED -> {
                    "Guidance grounded and verified"
                }
                StreamingManager.OrchestrationPhase.ERROR -> {
                    "Execution halted"
                }
                StreamingManager.OrchestrationPhase.IDLE -> {
                    "Initializing clinical core…"
                }
            }

            AnimatedContent(
                targetState = statusSummary,
                transitionSpec = {
                    fadeIn(animationSpec = motionScheme.fastEffectsSpec()) togetherWith
                        fadeOut(animationSpec = motionScheme.fastEffectsSpec())
                },
                label = "orchestration_status_text"
            ) { targetText ->
                Text(
                    text = targetText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }

            // Row 3: Live Discovered Topic Chips (Tappable to consult directly)
            if (liveSources.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    liveSources.forEach { source ->
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            onClick = { onSourceClick(source) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Description,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(11.dp)
                                )
                                Text(
                                    text = source.topicTitle.ifBlank { "Topic #${source.topicId}" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            // Material 3 Expressive subtle linear wavy progress during active generation
            if (phase == StreamingManager.OrchestrationPhase.EVIDENCE_RETRIEVAL ||
                phase == StreamingManager.OrchestrationPhase.SYNTHESIS ||
                phase == StreamingManager.OrchestrationPhase.VERIFICATION
            ) {
                LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }
        }
    }
}

@Composable
private fun PhaseStepItem(
    stepNumber: String,
    label: String,
    isCompleted: Boolean,
    isActive: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when {
            isCompleted -> {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(13.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(9.dp)
                        )
                    }
                }
            }
            isActive -> {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            else -> {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(13.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stepNumber,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isActive || isCompleted) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = when {
                isCompleted -> MaterialTheme.colorScheme.primary
                isActive -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            }
        )
    }
}

@Composable
private fun StepperSeparator() {
    Text(
        text = "─",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}
