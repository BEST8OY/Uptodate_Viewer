package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.ProviderSettings

@Composable
fun OpenAISettingsPanel(
    settings: ProviderSettings.OpenAI,
    modifier: Modifier = Modifier,
    onUpdate: (ProviderSettings.OpenAI) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProviderSettingsHeader("OpenAI Settings")

        // Reasoning Effort
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Reasoning Effort", style = MaterialTheme.typography.labelMedium)
            Text(
                "Controls depth of reasoning (o-series, GPT-5+)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = settings.reasoningEffort == null,
                    onClick = { onUpdate(settings.copy(reasoningEffort = null)) },
                    label = { Text("Default") },
                    shape = MaterialTheme.shapes.small
                )
                FilterChip(
                    selected = settings.reasoningEffort == "low",
                    onClick = { onUpdate(settings.copy(reasoningEffort = "low")) },
                    label = { Text("Low") },
                    shape = MaterialTheme.shapes.small
                )
                FilterChip(
                    selected = settings.reasoningEffort == "medium",
                    onClick = { onUpdate(settings.copy(reasoningEffort = "medium")) },
                    label = { Text("Medium") },
                    shape = MaterialTheme.shapes.small
                )
                FilterChip(
                    selected = settings.reasoningEffort == "high",
                    onClick = { onUpdate(settings.copy(reasoningEffort = "high")) },
                    label = { Text("High") },
                    shape = MaterialTheme.shapes.small
                )
            }
        }

        // Store Toggle with 48dp accessible row touch target
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .toggleable(
                    value = settings.store ?: false,
                    onValueChange = { onUpdate(settings.copy(store = it)) },
                    role = Role.Switch
                )
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Store Outputs", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Allow OpenAI to store outputs for improvement",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.store ?: false,
                onCheckedChange = null
            )
        }
    }
}

