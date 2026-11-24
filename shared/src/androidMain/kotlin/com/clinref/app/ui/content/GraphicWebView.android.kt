package com.clinref.app.ui.content

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun GraphicWebView(
    graphicId: String,
    fullHtml: String,
    onPageFinished: () -> Unit,
    onPageError: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    val currentOnPageFinished by rememberUpdatedState(onPageFinished)
    val currentOnPageError by rememberUpdatedState(onPageError)
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()
    var loadedHtml by remember(graphicId) { mutableStateOf<String?>(null) }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(backgroundColor)
                isFocusable = true
                isFocusableInTouchMode = true
                isLongClickable = true
                setOnApplyWindowInsetsListener { _, insets -> insets }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        currentOnPageFinished()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            currentOnPageError()
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) {
                            }
                            return true
                        }
                        return false
                    }
                }
                with(settings) {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
            }
        },
        update = { webView ->
            webView.setBackgroundColor(backgroundColor)
            if (loadedHtml != fullHtml) {
                loadedHtml = fullHtml
                webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
            }
        },
        onRelease = { webView -> webView.destroy() },
        modifier = modifier,
    )
}
