package com.clinref.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.clinref.app.data.DatabaseManager
import com.clinref.app.ui.navigation.NavGraph
import com.clinref.app.ui.theme.ClinRefTheme
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val databaseManager: DatabaseManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ClinRefTheme {
                NavGraph(databaseManager = databaseManager)
            }
        }
    }
}
