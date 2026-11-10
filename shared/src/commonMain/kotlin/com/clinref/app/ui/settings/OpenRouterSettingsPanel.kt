package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.ProviderSettings

@Composable
fun OpenRouterSettingsPanel(
    settings: ProviderSettings.OpenRouter,
    modifier: Modifier = Modifier,
    onUpdate: (ProviderSettings.OpenRouter) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProviderSettingsHeader("OpenRouter Settings")

        Text(
            "OpenRouter routes inference across 300+ upstream LLMs using your chosen model identifier. Common parameters like Temperature and Max Output Tokens are configured above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

