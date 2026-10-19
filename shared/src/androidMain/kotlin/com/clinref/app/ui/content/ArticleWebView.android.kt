package com.clinref.app.ui.content

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

private const val FLUSH_SCRIPT = "window.__clinrefFlush && window.__clinrefFlush()"
private const val DESTROY_FALLBACK_MS = 500L

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun HtmlContentWebView(
    document: ArticleDocument?,
    controller: ArticleWebViewController,
    modifier: Modifier
) {
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()

    AndroidView(
        factory = { context ->
            val mainHandler = Handler(Looper.getMainLooper())
            NestedScrollWebView(context).apply {
                setBackgroundColor(backgroundColor)
                setOnApplyWindowInsetsListener { _, insets ->
                    insets
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("appaction://")) {
                            val actionId = url.removePrefix("appaction://")
                            controller.onAction?.invoke(actionId)
                            return true
                        }
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                request.url
                            )
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                            return true
                        }
                        return false
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                    controller.onFindResult?.invoke(numberOfMatches, activeMatchOrdinal)
                }
                addJavascriptInterface(
                    JsBridge(
                        onAction = { jsonStr ->
                            controller.onAction?.invoke("manual_$jsonStr")
                        },
                        onScrollReport = { payload ->
                            ArticleScrollJs.parsePosition(payload)?.let { position ->
                                // Bridge callbacks arrive on a WebView-internal thread.
                                mainHandler.post { controller.onPositionChanged?.invoke(position) }
                            }
                        }
                    ),
                    "Android"
                )
                controller.bindPlatform(
                    scrollToSection = { sec -> evaluateJavascript(scrollToSectionJs(sec), null) },
                    findAll = { q -> findAllAsync(q) },
                    clearFindMatches = { clearMatches() },
                    findNext = { fwd -> findNext(fwd) },
                    flushPosition = { evaluateJavascript(FLUSH_SCRIPT, null) }
                )
            }
        },
        update = { wv ->
            val doc = document
            // Reload only for a new document revision. Start-target restoration happens inside the
            // page (see ArticleScrollJs) before first paint.
            if (doc != null && wv.tag != doc.revision) {
                wv.tag = doc.revision
                wv.loadDataWithBaseURL(
                    null,
                    ArticleScrollJs.inject(doc.html, controller.resolveStart(doc), doc.contentRev),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        },
        onRelease = { wv ->
            controller.unbindPlatform()
            wv.stopLoading()
            // Timers are paused while detached, so report the final position synchronously before
            // teardown. destroy() waits for the script (or a short fallback) so the report lands.
            var destroyed = false
            val destroy = {
                if (!destroyed) {
                    destroyed = true
                    wv.destroy()
                }
            }
            wv.evaluateJavascript(FLUSH_SCRIPT) { destroy() }
            Handler(Looper.getMainLooper()).postDelayed({ destroy() }, DESTROY_FALLBACK_MS)
        },
        modifier = modifier
    )
}
