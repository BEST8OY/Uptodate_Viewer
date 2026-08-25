package com.clinref.app.ui.content

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

fun scrollToSectionJs(sectionId: String): String =
    "document.getElementById('$sectionId')?.scrollIntoView({behavior:'smooth', block:'start'})"

class ArticleWebViewController {

    private var view: WebView? = null

    var onAction: ((String) -> Unit)? = null
    var onFindResult: ((count: Int, activeMatchOrdinal: Int) -> Unit)? = null
    var onContentScrolled: ((dy: Int) -> Unit)? = null

    fun attach(view: WebView) {
        this.view = view
    }

    fun detach(view: WebView) {
        if (this.view === view) this.view = null
    }

    fun findAll(query: String) {
        view?.findAllAsync(query)
    }

    fun clearFindMatches() {
        view?.clearMatches()
    }

    fun findNext(forward: Boolean) {
        view?.findNext(forward)
    }

    fun scrollToSection(sectionId: String) {
        view?.evaluateJavascript(scrollToSectionJs(sectionId), null)
    }
}

@Composable
fun rememberArticleWebViewController(): ArticleWebViewController =
    remember { ArticleWebViewController() }

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun HtmlContentWebView(
    processedHtml: String?,
    controller: ArticleWebViewController,
    initialSectionId: String? = null,
    modifier: Modifier = Modifier
) {
    val backgroundColor = MaterialTheme.colorScheme.background.toArgb()

    AndroidView(
        factory = { context ->
            WebView(context).apply {
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

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (initialSectionId != null) {
                            view?.evaluateJavascript(scrollToSectionJs(initialSectionId), null)
                        }
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
                // While a finger is down, gesture deltas drive hide/reveal — this also works at
                // the content edges where scrollY never changes. After lift (fling), the scroll
                // listener takes over; only one channel is ever live for a given motion.
                var pointerDown = false
                var lastTouchY = 0f
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            pointerDown = true
                            lastTouchY = event.y
                        }
                        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                            // Re-anchor on pointer-count changes so pinch gestures never emit deltas.
                            lastTouchY = event.y
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (pointerDown && event.pointerCount == 1) {
                                val dy = (lastTouchY - event.y).toInt()
                                if (dy != 0) {
                                    controller.onContentScrolled?.invoke(dy)
                                }
                            }
                            lastTouchY = event.y
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pointerDown = false
                    }
                    false
                }
                setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                    if (!pointerDown) {
                        val dy = scrollY - oldScrollY
                        if (dy != 0) {
                            controller.onContentScrolled?.invoke(dy)
                        }
                    }
                }
                addJavascriptInterface(
                    JsBridge { jsonStr ->
                        controller.onAction?.invoke("manual_$jsonStr")
                    },
                    "Android"
                )
                controller.attach(this)
            }
        },
        update = { wv ->
            val html = processedHtml
            if (html != null) {
                val htmlHash = html.hashCode().toString()
                if (wv.tag != htmlHash) {
                    wv.tag = htmlHash
                    wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
        },
        onRelease = { wv ->
            wv.stopLoading()
            wv.destroy()
            controller.detach(wv)
        },
        modifier = modifier
    )
}
