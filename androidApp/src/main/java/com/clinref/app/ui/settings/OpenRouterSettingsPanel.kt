package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.ProviderSettings

@Composable
fun OpenRouterSettingsPanel(
    settings: ProviderSettings.OpenRouter,
    onUpdate: (ProviderSettings.OpenRouter) -> Unit
) {
    ProviderSettingsHeader("OpenRouter Settings")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "OpenRouter routes inference across 300+ upstream LLMs using your chosen model identifier. Common parameters like Temperature and Max Output Tokens are configured above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
