package com.clinref.app.ui.setup

import androidx.compose.runtime.Composable
import org.koin.compose.viewmodel.koinViewModel

@Composable
expect fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = koinViewModel()
)

