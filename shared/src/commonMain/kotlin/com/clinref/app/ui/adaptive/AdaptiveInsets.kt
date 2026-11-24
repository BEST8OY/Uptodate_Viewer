package com.clinref.app.ui.adaptive

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable

val TopAndHorizontalSafeInsets: WindowInsets
    @Composable
    get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

val HorizontalSafeInsets: WindowInsets
    @Composable
    get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

val BottomSafeInsetsExcludingIme: WindowInsets
    @Composable
    get() = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Bottom)
        .exclude(WindowInsets.ime)

val BottomAndHorizontalSafeInsetsExcludingIme: WindowInsets
    @Composable
    get() = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
        .exclude(WindowInsets.ime)

val EndSafeInsets: WindowInsets
    @Composable
    get() = WindowInsets.safeDrawing.only(WindowInsetsSides.End)
