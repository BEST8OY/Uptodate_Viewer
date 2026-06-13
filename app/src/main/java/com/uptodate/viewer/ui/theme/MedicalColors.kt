package com.uptodate.viewer.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Medical safety-critical colors — DO NOT MODIFY these values.
 * Ported from Python src/ui/styles/colors.py
 */
object MedicalColors {
    // Light theme
    val drugLight = Color(0xFF059669)
    val dangerLight = Color(0xFFE11D48)
    val cautionLight = Color(0xFFD97706)
    val gradeLight = Color(0xFF7C3AED)

    // Dark theme
    val drugDark = Color(0xFF34D399)
    val dangerDark = Color(0xFFFB7185)
    val cautionDark = Color(0xFFFBBF24)
    val gradeDark = Color(0xFFA78BFA)

    fun drug(isDark: Boolean) = if (isDark) drugDark else drugLight
    fun danger(isDark: Boolean) = if (isDark) dangerDark else dangerLight
    fun caution(isDark: Boolean) = if (isDark) cautionDark else cautionLight
    fun grade(isDark: Boolean) = if (isDark) gradeDark else gradeLight
}
