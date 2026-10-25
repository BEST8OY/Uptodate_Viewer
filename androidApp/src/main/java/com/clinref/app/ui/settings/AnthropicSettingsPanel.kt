package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.ProviderSettings

@Composable
fun AnthropicSettingsPanel(
    settings: ProviderSettings.Anthropic,
    onUpdate: (ProviderSettings.Anthropic) -> Unit
) {
    ProviderSettingsHeader("Anthropic Settings")

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Extended Thinking Toggle (currently disabled pending Koog library update)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Extended Thinking", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Currently disabled — pending Koog library compatibility",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Switch(
                checked = false,
                onCheckedChange = { },
                enabled = false
            )
        }
    }
}
