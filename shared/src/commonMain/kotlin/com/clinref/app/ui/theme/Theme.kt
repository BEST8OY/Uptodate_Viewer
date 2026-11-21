package com.clinref.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable

@Composable
expect fun rememberDynamicColorScheme(darkTheme: Boolean): ColorScheme?

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ClinRefTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val dynamicColors = if (dynamicColor) rememberDynamicColorScheme(darkTheme) else null
    val colorScheme = when {
        dynamicColors != null -> dynamicColors
        darkTheme -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        content = content
    )
}
