package com.clinref.app.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
    val motionScheme = MaterialTheme.motionScheme

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = scaleIn(animationSpec = motionScheme.fastSpatialSpec()) +
            fadeIn(animationSpec = motionScheme.fastEffectsSpec()),
        exit = scaleOut(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeOut(animationSpec = motionScheme.fastEffectsSpec())
    ) {
        SmallFloatingActionButton(
            onClick = onClick,
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            elevation = FloatingActionButtonDefaults.elevation(
                defaultElevation = 2.dp,
                pressedElevation = 4.dp
            ),
            modifier = Modifier
                .size(48.dp)
                .pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                imageVector = if (direction == ScrollFABDirection.UP) {
                    Icons.Default.KeyboardArrowUp
                } else {
                    Icons.Default.KeyboardArrowDown
                },
                contentDescription = if (direction == ScrollFABDirection.UP) {
                    "Scroll to top"
                } else {
                    "Scroll to bottom"
                },
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun ScrollToBottomFAB(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) = ScrollFAB(visible = visible, direction = ScrollFABDirection.DOWN, onClick = onClick, modifier = modifier)
