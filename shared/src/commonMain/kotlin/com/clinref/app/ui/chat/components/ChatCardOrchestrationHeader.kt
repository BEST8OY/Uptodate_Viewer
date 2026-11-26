package com.clinref.app.ui.chat.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.domain.ai.TopicRef
import com.clinref.app.ui.common.nonBouncyExitSpring

/**
 * Calm, clinically structured 2-phase orchestration header embedded above the live
 * streaming assistant response.
 *
 * Behavior:
 * - Phase 1 (Evidence): Shows active retrieval status + horizontally scrollable discovered
 *   source pills (zero vertical layout thrashing).
 * - Phase 2 (Synthesis): Auto-compacts once text begins streaming so the clinician's focus
 *   stays on the live answer, while remaining 1-tap expandable to inspect step trace & sources.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatCardOrchestrationHeader(
    streamingState: StreamingManager.StreamingUiState,
    onSourceClick: (TopicRef) -> Unit,
    modifier: Modifier = Modifier
) {
    val motionScheme = MaterialTheme.motionScheme
    val phase = streamingState.phase
    val sources = streamingState.discoveredSources
    val steps = streamingState.steps

    // User can manually expand/collapse the detailed step trace at any point during the turn
    var isDetailsExpanded by remember(streamingState.turnId) { mutableStateOf(false) }

    // Automatically compact the source strip once synthesis text is actively streaming
    // unless the clinician explicitly opened details.
    val isCompactSynthesis = phase == StreamingManager.OrchestrationPhase.SYNTHESIS &&
        streamingState.hasStartedStreamingText &&
        !isDetailsExpanded

    val hasExpandableDetails = steps.isNotEmpty() || sources.isNotEmpty()

    val isSynthesisFailed = phase == StreamingManager.OrchestrationPhase.ERROR &&
        steps.any { it.id == "step_synthesis" && it.status == StreamingManager.StepStatus.FAILED }
    val isEvidenceFailed = phase == StreamingManager.OrchestrationPhase.ERROR && !isSynthesisFailed

    val chevronRotation by animateFloatAsState(
        targetValue = if (isDetailsExpanded) 180f else 0f,
        animationSpec = motionScheme.fastSpatialSpec(),
        label = "orchestration_chevron"
    )

    val evidenceBadgeText = remember(
        streamingState.topicsConsultedCount,
        streamingState.sectionsReadCount,
        streamingState.tablesReadCount
    ) {
        buildList {
            if (streamingState.topicsConsultedCount > 0) {
                val t = streamingState.topicsConsultedCount
                add("$t ${if (t == 1) "topic" else "topics"}")
            }
            if (streamingState.sectionsReadCount > 0) {
                val s = streamingState.sectionsReadCount
                add("$s ${if (s == 1) "sec" else "secs"}")
            }
            if (streamingState.tablesReadCount > 0) {
                val tbl = streamingState.tablesReadCount
                add("$tbl ${if (tbl == 1) "table" else "tables"}")
            }
        }.joinToString(" · ")
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .animateContentSize(
                animationSpec = nonBouncyExitSpring()
            ),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            width = 1.dp,
            color = if (phase == StreamingManager.OrchestrationPhase.ERROR) {
                MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            }
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: 2-phase stepper + live evidence metric pill + expand toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .then(
                        if (hasExpandableDetails) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = if (isDetailsExpanded) "Collapse retrieval trace" else "Expand retrieval trace"
                                ) {
                                    isDetailsExpanded = !isDetailsExpanded
                                }
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    PhaseStepBadge(
                        stepNumber = "1",
                        label = "Evidence",
                        isCompleted = phase == StreamingManager.OrchestrationPhase.SYNTHESIS ||
                            phase == StreamingManager.OrchestrationPhase.COMPLETED,
                        isActive = phase == StreamingManager.OrchestrationPhase.EVIDENCE_RETRIEVAL ||
                            phase == StreamingManager.OrchestrationPhase.IDLE,
                        isError = isEvidenceFailed
                    )

                    Text(
                        text = "—",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    PhaseStepBadge(
                        stepNumber = "2",
                        label = "Synthesis",
                        isCompleted = phase == StreamingManager.OrchestrationPhase.COMPLETED,
                        isActive = phase == StreamingManager.OrchestrationPhase.SYNTHESIS,
                        isError = isSynthesisFailed
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (evidenceBadgeText.isNotEmpty()) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                        ) {
                            Text(
                                text = evidenceBadgeText,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (hasExpandableDetails) {
                        Icon(
                            imageVector = Icons.Default.ExpandMore,
                            contentDescription = if (isDetailsExpanded) "Collapse details" else "Expand details",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(chevronRotation)
                        )
                    }
                }
            }

            // Live activity subtitle (hidden only when completely compact & streaming smoothly)
            AnimatedVisibility(
                visible = !isCompactSynthesis,
                enter = fadeIn(motionScheme.fastEffectsSpec()) + expandVertically(motionScheme.fastSpatialSpec()),
                exit = fadeOut(motionScheme.fastEffectsSpec()) + shrinkVertically(
                    animationSpec = nonBouncyExitSpring()
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedContent(
                        targetState = streamingState.statusText,
                        transitionSpec = {
                            fadeIn(motionScheme.fastEffectsSpec()) togetherWith
                                fadeOut(motionScheme.fastEffectsSpec())
                        },
                        label = "orchestration_status"
                    ) { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }

                    // Single-row horizontally scrollable discovered sources (zero vertical reflow jumps)
                    if (sources.isNotEmpty()) {
                        DiscoveredSourcesRail(
                            sources = sources,
                            onSourceClick = onSourceClick
                        )
                    }
                }
            }

            // Expandable Step Execution Trace
            AnimatedVisibility(
                visible = isDetailsExpanded && steps.isNotEmpty(),
                enter = fadeIn(motionScheme.fastEffectsSpec()) + expandVertically(motionScheme.fastSpatialSpec()),
                exit = fadeOut(motionScheme.fastEffectsSpec()) + shrinkVertically(
                    animationSpec = nonBouncyExitSpring()
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        thickness = 0.5.dp
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        steps.forEach { step ->
                            OrchestrationStepRow(step = step)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PhaseStepBadge(
    stepNumber: String,
    label: String,
    isCompleted: Boolean,
    isActive: Boolean,
    isError: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when {
            isError -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
            isCompleted -> {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Completed",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
            isActive -> {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            else -> {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(18.dp)
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
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (isActive || isCompleted) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = when {
                isError -> MaterialTheme.colorScheme.error
                isCompleted -> MaterialTheme.colorScheme.primary
                isActive -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
            }
        )
    }
}

@Composable
private fun DiscoveredSourcesRail(
    sources: List<StreamingManager.DiscoveredSource>,
    onSourceClick: (TopicRef) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        sources.forEach { source ->
            Surface(
                onClick = { onSourceClick(source.toTopicRef()) },
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (source.isGraphic) Icons.Default.TableChart else Icons.Default.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = source.title.ifBlank {
                            if (source.isGraphic) "Table #${source.topicId}" else "Topic #${source.topicId}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (!source.isGraphic && source.sectionsRead > 0) {
                        Text(
                            text = "·${source.sectionsRead}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OrchestrationStepRow(
    step: StreamingManager.OrchestrationStep
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (step.status) {
            StreamingManager.StepStatus.IN_PROGRESS -> {
                CircularWavyProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            StreamingManager.StepStatus.COMPLETED -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Done",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
            }
            StreamingManager.StepStatus.FAILED -> {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        Text(
            text = step.title,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )

        if (!step.detail.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = step.detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
