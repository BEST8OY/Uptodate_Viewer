package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.clinref.app.domain.ai.ProviderSettings

@Composable
fun OllamaSettingsPanel(
    settings: ProviderSettings.Ollama,
    onUpdate: (ProviderSettings.Ollama) -> Unit
) {
    ProviderSettingsHeader("Ollama Settings")

    OutlinedTextField(
        value = settings.numCtx?.toString() ?: "",
        onValueChange = { onUpdate(settings.copy(numCtx = it.toIntOrNull())) },
        label = { Text("Context Window (num_ctx)") },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}
