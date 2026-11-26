package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

        SettingsToggleRow(
            title = "Extended Thinking",
            subtitle = "Show Claude reasoning chain (Claude 3.7+)",
            checked = settings.enableThinking,
            onCheckedChange = { onUpdate(settings.copy(enableThinking = it)) }
        )

        if (settings.enableThinking) {
            val currentBudget = settings.thinkingBudget ?: 2048
            SettingsChipGroup(
                title = "Thinking Budget",
                subtitle = "Max tokens allocated for reasoning before synthesis",
                options = listOf(1024, 2048, 4096, 8192),
                selectedOption = currentBudget,
                onOptionSelected = { onUpdate(settings.copy(thinkingBudget = it)) },
                labelProvider = { "$it tokens" }
            )
        }
    }
}
