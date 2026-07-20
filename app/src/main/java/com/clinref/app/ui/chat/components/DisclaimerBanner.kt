package com.clinref.app.ui.chat.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fine-print safety note. Lives directly beneath the input bar (see ChatInput) rather
 * than as a Scaffold-level bottom banner, so it travels with the composer instead of
 * sitting pinned to the bottom of the screen behind it.
 */
@Composable
fun DisclaimerBanner(modifier: Modifier = Modifier) {
    Text(
        text = "AI-assisted \u2014 verify against source before clinical use.",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 4.dp)
    )
}
