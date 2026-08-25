package com.clinref.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Ancestor path of the active section (for example "Dosing › Perioperative"), shown once
 * the reader has scrolled into the article. Tapping reopens the outline sheet.
 */
@Composable
internal fun SectionBreadcrumb(
    visible: Boolean,
    trail: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motionScheme = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible && trail.isNotEmpty(),
        enter = fadeIn(motionScheme.defaultEffectsSpec()) +
            slideInVertically(motionScheme.defaultEffectsSpec()) { -it },
        exit = fadeOut(motionScheme.defaultEffectsSpec()) +
            slideOutVertically(motionScheme.defaultEffectsSpec()) { -it },
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = trail.joinToString(" › "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Single backward scan over the flat outline: collect TOPIC ancestors of the active
 * section by depth. Non-TOPIC leaves (graphics, related links) render as a lone leaf.
 */
internal fun buildBreadcrumbTrail(
    sections: List<OutlineSection>,
    activeId: String?
): List<String> {
    if (activeId == null || sections.isEmpty()) return emptyList()
    val index = sections.indexOfFirst { it.id == activeId }
    if (index == -1) return emptyList()

    val current = sections[index]
    val trail = mutableListOf(current.title)
    if (current.sectionType == SectionType.TOPIC) {
        var depth = current.depth
        for (i in index - 1 downTo 0) {
            val candidate = sections[i]
            if (candidate.sectionType == SectionType.TOPIC && candidate.depth < depth) {
                trail.add(candidate.title)
                depth = candidate.depth
                if (depth == 0) break
            }
        }
    }
    return trail.reversed()
}
