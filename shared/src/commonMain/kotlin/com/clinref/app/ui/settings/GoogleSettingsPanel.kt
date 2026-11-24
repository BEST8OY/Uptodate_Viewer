package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

        SettingsToggleRow(
            title = "Enable Thinking",
            subtitle = "Show model reasoning (Gemini 2.0+)",
            checked = settings.includeThoughts,
            onCheckedChange = { onUpdate(settings.copy(includeThoughts = it)) }
        )

        if (settings.includeThoughts) {
            SettingsChipGroup(
                title = "Thinking Level",
                options = listOf(null, "low", "medium", "high"),
                selectedOption = settings.thinkingLevel,
                onOptionSelected = { onUpdate(settings.copy(thinkingLevel = it)) },
                labelProvider = { it?.replaceFirstChar { c -> c.uppercase() } ?: "Default" },
                helperText = "Gemini 3.0+ / 4.x uses level (Low, Medium, High). Gemini 2.0 uses budget (default 8192 tokens)."
            )
        }
    }
}
