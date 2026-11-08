package com.clinref.app.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clinref.app.domain.ai.AiJsonUtils
import com.clinref.app.domain.ai.ClinicalSource
import com.clinref.app.ui.adaptive.AdaptiveTokens

/**
 * Academic-grade clinical references accordion.
 *
 * Consolidates all cited UpToDate topics and interactive tables into a single
 * unified container with typographic citation badges (e.g. [1], [T1]) rather than
 * generic clip-art icons. Motion is driven by Material 3 Expressive motionScheme.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClinicalReferencesSection(
    articles: List<ClinicalSource.Article>,
    tables: List<ClinicalSource.Table>,
    onNavigateToContent: (topicId: String, sectionId: String?) -> Unit,
    onGraphicSelected: (graphicId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (articles.isEmpty() && tables.isEmpty()) return

    var isExpanded by rememberSaveable { mutableStateOf(true) }
    val motionScheme = MaterialTheme.motionScheme

    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = motionScheme.fastSpatialSpec(),
        label = "references_chevron"
    )

    val totalCount = articles.size + tables.size

    Surface(
        modifier = modifier
            .widthIn(max = AdaptiveTokens.MaxChatInputWidth)
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Grouped Accordion Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable(role = Role.Button) { isExpanded = !isExpanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CLINICAL EVIDENCE SOURCES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = if (totalCount == 1) "1 source" else "$totalCount sources",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse references" else "Expand references",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation)
                )
            }

            // Expandable Reference List
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(animationSpec = motionScheme.fastEffectsSpec()) +
                    expandVertically(animationSpec = motionScheme.fastSpatialSpec()),
                exit = fadeOut(animationSpec = motionScheme.fastEffectsSpec()) +
                    shrinkVertically(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    var citationIndex = 1

                    // Articles Section
                    articles.forEachIndexed { index, article ->
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            thickness = 0.5.dp
                        )

                        val currentCitation = citationIndex++
                        val rawTopicTitle = if (article.topicTitle.isBlank() || AiJsonUtils.isNumericOnly(article.topicTitle)) {
                            "Clinical Topic #${article.topicId}"
                        } else {
                            article.topicTitle
                        }
                        val topicTitle = AiJsonUtils.cleanHtmlText(rawTopicTitle)

                        ArticleReferenceItem(
                            citationNumber = currentCitation,
                            title = topicTitle,
                            sections = article.sections,
                            onTopicClick = { onNavigateToContent(article.topicId, null) },
                            onSectionClick = { sectionId -> onNavigateToContent(article.topicId, sectionId) }
                        )
                    }

                    // Tables Section
                    tables.forEachIndexed { index, table ->
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                            thickness = 0.5.dp
                        )

                        TableReferenceItem(
                            tableIndex = index + 1,
                            table = table,
                            onClick = { onGraphicSelected(table.graphicId) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ArticleReferenceItem(
    citationNumber: Int,
    title: String,
    sections: List<ClinicalSource.SectionRef>,
    onTopicClick: () -> Unit,
    onSectionClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onTopicClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // Typographic Citation Index Badge (e.g. 1, 2)
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier
                    .size(24.dp)
                    .padding(top = 1.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "$citationNumber",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // Sub-Section Chips
                if (sections.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        sections.forEach { sec ->
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                onClick = { onSectionClick(sec.sectionId) },
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text(
                                    text = sec.sectionTitle.ifBlank { "Section" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier
                    .size(12.dp)
                    .align(Alignment.CenterVertically)
            )
        }
    }
}

@Composable
private fun TableReferenceItem(
    tableIndex: Int,
    table: ClinicalSource.Table,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(24.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "T$tableIndex",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = table.tableTitle.ifBlank { "Clinical Reference Table" },
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!table.parentTopicTitle.isNullOrBlank()) {
                Text(
                    text = table.parentTopicTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(12.dp)
        )
    }
}
