package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.ProviderSettings

@Composable
fun OpenRouterSettingsPanel(
    settings: ProviderSettings.OpenRouter,
    onUpdate: (ProviderSettings.OpenRouter) -> Unit
) {
    ProviderSettingsHeader("OpenRouter Settings")

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

        // Repetition Penalty
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Repetition Penalty", style = MaterialTheme.typography.labelMedium)
                Text(
                    String.format("%.2f", settings.repetitionPenalty ?: 1.0),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = (settings.repetitionPenalty ?: 1.0).toFloat(),
                onValueChange = { onUpdate(settings.copy(repetitionPenalty = it.toDouble())) },
                valueRange = 0f..2f,
                steps = 19,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Min P
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Min P", style = MaterialTheme.typography.labelMedium)
                Text(
                    String.format("%.2f", settings.minP ?: 0.0),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = (settings.minP ?: 0.0).toFloat(),
                onValueChange = { onUpdate(settings.copy(minP = it.toDouble())) },
                valueRange = 0f..1f,
                steps = 9,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Context Transforms
        OutlinedTextField(
            value = settings.transforms?.joinToString(", ") ?: "middle-out",
            onValueChange = {
                val transforms = it.split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() }.ifEmpty { null }
                onUpdate(settings.copy(transforms = transforms))
            },
            label = { Text("Context Transforms") },
            placeholder = { Text("middle-out") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        )
    }
}
