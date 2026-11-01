package com.clinref.app.ui.common

import android.content.ClipData
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.nativeClipboardManager

internal actual fun Clipboard.setPlainText(text: String) {
    nativeClipboardManager.setPrimaryClip(
        ClipData.newPlainText("text", text)
    )
}
