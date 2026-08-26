package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.ProviderSettings

@Composable
fun OpenAISettingsPanel(
    settings: ProviderSettings.OpenAI,
    onUpdate: (ProviderSettings.OpenAI) -> Unit
) {
    ProviderSettingsHeader("OpenAI Settings")

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Top P
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Top P", style = MaterialTheme.typography.labelMedium)
                Text(
                    String.format("%.2f", settings.topP ?: 1.0),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = (settings.topP ?: 1.0).toFloat(),
                onValueChange = { onUpdate(settings.copy(topP = it.toDouble())) },
                valueRange = 0f..1f,
                steps = 9,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Reasoning Effort
        Column {
            Text("Reasoning Effort", style = MaterialTheme.typography.labelMedium)
            Text(
                "Controls depth of reasoning (o-series, GPT-5+)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = settings.reasoningEffort == null,
                    onClick = { onUpdate(settings.copy(reasoningEffort = null)) },
                    label = { Text("Default") }
                )
                FilterChip(
                    selected = settings.reasoningEffort == "low",
                    onClick = { onUpdate(settings.copy(reasoningEffort = "low")) },
                    label = { Text("Low") }
                )
                FilterChip(
                    selected = settings.reasoningEffort == "medium",
                    onClick = { onUpdate(settings.copy(reasoningEffort = "medium")) },
                    label = { Text("Medium") }
                )
                FilterChip(
                    selected = settings.reasoningEffort == "high",
                    onClick = { onUpdate(settings.copy(reasoningEffort = "high")) },
                    label = { Text("High") }
                )
            }
        }

        // Store Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Store Outputs", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Allow OpenAI to store outputs for improvement",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.store ?: false,
                onCheckedChange = { onUpdate(settings.copy(store = it)) }
            )
        }
    }
}
