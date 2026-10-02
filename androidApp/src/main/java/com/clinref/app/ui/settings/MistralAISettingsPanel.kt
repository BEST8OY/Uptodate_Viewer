package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
fun MistralAISettingsPanel(
    settings: ProviderSettings.MistralAI,
    onUpdate: (ProviderSettings.MistralAI) -> Unit
) {
    ProviderSettingsHeader("Mistral AI Settings")

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

        // Safe Prompt Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Safe Prompt", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Inject safety prompt before conversations",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.safePrompt ?: false,
                onCheckedChange = { onUpdate(settings.copy(safePrompt = it)) }
            )
        }
    }
}
