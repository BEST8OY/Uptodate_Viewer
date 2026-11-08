package com.clinref.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.ai.AiConfiguration
import com.clinref.app.domain.ai.AiProvider
import com.clinref.app.domain.ai.ProviderSettings
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.adaptive.LocalWindowWidthClass
import com.clinref.app.ui.adaptive.WindowWidthClass
import com.clinref.app.ui.common.BackHandler
import kotlin.math.abs
import kotlin.math.roundToInt
import org.koin.compose.viewmodel.koinViewModel

/**
 * Stateful entry point collecting MVI StateFlows from SettingsViewModel.
 */
@Composable
fun AiSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    val isSaving by viewModel.isSaving.collectAsStateWithLifecycle()

    AiSettingsContent(
        configuration = configuration,
        apiKey = apiKey,
        availableModels = availableModels,
        testResult = testResult,
        isSaving = isSaving,
        onBack = onBack,
        onSave = {
            viewModel.save()
            onBack()
        },
        onTestConnection = { viewModel.testConnection() },
        onUpdateProvider = { viewModel.updateProvider(it) },
        onUpdateModel = { viewModel.updateModel(it) },
        onUpdateApiKey = { viewModel.updateApiKey(it) },
        onUpdateBaseUrl = { viewModel.updateBaseUrl(it) },
        onUpdateTemperature = { viewModel.updateTemperature(it) },
        onUpdateMaxTokens = { viewModel.updateMaxTokens(it) },
        onUpdateProviderSettings = { viewModel.updateProviderSettings(it) },
        modifier = modifier
    )
}

/**
 * Pure stateless settings content composable, adhering to M3 and UDF principles.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiSettingsContent(
    configuration: AiConfiguration,
    apiKey: String,
    availableModels: List<String>,
    testResult: SettingsViewModel.TestResult?,
    isSaving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
    onUpdateProvider: (AiProvider) -> Unit,
    onUpdateModel: (String) -> Unit,
    onUpdateApiKey: (String) -> Unit,
    onUpdateBaseUrl: (String) -> Unit,
    onUpdateTemperature: (Float) -> Unit,
    onUpdateMaxTokens: (Int) -> Unit,
    onUpdateProviderSettings: (ProviderSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var showModelSheet by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    BackHandler(enabled = showModelSheet) {
        showModelSheet = false
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumTopAppBar(
                title = {
                    Text(
                        text = "AI Orchestration",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .widthIn(max = AdaptiveTokens.MaxFormContentWidth)
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
                            onClick = onTestConnection,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = ButtonDefaults.outlinedShape,
                            enabled = testResult !is SettingsViewModel.TestResult.Loading
                        ) {
                            Text("Test Connection", style = MaterialTheme.typography.labelLarge)
                        }

                        Button(
                            onClick = onSave,
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = ButtonDefaults.shape,
                            enabled = !isSaving
                        ) {
                            Text("Save", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = AdaptiveTokens.MaxFormContentWidth)
                    .fillMaxWidth()
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

                        // Modern Selectable Provider Cards (2 or 3 column responsive layout)
                        ProviderSelectionGrid(
                            selectedProvider = configuration.provider,
                            onProviderSelected = onUpdateProvider
                        )

                        // Base URL for custom endpoints
                        if (configuration.provider == AiProvider.OLLAMA ||
                            configuration.provider == AiProvider.OPENROUTER ||
                            configuration.provider == AiProvider.MISTRAL
                        ) {
                            OutlinedTextField(
                                value = configuration.baseUrl,
                                onValueChange = onUpdateBaseUrl,
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
                                shape = MaterialTheme.shapes.medium,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Next
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // API Key
                        if (configuration.provider != AiProvider.OLLAMA) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = onUpdateApiKey,
                                label = { Text("API Credential Key") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.medium,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
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
                        // Accessible Model Picker
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Inference Model", style = MaterialTheme.typography.labelMedium)
                            ModelPickerField(
                                model = configuration.model,
                                onClick = { showModelSheet = true }
                            )
                        }

                        if (showModelSheet) {
                            ModelSelectionSheet(
                                currentModel = configuration.model,
                                availableModels = availableModels,
                                onModelSelected = onUpdateModel,
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
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    val hundredths = (configuration.temperature * 100).roundToInt()
                                    val tempFormatted = "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
                                    Text(
                                        text = tempFormatted,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            val sliderState = rememberSliderState(
                                value = configuration.temperature,
                                steps = 19,
                                trackRange = 0f..1f
                            )

                            LaunchedEffect(configuration.provider, configuration.temperature) {
                                if (abs(sliderState.value - configuration.temperature) > 0.001f) {
                                    sliderState.value = configuration.temperature
                                }
                            }

                            Slider(
                                state = sliderState,
                                onValueChange = onUpdateTemperature,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("0.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("0.5", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("1.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // Token Limits
                        OutlinedTextField(
                            value = configuration.maxTokens.toString(),
                            onValueChange = { it.toIntOrNull()?.let { tokens -> onUpdateMaxTokens(tokens) } },
                            label = { Text("Max Output Tokens") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            )
                        )

                        // Provider-Specific Settings
                        when (val settings = configuration.providerSettings) {
                            is ProviderSettings.Google ->
                                GoogleSettingsPanel(settings, onUpdate = onUpdateProviderSettings)
                            is ProviderSettings.OpenAI ->
                                OpenAISettingsPanel(settings, onUpdate = onUpdateProviderSettings)
                            is ProviderSettings.Anthropic ->
                                AnthropicSettingsPanel(settings, onUpdate = onUpdateProviderSettings)
                            is ProviderSettings.MistralAI ->
                                MistralAISettingsPanel(settings, onUpdate = onUpdateProviderSettings)
                            is ProviderSettings.OpenRouter ->
                                OpenRouterSettingsPanel(settings, onUpdate = onUpdateProviderSettings)
                            is ProviderSettings.Ollama ->
                                OllamaSettingsPanel(settings, onUpdate = onUpdateProviderSettings)
                        }
                    }
                }

                // PANEL 3: DATA COMPLIANCE NOTICE
                Card(
                    shape = MaterialTheme.shapes.large,
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
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Data Handling",
                                style = MaterialTheme.typography.titleSmall
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

                // DYNAMIC CONNECTION TEST AREA WITH MONOTONIC EXIT MOTION
                var lastResult by remember { mutableStateOf(testResult) }
                LaunchedEffect(testResult) {
                    if (testResult != null) {
                        lastResult = testResult
                    }
                }

                AnimatedVisibility(
                    visible = testResult != null,
                    enter = fadeIn(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
                        expandVertically(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec()),
                    exit = fadeOut(animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()) +
                        shrinkVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        )
                ) {
                    (testResult ?: lastResult)?.let { result ->
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

                        Surface(
                            color = statusColor,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                    color = textColor
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * Accessible Model Picker Control replacing the disabled OutlinedTextField anti-pattern.
 */
@Composable
private fun ModelPickerField(
    model: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = if (model.isBlank()) {
                    "Choose inference model"
                } else {
                    "Selected model: $model. Tap to change."
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Model Identifier",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = model.ifBlank { "Select a model..." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (model.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tap to choose from presets or enter custom slug",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.Default.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ProviderSelectionGrid(
    selectedProvider: AiProvider,
    onProviderSelected: (AiProvider) -> Unit,
    modifier: Modifier = Modifier
) {
    val widthClass = LocalWindowWidthClass.current
    val columns = if (widthClass == WindowWidthClass.EXPANDED) 3 else 2

    val providers = remember { AiProvider.entries }
    val chunkedProviders = remember(columns) { providers.chunked(columns) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chunkedProviders.forEach { rowProviders ->
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
                repeat(columns - rowProviders.size) {
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
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }
    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    OutlinedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = modifier.semantics {
            role = Role.RadioButton
            selected = isSelected
        }
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
                    shape = MaterialTheme.shapes.small,
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
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
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
                style = MaterialTheme.typography.bodyMedium,
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
                color = if (isLocal) {
                    MaterialTheme.colorScheme.secondary
                } else if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
