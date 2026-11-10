package com.clinref.app.ui.content

import android.webkit.JavascriptInterface

class JsBridge(
    private val onScrollReport: (String) -> Unit
) {
    /** Receives a debounced reading-position JSON payload from the in-page script. */
    @JavascriptInterface
    fun onScroll(payload: String) {
        onScrollReport(payload)
    }
}
