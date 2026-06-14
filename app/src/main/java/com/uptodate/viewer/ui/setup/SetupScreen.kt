package com.uptodate.viewer.ui.setup

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val isConfigured by viewModel.isConfigured.collectAsState()
    val error by viewModel.error.collectAsState()
    val availableDbs by viewModel.availableDbs.collectAsState()
    val missingDbs by viewModel.missingDbs.collectAsState()
    val isValidating by viewModel.isValidating.collectAsState()
    var directoryPath by remember { mutableStateOf("") }
    val context = LocalContext.current

    if (isConfigured) {
        onSetupComplete()
        return
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { /* re-check happens on recomposition */ }

    val hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    val requiredFiles = listOf(
        "unidex.en.sqlite" to "Index",
        "utdtoc.db" to "Table of Contents",
        "fsearch.db" to "Full-text Search",
        "fcontentsearch.db" to "Content Search",
        "utdasset.sqlite" to "Assets",
        "utdqf.sqlite" to "Quick Facts"
    )

    val allFound = requiredFiles.all { (name, _) -> availableDbs.any { it == name } }
    val canSubmit = directoryPath.isNotBlank() && hasStoragePermission && allFound && !isValidating

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "UpToDate Viewer",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Point the app to your UpToDate database folder to get started.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Step 1: Permission
            StepHeader(step = 1, title = "Storage Access", done = hasStoragePermission)

            AnimatedVisibility(visible = !hasStoragePermission) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "The app needs access to files on your device to read the database.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                            }
                            manageStorageLauncher.launch(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Grant Permission")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Step 2: Directory
            StepHeader(step = 2, title = "Database Directory", done = allFound && directoryPath.isNotBlank())

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = directoryPath,
                onValueChange = {
                    directoryPath = it
                    if (hasStoragePermission) viewModel.checkDirectory(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Path to database folder") },
                placeholder = { Text("/storage/emulated/0/UptodateDB") },
                singleLine = true,
                enabled = hasStoragePermission
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Paste or type the full path to the folder containing the .sqlite and .db files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Step 3: File Status
            if (directoryPath.isNotBlank() && hasStoragePermission) {
                Spacer(modifier = Modifier.height(20.dp))

                StepHeader(step = 3, title = "File Verification", done = allFound)

                Spacer(modifier = Modifier.height(12.dp))

                requiredFiles.forEach { (fileName, label) ->
                    val found = availableDbs.any { it == fileName }
                    FileStatusRow(fileName = fileName, label = label, found = found)
                }
            }

            // Error
            error?.let { errorMsg ->
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = errorMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Submit
            Button(
                onClick = { viewModel.selectDirectory(directoryPath) },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit
            ) {
                if (isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isValidating) "Validating..." else "Start Using App")
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = false
            ) {
                Text("Browse (not supported yet)")
            }
        }
    }
}

@Composable
private fun StepHeader(step: Int, title: String, done: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "Step $step",
            style = MaterialTheme.typography.labelMedium,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (done) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Done",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun FileStatusRow(fileName: String, label: String, found: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = if (found) Icons.Default.CheckCircle else Icons.Default.Error,
            contentDescription = null,
            tint = if (found) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
