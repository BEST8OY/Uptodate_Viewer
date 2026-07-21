package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.ProviderSettings

@Composable
fun GoogleSettingsPanel(
    settings: ProviderSettings.Google,
    onUpdate: (ProviderSettings.Google) -> Unit
) {
    ProviderSettingsHeader("Google Gemini Settings")

    // Thinking toggle
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Enable Thinking", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            Text(
                "Show model reasoning (Gemini 2.0+)",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = settings.includeThoughts,
            onCheckedChange = { onUpdate(settings.copy(includeThoughts = it)) }
        )
    }

    // Thinking Level (for Gemini 3.0)
    if (settings.includeThoughts) {
        Column {
            Text("Thinking Level", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = settings.thinkingLevel == null,
                    onClick = { onUpdate(settings.copy(thinkingLevel = null)) },
                    label = { Text("Default") }
                )
                FilterChip(
                    selected = settings.thinkingLevel == "low",
                    onClick = { onUpdate(settings.copy(thinkingLevel = "low")) },
                    label = { Text("Low") }
                )
                FilterChip(
                    selected = settings.thinkingLevel == "high",
                    onClick = { onUpdate(settings.copy(thinkingLevel = "high")) },
                    label = { Text("High") }
                )
            }
            Text(
                "Gemini 3.0 uses level. Gemini 2.0 uses budget (default 8192 tokens).",
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
