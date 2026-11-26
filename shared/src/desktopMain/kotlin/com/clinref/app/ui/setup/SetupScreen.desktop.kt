package com.clinref.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

@Composable
actual fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel
) {
    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isValidating by viewModel.isValidating.collectAsStateWithLifecycle()
    var navigated by remember { mutableStateOf(false) }

    val defaultDbPath = remember {
        File(".").absolutePath
    }
    val manualPathState = rememberTextFieldState()
    val adaptiveInfo = LocalWindowAdaptiveInfo.current

    LaunchedEffect(isConfigured) {
        if (!isConfigured && !isValidating) {
            viewModel.selectDirectory(defaultDbPath)
        }
    }

    LaunchedEffect(isConfigured) {
        if (isConfigured && !navigated) {
            navigated = true
            onSetupComplete()
        }
    }

    SetupScreenShell(
        title = "ClinRef Desktop",
        description = "Point to directory containing SQLite clinical databases\n(utdasset.sqlite, unidex.en.sqlite, fsearch.db, etc.)",
        isValidating = isValidating,
        error = error
    ) {
        val actionButtons: @Composable (Modifier) -> Unit = { buttonModifier ->
            OutlinedButton(
                onClick = {
                    SwingUtilities.invokeLater {
                        val chooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Select Database Directory"
                            currentDirectory = File(".")
                        }
                        val result = chooser.showOpenDialog(null)
                        if (result == JFileChooser.APPROVE_OPTION) {
                            val path = chooser.selectedFile.absolutePath
                            manualPathState.edit { replace(0, length, path) }
                            viewModel.selectDirectory(path)
                        }
                    }
                },
                enabled = !isValidating,
                modifier = buttonModifier.pointerHoverIcon(if (!isValidating) PointerIcon.Hand else PointerIcon.Default),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Browse Folder...")
            }

            Button(
                onClick = { viewModel.selectDirectory(defaultDbPath) },
                enabled = !isValidating,
                modifier = buttonModifier.pointerHoverIcon(if (!isValidating) PointerIcon.Hand else PointerIcon.Default),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check Working Dir")
            }
        }

        if (adaptiveInfo.isCompact) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                actionButtons(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actionButtons(Modifier)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                state = manualPathState,
                label = { Text("Or enter path manually") },
                placeholder = { Text("/path/to/databases") },
                lineLimits = TextFieldLineLimits.SingleLine,
                modifier = Modifier.weight(1f),
                enabled = !isValidating,
                shape = MaterialTheme.shapes.medium
            )

            val isManualPathNotBlank = manualPathState.text.isNotBlank()
            Button(
                onClick = {
                    val trimmed = manualPathState.text.toString().trim()
                    if (trimmed.isNotBlank()) {
                        viewModel.selectDirectory(trimmed)
                    }
                },
                enabled = !isValidating && isManualPathNotBlank,
                modifier = Modifier.pointerHoverIcon(
                    if (!isValidating && isManualPathNotBlank) PointerIcon.Hand else PointerIcon.Default
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Open")
            }
        }
    }
}
