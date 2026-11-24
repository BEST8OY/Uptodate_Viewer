package com.clinref.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import javax.swing.JFileChooser

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
    var manualPath by remember { mutableStateOf("") }

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
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    val chooser = JFileChooser().apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = "Select Database Directory"
                        currentDirectory = File(".")
                    }
                    val result = chooser.showOpenDialog(null)
                    if (result == JFileChooser.APPROVE_OPTION) {
                        val path = chooser.selectedFile.absolutePath
                        manualPath = path
                        viewModel.selectDirectory(path)
                    }
                },
                enabled = !isValidating,
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

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = manualPath,
                onValueChange = { manualPath = it },
                label = { Text("Or enter path manually") },
                placeholder = { Text("/path/to/databases") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = !isValidating,
                shape = MaterialTheme.shapes.medium
            )

            Button(
                onClick = {
                    if (manualPath.isNotBlank()) {
                        viewModel.selectDirectory(manualPath.trim())
                    }
                },
                enabled = !isValidating && manualPath.isNotBlank(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Open")
            }
        }
    }
}
