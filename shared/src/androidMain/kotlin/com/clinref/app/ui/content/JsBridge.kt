package com.clinref.app.ui.content

import android.webkit.JavascriptInterface

class JsBridge(
    private val onAction: (String) -> Unit
) {
    @JavascriptInterface
    fun appAction(jsonStr: String) {
        onAction(jsonStr)
    }
}
