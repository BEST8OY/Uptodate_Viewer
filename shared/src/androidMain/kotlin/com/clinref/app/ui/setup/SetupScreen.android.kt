package com.clinref.app.ui.setup

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import com.clinref.app.ui.common.BackHandler
import java.io.File

@Composable
actual fun SetupScreen(
    onSetupComplete: () -> Unit,
    viewModel: SetupViewModel
) {
    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val isValidating by viewModel.isValidating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var navigated by remember { mutableStateOf(false) }

    val defaultDbPath = remember {
        File(Environment.getExternalStorageDirectory(), "UpToDateDB").absolutePath
    }

    BackHandler { }

    fun checkStoragePermission() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    var hasStoragePermission by remember { mutableStateOf(checkStoragePermission()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasStoragePermission = checkStoragePermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = checkStoragePermission()
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            val rawPath = it.path ?: return@let
            val resolvedPath = if (rawPath.contains(":")) {
                val rel = rawPath.substringAfter(":")
                File(Environment.getExternalStorageDirectory(), rel).absolutePath
            } else {
                rawPath
            }
            viewModel.selectDirectory(resolvedPath)
        }
    }

    LaunchedEffect(hasStoragePermission, isConfigured) {
        if (hasStoragePermission && !isConfigured && !isValidating) {
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
        title = "UpToDate Viewer",
        description = if (!hasStoragePermission) {
            "Storage permission is required to access UpToDate database files."
        } else {
            "Point to directory containing SQLite databases\n(utdasset.sqlite, unidex.en.sqlite, fsearch.db, etc.)"
        },
        isValidating = isValidating,
        error = error
    ) {
        if (!hasStoragePermission) {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        manageStorageLauncher.launch(intent)
                    }
                },
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Grant Storage Permission")
            }
        } else {
            val adaptiveInfo = LocalWindowAdaptiveInfo.current
            val showRetry = error != null || !isValidating

            val actionButtons: @Composable (Modifier) -> Unit = { buttonModifier ->
                OutlinedButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    enabled = !isValidating,
                    modifier = buttonModifier,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Choose Folder...")
                }

                if (showRetry) {
                    Button(
                        onClick = { viewModel.selectDirectory(defaultDbPath) },
                        enabled = !isValidating,
                        modifier = buttonModifier,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry /UpToDateDB")
                    }
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
        }
    }
}
