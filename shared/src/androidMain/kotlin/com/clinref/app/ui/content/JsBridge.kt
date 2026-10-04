package com.clinref.app.ui.content

import android.webkit.JavascriptInterface

class JsBridge(
    private val onAction: (String) -> Unit,
    private val onScrollReport: (String) -> Unit = {}
) {
    @JavascriptInterface
    fun appAction(jsonStr: String) {
        onAction(jsonStr)
    }

    /** Receives a debounced reading-position JSON payload from the in-page script. */
    @JavascriptInterface
    fun onScroll(payload: String) {
        onScrollReport(payload)
    }
}
