package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.ProviderSettings

@Composable
fun OllamaSettingsPanel(
    settings: ProviderSettings.Ollama,
    modifier: Modifier = Modifier,
    onUpdate: (ProviderSettings.Ollama) -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProviderSettingsHeader("Ollama Settings")

        OutlinedTextField(
            value = settings.numCtx?.toString() ?: "",
            onValueChange = { onUpdate(settings.copy(numCtx = it.toIntOrNull())) },
            label = { Text("Context Window (num_ctx)") },
            placeholder = { Text("4096 (default)") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            )
        )
    }
}

