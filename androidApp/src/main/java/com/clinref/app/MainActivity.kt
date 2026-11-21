package com.clinref.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import com.clinref.app.data.DatabaseManager
import com.clinref.app.ui.navigation.NavGraph
import com.clinref.app.ui.theme.ClinRefTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val databaseManager: DatabaseManager by inject()

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // Workaround for upstream Jetpack Compose Foundation bug b/519816995:
        // https://issuetracker.google.com/issues/519816995
        // "New Context Menu implementation flickers on item changes, showing 'select all' on disposing"
        // In Compose Foundation 1.9+, AndroidTextContextMenuToolbarProvider races with
        // ActionMode teardown when text selection clears upon tap-outside: SelectionManager
        // invalidates the ActionMode when selection == null, causing Android to briefly flash
        // a "Select all"-only toolbar right before ActionMode.finish() executes.
        // Disabling isNewContextMenuEnabled restores the stable text selection pipeline, keeping
        // "Select all" available during active selection while eliminating the transient dismiss flash.
        // Once b/519816995 is resolved in a future Compose Foundation release, this flag can be removed.
        ComposeFoundationFlags.isNewContextMenuEnabled = false
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClinRefTheme {
                NavGraph(databaseManager = databaseManager)
            }
        }
    }
}
