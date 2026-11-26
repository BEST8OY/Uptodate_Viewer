package com.clinref.app.ui.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.scene.Scene
import kotlin.math.roundToInt

@Immutable
internal class NavMotion(
    val spatialSpec: FiniteAnimationSpec<IntOffset>,
    val floatSpatialSpec: FiniteAnimationSpec<Float>,
    val effectsSpec: FiniteAnimationSpec<Float>,
    val fastEffectsSpec: FiniteAnimationSpec<Float>,
    val exitSpatialSpec: FiniteAnimationSpec<IntOffset>,
    val exitFloatSpatialSpec: FiniteAnimationSpec<Float>
)

@Composable
internal fun rememberNavMotion(): NavMotion {
    val motionScheme = MaterialTheme.motionScheme
    return remember(motionScheme) {
        NavMotion(
            spatialSpec = motionScheme.defaultSpatialSpec(),
            floatSpatialSpec = motionScheme.defaultSpatialSpec(),
            effectsSpec = motionScheme.defaultEffectsSpec(),
            fastEffectsSpec = motionScheme.fastEffectsSpec(),
            exitSpatialSpec = motionScheme.fastSpatialSpec(),
            exitFloatSpatialSpec = motionScheme.fastSpatialSpec()
        )
    }
}

internal val RouteKeyMetadata = object : NavMetadataKey<NavKey> {}

internal fun Scene<NavKey>.routeKey(): NavKey? =
    entries.lastOrNull()?.metadata?.get(RouteKeyMetadata)

internal fun peerLateralTransition(
    from: TopLevelRoute,
    to: TopLevelRoute,
    motion: NavMotion
): ContentTransform {
    val fromIndex = topLevelRoutes.indexOf(from)
    val toIndex = topLevelRoutes.indexOf(to)
    return when {
        toIndex > fromIndex -> {
            (slideInHorizontally(motion.spatialSpec) { (it * 0.20f).roundToInt() } +
                fadeIn(motion.effectsSpec)) togetherWith
                (slideOutHorizontally(motion.spatialSpec) { -(it * 0.20f).roundToInt() } +
                    fadeOut(motion.fastEffectsSpec))
        }
        toIndex < fromIndex -> {
            (slideInHorizontally(motion.spatialSpec) { -(it * 0.20f).roundToInt() } +
                fadeIn(motion.effectsSpec)) togetherWith
                (slideOutHorizontally(motion.spatialSpec) { (it * 0.20f).roundToInt() } +
                    fadeOut(motion.fastEffectsSpec))
        }
        else -> fadeIn(motion.effectsSpec) togetherWith fadeOut(motion.fastEffectsSpec)
    }
}

internal fun auxiliaryModalLiftTransition(motion: NavMotion): ContentTransform =
    (slideInVertically(motion.spatialSpec) { (it * 0.12f).roundToInt() } +
        scaleIn(motion.floatSpatialSpec, initialScale = 0.94f) +
        fadeIn(motion.effectsSpec)) togetherWith
        (scaleOut(motion.floatSpatialSpec, targetScale = 0.96f) +
            fadeOut(motion.fastEffectsSpec, targetAlpha = 0f))

internal fun auxiliaryModalRecedeTransition(motion: NavMotion): ContentTransform =
    (scaleIn(motion.floatSpatialSpec, initialScale = 0.96f) +
        fadeIn(motion.effectsSpec)) togetherWith
        (slideOutVertically(motion.exitSpatialSpec) { (it * 0.12f).roundToInt() } +
            scaleOut(motion.exitFloatSpatialSpec, targetScale = 0.94f) +
            fadeOut(motion.fastEffectsSpec, targetAlpha = 0f))

internal fun hierarchicalForwardTransition(motion: NavMotion): ContentTransform =
    (slideInHorizontally(motion.spatialSpec) { it } +
        fadeIn(motion.effectsSpec, initialAlpha = 0f)) togetherWith
        (slideOutHorizontally(motion.spatialSpec) { -it / 4 } +
            scaleOut(motion.floatSpatialSpec, targetScale = 0.96f) +
            fadeOut(motion.fastEffectsSpec, targetAlpha = 0f))

internal fun hierarchicalPopTransition(
    motion: NavMotion,
    initialScale: Float = 0.96f,
    targetScale: Float = 0.96f,
    slideSign: Int = 1
): ContentTransform =
    (slideInHorizontally(motion.spatialSpec) { -it / 4 * slideSign } +
        scaleIn(motion.floatSpatialSpec, initialScale = initialScale) +
        fadeIn(motion.effectsSpec, initialAlpha = 0f)) togetherWith
        (slideOutHorizontally(motion.exitSpatialSpec) { it * slideSign } +
            scaleOut(motion.exitFloatSpatialSpec, targetScale = targetScale) +
            fadeOut(motion.fastEffectsSpec, targetAlpha = 0f))
