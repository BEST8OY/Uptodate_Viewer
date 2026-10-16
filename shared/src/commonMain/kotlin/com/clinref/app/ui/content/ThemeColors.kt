package com.clinref.app.ui.content

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

        fun dark() = ThemeColors(
            isDark = true,
            bg = "#121212",
            surface = "#1e1e1e",
            text = "#e0e0e0",
            textSecondary = "#a0a0a0",
            textTertiary = "#707070",
            border = "#2e2e2e",
            borderEmphasis = "#404040",
            primary = "#90CAF9",
            onPrimary = "#0d47a1",
            heading = "#f5f5f5",
            drug = "#34d399",
            danger = "#fb7185",
            caution = "#fbbf24",
            grade = "#a78bfa",
            selection = "#90CAF9",
            primaryContainer = "#00497d",
            onPrimaryContainer = "#d1e4ff",
            tertiaryContainer = "#4a0072",
            onTertiaryContainer = "#f3deff"
        )
    }
}
