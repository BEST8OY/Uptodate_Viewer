package com.uptodate.viewer

import android.net.Uri
import android.os.Bundle
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
import com.uptodate.viewer.data.database.DatabaseManager
import com.uptodate.viewer.ui.main.MainScreen
import com.uptodate.viewer.ui.theme.UptodateTheme
import com.uptodate.viewer.util.DatabasePrefs
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

                LaunchedEffect(Unit) {
                    val savedUri = DatabasePrefs.getSavedDatabaseUri(applicationContext)
                    if (savedUri != null) {
                        val configured = withContext(Dispatchers.IO) {
                            databaseManager.configureFromUri(savedUri)
                        }
                        if (configured) {
                            hasDbState.value = true
                        }
                    }
                }

                AppContent(
                    hasDbState = hasDbState,
                    databaseManager = databaseManager,
                    scope = scope
                )
            }
        }
    }
}

@Composable
private fun AppContent(
    hasDbState: androidx.compose.runtime.MutableState<Boolean>,
    databaseManager: DatabaseManager,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var hasDatabases by hasDbState

    val dirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    databaseManager.configureFromUri(uri)
                }
                if (success) {
                    DatabasePrefs.saveDatabaseUri(uri, databaseManager.context)
                    hasDatabases = true
                }
            }
        }
    }

    MainScreen(
        hasDatabases = hasDatabases,
        onDatabaseConfigured = { hasDatabases = true },
        onSelectDatabaseDir = { dirPicker.launch(null) }
    )
}
