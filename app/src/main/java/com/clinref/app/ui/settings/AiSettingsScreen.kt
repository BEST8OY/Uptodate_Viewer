package com.clinref.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.ai.AiProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()

    var showApiKey by remember { mutableStateOf(false) }
    var providerExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("AI Settings") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Provider picker
            Text("Provider", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(
                expanded = providerExpanded,
                onExpandedChange = { providerExpanded = it }
            ) {
                OutlinedTextField(
                    value = configuration.provider.displayName,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = providerExpanded,
                    onDismissRequest = { providerExpanded = false }
                ) {
                    AiProvider.entries.forEach { provider ->
                        DropdownMenuItem(
                            text = { Text(provider.displayName) },
                            onClick = {
                                viewModel.updateProvider(provider)
                                providerExpanded = false
                            }
                        )
                    }
                }
            }

            // API key
            Text("API Key", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { viewModel.updateApiKey(it) },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showApiKey) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showApiKey = !showApiKey }) {
                        Icon(
                            imageVector = if (showApiKey) Icons.Default.VisibilityOff
                            else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility"
                        )
                    }
                },
                placeholder = { Text("Enter your API key") }
            )
            Text(
                text = "API keys are stored locally using Android Keystore.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Base URL (for Ollama/OpenRouter)
            if (configuration.provider == AiProvider.OLLAMA ||
                configuration.provider == AiProvider.OPENROUTER
            ) {
                Text("Base URL", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = configuration.baseUrl,
                    onValueChange = { viewModel.updateBaseUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            if (configuration.provider == AiProvider.OLLAMA) "http://localhost:11434"
                            else "https://openrouter.ai/api/v1"
                        )
                    }
                )
            }

            // Model picker
            Text("Model", style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it }
            ) {
                OutlinedTextField(
                    value = configuration.model.ifEmpty { "Select model" },
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                viewModel.updateModel(model)
                                modelExpanded = false
                            }
                        )
                    }
                }
            }

            // Temperature
            Text("Temperature: ${String.format("%.1f", configuration.temperature)}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = configuration.temperature,
                onValueChange = { viewModel.updateTemperature(it) },
                valueRange = 0f..1f,
                steps = 9
            )

            // Max tokens
            Text("Max Tokens", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = configuration.maxTokens.toString(),
                onValueChange = { it.toIntOrNull()?.let { tokens -> viewModel.updateMaxTokens(tokens) } },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // History compression threshold
            Text("History Compression Threshold (tokens)", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = configuration.historyCompressionThreshold.toString(),
                onValueChange = { it.toIntOrNull()?.let { threshold -> viewModel.updateHistoryThreshold(threshold) } },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            // Requests per minute
            Text(
                text = "Requests per Minute: ${if (configuration.requestsPerMinute == 0) "Unlimited" else configuration.requestsPerMinute}",
                style = MaterialTheme.typography.titleMedium
            )
            Slider(
                value = configuration.requestsPerMinute.toFloat(),
                onValueChange = { viewModel.updateRequestsPerMinute(it.toInt()) },
                valueRange = 0f..60f,
                steps = 11
            )
            Text(
                text = "0 = unlimited. Limits API call frequency to avoid rate limits.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Data handling notice
            Text("Data Handling", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "API keys are stored on device only. Cloud providers receive your question and patient profile content. Local models (Ollama) do not send data externally.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Test connection
            Button(
                onClick = { viewModel.testConnection() },
                modifier = Modifier.fillMaxWidth(),
                enabled = testResult !is SettingsViewModel.TestResult.Loading
            ) {
                when (testResult) {
                    is SettingsViewModel.TestResult.Loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing\u2026")
                    }
                    is SettingsViewModel.TestResult.Success -> {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Connection Successful")
                    }
                    is SettingsViewModel.TestResult.Error -> {
                        Icon(Icons.Default.Error, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Failed: ${(testResult as SettingsViewModel.TestResult.Error).message}")
                    }
                    null -> {
                        Text("Test Connection")
                    }
                }
            }

            // Save
            Button(
                onClick = {
                    viewModel.save()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSaving
            ) {
                Text("Save")
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
