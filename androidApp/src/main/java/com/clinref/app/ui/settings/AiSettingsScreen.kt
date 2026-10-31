package com.clinref.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.ai.AiProvider
import com.clinref.app.ui.common.BackHandler
import kotlin.math.abs
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()

    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showModelSheet) {
        showModelSheet = false
    }

    Scaffold(
        topBar = {
            MediumTopAppBar(
                title = {
                    Text(
                        "AI Orchestration",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.save()
                            onBack()
                        },
                        enabled = !isSaving
                    ) {
                        Text(
                            "Save",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing
                                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                                .exclude(WindowInsets.ime)
                        )
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.testConnection() },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = testResult !is SettingsViewModel.TestResult.Loading
                    ) {
                        Text("Test Connection", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            viewModel.save()
                            onBack()
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        enabled = !isSaving
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // PANEL 1: API CONNECTION & PROVIDER SELECTION
            SettingsGroupCard(
                title = "Endpoint Configuration",
                icon = Icons.Default.Cloud
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Gateway Provider", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = "Select an API inference backend or local on-device runtime",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Modern Selectable Provider Cards (2-column responsive layout)
                    ProviderSelectionGrid(
                        selectedProvider = configuration.provider,
                        onProviderSelected = { viewModel.updateProvider(it) }
                    )

                    // Base URL for custom endpoints
                    if (configuration.provider == AiProvider.OLLAMA ||
                        configuration.provider == AiProvider.OPENROUTER ||
                        configuration.provider == AiProvider.MISTRAL
                    ) {
                        OutlinedTextField(
                            value = configuration.baseUrl,
                            onValueChange = { viewModel.updateBaseUrl(it) },
                            label = { Text("Base Gateway URL") },
                            placeholder = {
                                Text(
                                    when (configuration.provider) {
                                        AiProvider.OLLAMA -> "http://localhost:11434"
                                        AiProvider.MISTRAL -> "https://api.mistral.ai"
                                        else -> "https://openrouter.ai/api/v1"
                                    }
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // API Key
                    if (configuration.provider != AiProvider.OLLAMA) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { viewModel.updateApiKey(it) },
                            label = { Text("API Credential Key") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { showApiKey = !showApiKey }) {
                                    Icon(
                                        imageVector = if (showApiKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (showApiKey) "Hide API key" else "Show API key"
                                    )
                                }
                            }
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Keystore Encrypted (Local Storage)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // PANEL 2: MODEL PARAMETERS
            SettingsGroupCard(
                title = "Model Parameters",
                icon = Icons.Default.Tune
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    // Model Picker (Searchable Modal BottomSheet with Category Filters & Tags)
                    Text("Inference Model", style = MaterialTheme.typography.labelMedium)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { showModelSheet = true }
                    ) {
                        OutlinedTextField(
                            value = configuration.model.ifBlank { "Select a model..." },
                            onValueChange = {},
                            readOnly = true,
                            enabled = false,
                            colors = OutlinedTextFieldDefaults.colors(
                                disabledTextColor = if (configuration.model.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                disabledBorderColor = MaterialTheme.colorScheme.outline,
                                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                disabledTrailingIconColor = MaterialTheme.colorScheme.primary,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                            ),
                            label = { Text("Model Identifier") },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = "Choose model"
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = {
                                Text(
                                    "Tap to choose from presets or enter custom slug",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        )
                    }

                    if (showModelSheet) {
                        ModelSelectionSheet(
                            currentModel = configuration.model,
                            availableModels = availableModels,
                            onModelSelected = { viewModel.updateModel(it) },
                            onDismiss = { showModelSheet = false }
                        )
                    }

                    // Temperature Presets & Fine-Tuning Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Inference Temperature", style = MaterialTheme.typography.labelMedium)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Text(
                                    text = String.format("%.2f", configuration.temperature),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Clinical Mode Presets (Segmented Buttons)
                        val currentTemp = configuration.temperature
                        val selectedPresetIndex = when {
                            abs(currentTemp - 0.0f) < 0.05f -> 0
                            abs(currentTemp - 0.3f) < 0.05f -> 1
                            abs(currentTemp - 0.7f) < 0.05f -> 2
                            else -> -1
                        }

                        val presets = listOf(
                            "Strict (0.0)" to 0.0f,
                            "Clinical (0.3)" to 0.3f,
                            "Creative (0.7)" to 0.7f
                        )

                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            presets.forEachIndexed { index, (label, temp) ->
                                SegmentedButton(
                                    selected = selectedPresetIndex == index,
                                    onClick = { viewModel.updateTemperature(temp) },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = presets.size)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = when {
                                currentTemp < 0.1f -> "Strict: Maximally deterministic for dosing, interactions, and guideline lookups."
                                currentTemp in 0.1f..0.5f -> "Clinical: Balanced reasoning for clinical summaries and differential evaluation."
                                else -> "Creative: Higher diversity for exploratory brainstorming; verify medical facts."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Slider(
                            value = configuration.temperature,
                            onValueChange = { viewModel.updateTemperature(it) },
                            valueRange = 0f..1f,
                            steps = 19,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("0.0 (Strict)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("0.5 (Balanced)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("1.0 (Creative)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Token Limits
                    OutlinedTextField(
                        value = configuration.maxTokens.toString(),
                        onValueChange = { it.toIntOrNull()?.let { tokens -> viewModel.updateMaxTokens(tokens) } },
                        label = { Text("Max Output Tokens") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    // Provider-Specific Settings
                    when (val settings = configuration.providerSettings) {
                        is com.clinref.app.domain.ai.ProviderSettings.Google ->
                            GoogleSettingsPanel(settings) { viewModel.updateProviderSettings(it) }
                        is com.clinref.app.domain.ai.ProviderSettings.OpenAI ->
                            OpenAISettingsPanel(settings) { viewModel.updateProviderSettings(it) }
                        is com.clinref.app.domain.ai.ProviderSettings.Anthropic ->
                            AnthropicSettingsPanel(settings) { viewModel.updateProviderSettings(it) }
                        is com.clinref.app.domain.ai.ProviderSettings.MistralAI ->
                            MistralAISettingsPanel(settings) { viewModel.updateProviderSettings(it) }
                        is com.clinref.app.domain.ai.ProviderSettings.OpenRouter ->
                            OpenRouterSettingsPanel(settings) { viewModel.updateProviderSettings(it) }
                        is com.clinref.app.domain.ai.ProviderSettings.Ollama ->
                            OllamaSettingsPanel(settings) { viewModel.updateProviderSettings(it) }
                    }
                }
            }

            // PANEL 3: RATE LIMITS & MEMORY
            SettingsGroupCard(
                title = "Memory & Rate Controls",
                icon = Icons.Default.Speed
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = configuration.historyCompressionThreshold.toString(),
                        onValueChange = { it.toIntOrNull()?.let { threshold -> viewModel.updateHistoryThreshold(threshold) } },
                        label = { Text("Compression Threshold (Tokens)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) }
                    )
                }
            }

            // PANEL 4: DATA COMPLIANCE NOTICE
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Security notice",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "Data Handling",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Cloud providers receive your question and patient profile content. " +
                                "Local models (Ollama) do not send data externally. " +
                                "API keys are stored on device only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // DYNAMIC CONNECTION TEST AREA
            AnimatedVisibility(
                visible = testResult != null,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                testResult?.let { result ->
                    val statusColor = when (result) {
                        is SettingsViewModel.TestResult.Success -> MaterialTheme.colorScheme.primaryContainer
                        is SettingsViewModel.TestResult.Error -> MaterialTheme.colorScheme.errorContainer
                        SettingsViewModel.TestResult.Loading -> MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                    val textColor = when (result) {
                        is SettingsViewModel.TestResult.Success -> MaterialTheme.colorScheme.onPrimaryContainer
                        is SettingsViewModel.TestResult.Error -> MaterialTheme.colorScheme.onErrorContainer
                        SettingsViewModel.TestResult.Loading -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(statusColor)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        when (result) {
                            SettingsViewModel.TestResult.Loading -> {
                                ContainedLoadingIndicator(
                                    modifier = Modifier.size(36.dp),
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    indicatorColor = MaterialTheme.colorScheme.primary
                                )
                            }
                            is SettingsViewModel.TestResult.Success -> {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = "Succeeded",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            is SettingsViewModel.TestResult.Error -> {
                                Icon(
                                    Icons.Default.Error,
                                    contentDescription = "Failed",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = when (result) {
                                SettingsViewModel.TestResult.Loading -> "Testing active connection..."
                                is SettingsViewModel.TestResult.Success -> "Gateway verified successfully!"
                                is SettingsViewModel.TestResult.Error -> "Failed: ${result.message}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = textColor,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ProviderSelectionGrid(
    selectedProvider: AiProvider,
    onProviderSelected: (AiProvider) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val providers = AiProvider.entries
        providers.chunked(2).forEach { rowProviders ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowProviders.forEach { provider ->
                    ProviderCard(
                        provider = provider,
                        isSelected = selectedProvider == provider,
                        onClick = { onProviderSelected(provider) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowProviders.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: AiProvider,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isLocal = provider == AiProvider.OLLAMA
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isLocal) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isLocal) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        } else {
                            val monogram = when (provider) {
                                AiProvider.GOOGLE -> "G"
                                AiProvider.ANTHROPIC -> "A"
                                AiProvider.OPENAI -> "OA"
                                AiProvider.MISTRAL -> "M"
                                AiProvider.OPENROUTER -> "OR"
                                AiProvider.OLLAMA -> "OL"
                            }
                            Text(
                                text = monogram,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = provider.displayName,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = when {
                    isLocal -> "Private (Local)"
                    provider == AiProvider.OPENROUTER -> "200+ Models"
                    else -> "Cloud Gateway"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isLocal) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
