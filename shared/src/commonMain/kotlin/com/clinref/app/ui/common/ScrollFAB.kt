package com.clinref.app.ui.common

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
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
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "fab_alpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) {
            MaterialTheme.motionScheme.fastSpatialSpec()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        },
        label = "fab_scale"
    )

    if (alpha > 0f || visible) {
        Box(
            modifier = modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                    transformOrigin = TransformOrigin.Center
                }
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
                modifier = Modifier.size(48.dp)
            ) {
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

