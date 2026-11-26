package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

        SettingsChipGroup(
            title = "Reasoning Effort",
            subtitle = "Controls depth of reasoning (o-series, GPT-5+)",
            options = listOf(null, "low", "medium", "high"),
            selectedOption = settings.reasoningEffort,
            onOptionSelected = { onUpdate(settings.copy(reasoningEffort = it)) },
            labelProvider = { it?.replaceFirstChar { c -> c.uppercase() } ?: "Default" }
        )

        SettingsToggleRow(
            title = "Store Outputs",
            subtitle = "Allow OpenAI to store outputs for improvement",
            checked = settings.store ?: false,
            onCheckedChange = { onUpdate(settings.copy(store = it)) }
        )
    }
}
