package com.clinref.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp

enum class ScrollFABDirection { UP, DOWN }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScrollFAB(
    visible: Boolean,
    direction: ScrollFABDirection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(initialScale = 0.7f, transformOrigin = TransformOrigin.Center) + fadeIn(
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec()
        ),
        exit = scaleOut(targetScale = 0.0f, transformOrigin = TransformOrigin.Center) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shadowElevation = 3.dp,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (direction == ScrollFABDirection.UP) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (direction == ScrollFABDirection.UP) "Scroll to top" else "Scroll to bottom",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun ScrollToTopFAB(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = ScrollFAB(visible = visible, direction = ScrollFABDirection.UP, onClick = onClick, modifier = modifier)

@Composable
fun ScrollToBottomFAB(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = ScrollFAB(visible = visible, direction = ScrollFABDirection.DOWN, onClick = onClick, modifier = modifier)
