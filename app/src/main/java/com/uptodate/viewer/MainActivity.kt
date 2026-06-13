package com.uptodate.viewer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.uptodate.viewer.data.database.DatabaseManager
import com.uptodate.viewer.ui.main.MainScreen
import com.uptodate.viewer.ui.theme.UptodateTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var databaseManager: DatabaseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            UptodateTheme {
                val scope = rememberCoroutineScope()
                val hasDbState = remember { mutableStateOf(false) }
                var hasPermission by remember { mutableStateOf(checkStoragePermission()) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) {
                    hasPermission = checkStoragePermission()
                }

                LaunchedEffect(hasPermission) {
                    if (hasPermission) {
                        val configured = withContext(Dispatchers.IO) {
                            databaseManager.configureFromDefaultPath()
                        }
                        if (configured) {
                            hasDbState.value = true
                        }
                    }
                }

                AppContent(
                    hasDbState = hasDbState,
                    hasPermission = hasPermission,
                    onRequestPermission = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        permissionLauncher.launch(intent)
                    }
                )
            }
        }
    }

    private fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
private fun AppContent(
    hasDbState: androidx.compose.runtime.MutableState<Boolean>,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit
) {
    var hasDatabases by hasDbState

    MainScreen(
        hasDatabases = hasDatabases,
        hasPermission = hasPermission,
        onRequestPermission = onRequestPermission
    )
}
