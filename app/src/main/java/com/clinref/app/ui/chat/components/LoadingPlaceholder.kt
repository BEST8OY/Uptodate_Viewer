package com.clinref.app.ui.chat.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.clinref.app.ui.chat.ChatDimens

@Composable
fun ChatLoadingPlaceholder(
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmer_alpha"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ChatDimens.ScreenHPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        AssistantSkeletonTurn(alpha, lineWidths = listOf(1f, 0.9f, 0.55f))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SkeletonBlock(width = 150.dp, height = 34.dp, alpha = alpha, shape = ChatDimens.UserBubbleShape)
        }

        AssistantSkeletonTurn(alpha, lineWidths = listOf(1f, 0.7f))
    }
}

@Composable
private fun AssistantSkeletonTurn(alpha: Float, lineWidths: List<Float>) {
    Row(modifier = Modifier.fillMaxWidth()) {
        SkeletonBlock(width = ChatDimens.AssistantMarkSize, height = ChatDimens.AssistantMarkSize, alpha = alpha, shape = CircleShape)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lineWidths.forEach { fraction ->
                Box(modifier = Modifier.fillMaxWidth(fraction)) {
                    SkeletonBlock(width = null, height = 14.dp, alpha = alpha, shape = RoundedCornerShape(4.dp))
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    width: androidx.compose.ui.unit.Dp?,
    height: androidx.compose.ui.unit.Dp,
    alpha: Float,
    shape: androidx.compose.ui.graphics.Shape
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = (if (width != null) Modifier.size(width, height) else Modifier
            .fillMaxWidth()
            .height(height))
            .alpha(alpha)
    ) {}
}
