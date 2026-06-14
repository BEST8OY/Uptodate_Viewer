package com.uptodate.viewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.uptodate.viewer.data.DatabaseManager
import com.uptodate.viewer.ui.navigation.NavGraph
import com.uptodate.viewer.ui.theme.UptodateViewerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var databaseManager: DatabaseManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UptodateViewerTheme {
                NavGraph(databaseManager = databaseManager)
            }
        }
    }
}
