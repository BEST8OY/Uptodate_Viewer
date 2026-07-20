package com.clinref.app.ui.chat.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.clinref.app.ui.chat.ChatDimens

/**
 * Shown in place of the assistant's next turn while it's working — whether that's
 * "Searching topics…", "Reading section content…", or a generic "Thinking…" while
 * waiting on the model between tool calls. One consistent row for all of those states
 * reads far calmer than alternating between a labelled row and a bare spinner.
 *
 * Uses M3 Expressive's [LoadingIndicator], which morphs between shapes rather than
 * spinning — a nice, literal fit for "the assistant is actively working on this."
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolProgressIndicator(
    label: String,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "activity_shimmer")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "activity_shimmer_alpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ChatDimens.ScreenHPadding, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistantMark()
        Spacer(modifier = Modifier.size(10.dp))
        LoadingIndicator(modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.size(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.alpha(alpha)
        )
    }
}
