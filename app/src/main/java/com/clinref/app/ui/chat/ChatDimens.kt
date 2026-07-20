package com.clinref.app.ui.chat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shared visual constants for the chat experience. Centralising these keeps the
 * message list, input bar, and supporting components (citations, warnings,
 * activity indicators) visually consistent and makes future tuning a one-line change.
 */
object ChatDimens {
    val ScreenHPadding = 16.dp
    val MessageVGap = 2.dp
    val TurnVGap = 18.dp

    val BubbleMaxWidthFraction = 0.84f
    val UserBubbleShape = RoundedCornerShape(
        topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp
    )

    val InputCornerRadius = 26.dp
    val SendButtonSize = 40.dp
    val SendButtonCornerRadiusIdle = 20.dp
    val SendButtonCornerRadiusActive = 12.dp
    val AssistantMarkSize = 26.dp

    val ChipHeight = 34.dp
}

/**
 * Decorative, non-interactive shapes used for branding accents (the assistant "mark"
 * and the empty-state hero shape).
 *
 * MaterialShapes / toShape() requires a @Composable context and cannot be used in
 * a plain object, so we use RoundedCornerShape(50) as a universal fallback that
 * gives the same "pill" / circular silhouette without the composable constraint.
 */
object ChatShapes {
    val assistantMark: Shape = RoundedCornerShape(50)
    val emptyStateHero: Shape = RoundedCornerShape(50)
}
