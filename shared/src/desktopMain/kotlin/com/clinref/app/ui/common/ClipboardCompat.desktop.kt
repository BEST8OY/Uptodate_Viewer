package com.clinref.app.ui.common

import androidx.compose.ui.platform.Clipboard
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

internal actual fun Clipboard.setPlainText(text: String) {
    val awtClipboard = Toolkit.getDefaultToolkit().systemClipboard
    awtClipboard.setContents(StringSelection(text), null)
}
