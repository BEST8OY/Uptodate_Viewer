package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
fun GoogleSettingsPanel(
    settings: ProviderSettings.Google,
    modifier: Modifier = Modifier,
    onUpdate: (ProviderSettings.Google) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProviderSettingsHeader("Google Gemini Settings")

        // Thinking toggle with 48dp accessible row touch target
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .toggleable(
                    value = settings.includeThoughts,
                    onValueChange = { onUpdate(settings.copy(includeThoughts = it)) },
                    role = Role.Switch
                )
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Enable Thinking", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Show model reasoning (Gemini 2.0+)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.includeThoughts,
                onCheckedChange = null
            )
        }

        // Thinking Level (for Gemini 3.0)
        if (settings.includeThoughts) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Thinking Level", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.thinkingLevel == null,
                        onClick = { onUpdate(settings.copy(thinkingLevel = null)) },
                        label = { Text("Default") },
                        shape = MaterialTheme.shapes.small
                    )
                    FilterChip(
                        selected = settings.thinkingLevel == "low",
                        onClick = { onUpdate(settings.copy(thinkingLevel = "low")) },
                        label = { Text("Low") },
                        shape = MaterialTheme.shapes.small
                    )
                    FilterChip(
                        selected = settings.thinkingLevel == "high",
                        onClick = { onUpdate(settings.copy(thinkingLevel = "high")) },
                        label = { Text("High") },
                        shape = MaterialTheme.shapes.small
                    )
                }
                Text(
                    "Gemini 3.0 uses level. Gemini 2.0 uses budget (default 8192 tokens).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

