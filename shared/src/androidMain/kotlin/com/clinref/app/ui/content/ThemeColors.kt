package com.clinref.app.ui.content

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

data class ThemeColors(
    val isDark: Boolean,
    val bg: String,
    val surface: String,
    val text: String,
    val textSecondary: String,
    val textTertiary: String,
    val border: String,
    val borderEmphasis: String,
    val primary: String,
    val onPrimary: String,
    val heading: String,
    val drug: String,
    val danger: String,
    val caution: String,
    val grade: String,
    val selection: String,
    val primaryContainer: String,
    val onPrimaryContainer: String,
    val tertiaryContainer: String,
    val onTertiaryContainer: String
) {
    companion object {
        fun light() = ThemeColors(
            isDark = false,
            bg = "#ffffff",
            surface = "#f5f5f5",
            text = "#000000",
            textSecondary = "#666666",
            textTertiary = "#999999",
            border = "#e0e0e0",
            borderEmphasis = "#cccccc",
            primary = "#1976D2",
            onPrimary = "#ffffff",
            heading = "#1a1a1a",
            drug = "#059669",
            danger = "#e11d48",
            caution = "#d97706",
            grade = "#7c3aed",
            selection = "#1976D2",
            primaryContainer = "#d1e4ff",
            onPrimaryContainer = "#001d36",
            tertiaryContainer = "#f3deff",
            onTertiaryContainer = "#31004a"
        )

        fun fromColorScheme(colorScheme: ColorScheme): ThemeColors {
            val bg = colorScheme.background
            val isDark = bg.luminance() < 0.5f

            val bgHex = bg.toHexString()
            val surface = colorScheme.surface.toHexString()
            val text = colorScheme.onBackground.toHexString()
            val textSecondary = colorScheme.onSurfaceVariant.toHexString()
            val textTertiary = colorScheme.outline.toHexString()
            val border = colorScheme.outlineVariant.toHexString()
            val borderEmphasis = colorScheme.outline.toHexString()
            val primary = colorScheme.primary.toHexString()
            val onPrimary = colorScheme.onPrimary.toHexString()
            val selection = colorScheme.primary.toHexString()

            val medicalColors = if (isDark) {
                MedicalColors(
                    drug = "#34d399",
                    danger = "#fb7185",
                    caution = "#fbbf24",
                    grade = "#a78bfa"
                )
            } else {
                MedicalColors(
                    drug = "#059669",
                    danger = "#e11d48",
                    caution = "#d97706",
                    grade = "#7c3aed"
                )
            }

            return ThemeColors(
                isDark = isDark,
                bg = bgHex,
                surface = surface,
                text = text,
                textSecondary = textSecondary,
                textTertiary = textTertiary,
                border = border,
                borderEmphasis = borderEmphasis,
                primary = primary,
                onPrimary = onPrimary,
                heading = colorScheme.onBackground.toHexString(),
                drug = medicalColors.drug,
                danger = medicalColors.danger,
                caution = medicalColors.caution,
                grade = medicalColors.grade,
                selection = selection,
                primaryContainer = colorScheme.primaryContainer.toHexString(),
                onPrimaryContainer = colorScheme.onPrimaryContainer.toHexString(),
                tertiaryContainer = colorScheme.tertiaryContainer.toHexString(),
                onTertiaryContainer = colorScheme.onTertiaryContainer.toHexString()
            )
        }
    }
}

private data class MedicalColors(
    val drug: String,
    val danger: String,
    val caution: String,
    val grade: String
)

private fun Color.toHexString(): String {
    val argb = toArgb()
    return String.format("#%06X", argb and 0xFFFFFF)
}
