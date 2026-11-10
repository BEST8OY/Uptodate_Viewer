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
fun MistralAISettingsPanel(
    settings: ProviderSettings.MistralAI,
    modifier: Modifier = Modifier,
    onUpdate: (ProviderSettings.MistralAI) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProviderSettingsHeader("Mistral AI Settings")
        Text(
            text = "Mistral AI models utilize the standard gateway endpoint and inference parameters (Temperature and Max Output Tokens) configured above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}



