package com.clinref.app.ui.chat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toShape
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
 * Decorative, non-interactive M3 Expressive shapes.
 *
 * These use [MaterialShapes] — the preset library of RoundedPolygons shipped with
 * Material 3 Expressive (androidx.compose.material3:material3 1.4+) — purely for
 * static branding accents (the assistant "mark" and the empty-state hero shape).
 *
 * NOTE: MaterialShapes / toShape() have moved between experimental and stable a
 * couple of times across 1.4.x releases. If your Compose BOM rejects the
 * `toShape()` import below, pin material3 to a version where MaterialShapes is
 * stable, or swap these two lines for a plain RoundedCornerShape(50) as a fallback.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object ChatShapes {
    val assistantMark: Shape by lazy { MaterialShapes.Cookie9Sided.toShape() }
    val emptyStateHero: Shape by lazy { MaterialShapes.Sunny.toShape() }
}
