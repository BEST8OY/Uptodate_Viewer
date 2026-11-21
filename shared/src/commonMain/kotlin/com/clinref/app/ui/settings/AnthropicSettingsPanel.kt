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
fun AnthropicSettingsPanel(
    settings: ProviderSettings.Anthropic,
    modifier: Modifier = Modifier,
    onUpdate: (ProviderSettings.Anthropic) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ProviderSettingsHeader("Anthropic Settings")

        // Extended Thinking Toggle with 48dp accessible row touch target
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .toggleable(
                    value = settings.enableThinking,
                    onValueChange = { onUpdate(settings.copy(enableThinking = it)) },
                    role = Role.Switch
                )
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Text("Extended Thinking", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Show Claude reasoning chain (Claude 3.7+)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.enableThinking,
                onCheckedChange = null
            )
        }

        // Thinking Token Budget
        if (settings.enableThinking) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Thinking Budget", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Max tokens allocated for reasoning before synthesis",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val currentBudget = settings.thinkingBudget ?: 2048
                    listOf(1024, 2048, 4096, 8192).forEach { budget ->
                        FilterChip(
                            selected = currentBudget == budget,
                            onClick = { onUpdate(settings.copy(thinkingBudget = budget)) },
                            label = { Text("$budget tokens") },
                            shape = MaterialTheme.shapes.small
                        )
                    }
                }
            }
        }
    }
}

