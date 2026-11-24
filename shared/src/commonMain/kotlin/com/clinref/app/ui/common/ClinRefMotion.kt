package com.clinref.app.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

fun <T> nonBouncyExitSpring(
    stiffness: Float = Spring.StiffnessMediumLow,
    visibilityThreshold: T? = null
): SpringSpec<T> = spring(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = stiffness,
    visibilityThreshold = visibilityThreshold
)

fun <T> nonBouncyFastExitSpring(
    visibilityThreshold: T? = null
): SpringSpec<T> = nonBouncyExitSpring(
    stiffness = Spring.StiffnessMedium,
    visibilityThreshold = visibilityThreshold
)
