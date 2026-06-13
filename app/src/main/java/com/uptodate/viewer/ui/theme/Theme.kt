package com.uptodate.viewer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun UptodateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }.copy(
        error = if (darkTheme) MedicalColors.dangerDark else MedicalColors.dangerLight,
        onError = if (darkTheme) Color(0xFF601410) else Color.White,
    )

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}

/**
 * Returns the CSS-safe hex colors for the current theme,
 * including medical safety colors.
 */
object ThemeColors {
    val isDark: Boolean
        @Composable get() = isSystemInDarkTheme()

    val textColor: Color
        @Composable get() = MaterialTheme.colorScheme.onBackground

    val bgColor: Color
        @Composable get() = MaterialTheme.colorScheme.background

    val surfaceColor: Color
        @Composable get() = MaterialTheme.colorScheme.surface

    val linkColor: Color
        @Composable get() = MaterialTheme.colorScheme.primary

    val borderColor: Color
        @Composable get() = MaterialTheme.colorScheme.outlineVariant

    val selectionColor: Color
        @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)

    val drugColor: Color
        @Composable get() = MedicalColors.drug(isDark)

    val dangerColor: Color
        @Composable get() = MedicalColors.danger(isDark)

    val cautionColor: Color
        @Composable get() = MedicalColors.caution(isDark)

    val gradeColor: Color
        @Composable get() = MedicalColors.grade(isDark)
}
