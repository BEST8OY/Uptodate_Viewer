package com.clinref.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Specification of a swipe action to display in [SegmentedDismissBackground].
 */
data class DismissAction(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color? = null,
    val contentColor: Color? = null,
)

/**
 * Common M3 Expressive dismiss background for segmented list items using a separating pill architecture.
 *
 * Rather than filling the entire row beneath the card (which causes subpixel edge and corner bleed),
 * the background is drawn strictly inside the exposed gap left behind by the translating card,
 * separated by a dynamic 2dp gap that tapers to 0px on dismissal.
 *
 * Supports both [startAction] (revealed when swiping start-to-end) and [endAction] (revealed when swiping end-to-start).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedDismissBackground(
    dismissState: SwipeToDismissBoxState,
    shape: Shape,
    modifier: Modifier = Modifier,
    startAction: DismissAction? = null,
    endAction: DismissAction? = null,
) {
    val slicePath = remember { Path() }
    val direction = dismissState.dismissDirection
    val isStartToEnd = direction == SwipeToDismissBoxValue.StartToEnd
    val activeAction = if (isStartToEnd) startAction else endAction

    if (activeAction == null) return

    val containerColor = activeAction.containerColor ?: if (isStartToEnd) {
        MaterialTheme.colorScheme.tertiaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = activeAction.contentColor ?: if (isStartToEnd) {
        MaterialTheme.colorScheme.onTertiaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawSwipeActionPill(
                    dismissState = dismissState,
                    isStartToEnd = isStartToEnd,
                    containerColor = containerColor,
                    shape = shape,
                    reusePath = slicePath,
                )
                .padding(horizontal = 24.dp),
            contentAlignment = if (isStartToEnd) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isStartToEnd) {
                    Icon(
                        imageVector = activeAction.icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = activeAction.label,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else {
                    Text(
                        text = activeAction.label,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Icon(
                        imageVector = activeAction.icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Safely reads the current drag offset from [SwipeToDismissBoxState], returning `0f` before
 * the underlying anchored draggable state anchors are initialized on the first layout pass.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun SwipeToDismissBoxState.safeOffset(): Float =
    try {
        val value = requireOffset()
        if (value.isNaN()) 0f else value
    } catch (_: IllegalStateException) {
        0f
    }

/**
 * Clips the outer [androidx.compose.material3.SwipeToDismissBox] silhouette to [shape] only while
 * a swipe is actively displaced (`placedOffsetPx != 0`), preventing the translating card from
 * overflowing into row padding while avoiding double-clipping or subpixel edge blur when settled at rest.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.clipDuringSwipe(
    dismissState: SwipeToDismissBoxState,
    shape: Shape,
    reusePath: Path,
): Modifier = drawWithContent {
    val rawOffset = dismissState.safeOffset()
    if (rawOffset.roundToInt() == 0) {
        drawContent()
        return@drawWithContent
    }

    reusePath.rewind()
    reusePath.addOutline(shape.createOutline(size, layoutDirection, this))
    clipPath(reusePath) {
        this@drawWithContent.drawContent()
    }
}

/**
 * Draws the swipe action background and content inside a dynamic rounded pill of [shape]
 * that grows within the exposed gap left behind by the sliding item.
 *
 * Because the pill never extends into the region occupied by the card, the item retains
 * its native shapes with zero background bleeding.
 */
@OptIn(ExperimentalMaterial3Api::class)
fun Modifier.drawSwipeActionPill(
    dismissState: SwipeToDismissBoxState,
    isStartToEnd: Boolean,
    containerColor: Color,
    shape: Shape,
    reusePath: Path,
): Modifier = drawWithContent {
    val rawOffset = dismissState.safeOffset()
    val isActiveDirection = if (isStartToEnd) rawOffset > 0f else rawOffset < 0f
    if (!isActiveDirection || size.width <= 0f) return@drawWithContent

    val rtlModifier = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
    val placedOffsetPx = (rawOffset * rtlModifier).roundToInt().toFloat()
    val absOffsetPx = abs(placedOffsetPx)
    if (absOffsetPx <= 0f) return@drawWithContent

    // Maintain a crisp 2.dp separation gap between the action pill and the sliding card,
    // tapering smoothly to 0px as the item reaches full dismissal.
    val fraction = (absOffsetPx / size.width).coerceIn(0f, 1f)
    val gapPx = 2.dp.toPx() * (1f - fraction)
    val revealWidth = (absOffsetPx - gapPx).coerceIn(0f, size.width)
    if (revealWidth <= 1f) return@drawWithContent

    val sliceLeft = if (placedOffsetPx > 0f) {
        0f
    } else {
        (size.width - revealWidth).coerceAtLeast(0f)
    }
    val sliceSize = Size(revealWidth, size.height)

    reusePath.rewind()
    reusePath.addOutline(shape.createOutline(sliceSize, layoutDirection, this))
    if (sliceLeft != 0f) {
        reusePath.translate(Offset(sliceLeft, 0f))
    }

    clipPath(reusePath) {
        drawRect(
            color = containerColor,
            topLeft = Offset(sliceLeft, 0f),
            size = sliceSize,
        )
        this@drawWithContent.drawContent()
    }
}
