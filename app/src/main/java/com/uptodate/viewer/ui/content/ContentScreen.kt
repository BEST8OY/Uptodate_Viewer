package com.uptodate.viewer.ui.content

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.uptodate.viewer.R
import com.uptodate.viewer.ui.theme.ContentCssBuilder
import com.uptodate.viewer.ui.theme.ThemeColors
import com.uptodate.viewer.util.AppAction
import com.uptodate.viewer.util.TopicId

private const val ZOOM_MIN = 50f
private const val ZOOM_MAX = 200f

private fun linkInterceptJs(interfaceName: String = "Android") = """
(function() {
    document.addEventListener('click', function(e) {
        var a = e.target.closest('a');
        if (!a) return;
        var href = a.getAttribute('href');
        if (!href || href.startsWith('javascript:void')) return;
        e.preventDefault();
        e.stopPropagation();
        $interfaceName.navigateUrl(href);
    }, true);
})();
"""

/**
 * Result of parsing a URL clicked in the WebView.
 */
private sealed class LinkTarget {
    data class Action(val actionId: String) : LinkTarget()
    data class Topic(val topicId: String, val fragment: String? = null) : LinkTarget()
    data class Anchor(val fragment: String) : LinkTarget()
    data object Unhandled : LinkTarget()
}

private fun parseLink(url: String): LinkTarget {
    // Anchor-only link: "#H123456"
    if (url.startsWith("#")) {
        return LinkTarget.Anchor(url.removePrefix("#"))
    }

    val uri = android.net.Uri.parse(url)
    val scheme = uri.scheme ?: ""
    val host = uri.host ?: ""
    val fragment = uri.fragment

    // appaction://action_0
    if (scheme == AppAction.SCHEME) {
        return LinkTarget.Action(host)
    }

    // Query param: ?topicRef=1234 or ?topicKey=topic-1234
    val topicRef = uri.getQueryParameter("topicRef")
        ?: uri.getQueryParameter("topicKey")
    if (topicRef != null) {
        val num = TopicId.REGEX.find(topicRef.trim())
        if (num != null) return LinkTarget.Topic(num.groupValues[1], fragment)
    }

    // Host is a bare topic id: "1234" or "topic-1234"
    val hostMatch = TopicId.REGEX.find(host)
    if (hostMatch != null) {
        return LinkTarget.Topic(hostMatch.groupValues[1], fragment)
    }

    // Last path segment: "/contents/topic-1234"
    val pathMatch = TopicId.REGEX.find(uri.lastPathSegment ?: "")
    if (pathMatch != null) {
        return LinkTarget.Topic(pathMatch.groupValues[1], fragment)
    }

    return LinkTarget.Unhandled
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentScreen(
    topicId: String,
    onNavigateToGraphic: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ContentViewModel
) {
    val state by viewModel.state.collectAsState()

    val isDark = ThemeColors.isDark
    val tc = ThemeColors.textColor
    val bc = ThemeColors.bgColor
    val lc = ThemeColors.linkColor
    val dc = ThemeColors.drugColor
    val dac = ThemeColors.dangerColor
    val cac = ThemeColors.cautionColor
    val gc = ThemeColors.gradeColor
    val boc = ThemeColors.borderColor
    val sc = ThemeColors.selectionColor

    val css = remember(isDark, tc, bc, lc, dc, dac, cac, gc, boc, sc) {
        ContentCssBuilder(isDark, tc, bc, lc, dc, dac, cac, gc, boc, sc).build()
    }
    val outlineCss = remember(isDark, tc, bc, lc, dc, dac, cac, gc, boc, sc) {
        ContentCssBuilder(isDark, tc, bc, lc, dc, dac, cac, gc, boc, sc).buildOutline()
    }

    val fullHtml = state.content?.let { data ->
        buildString {
            append("""<html><head>$css</head><body>""")
            append(data.rawBodyHtml)
            append("</body></html>")
        }
    } ?: ""

    val outlineFullHtml = state.content?.let { data ->
        if (data.rawOutlineHtml.isNotEmpty()) {
            buildString {
                append("""<html><head>$outlineCss</head><body class="outline-mode"><div class="topic-outline">""")
                append(data.rawOutlineHtml)
                append("</div></body></html>")
            }
        } else ""
    } ?: ""

    var mainWebView by remember { mutableStateOf<WebView?>(null) }
    var outlineWebView by remember { mutableStateOf<WebView?>(null) }
    val scrollTarget = remember { mutableListOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            mainWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            mainWebView = null
            outlineWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            outlineWebView = null
        }
    }

    LaunchedEffect(Unit) {
        viewModel.navEventFlow.collect { event ->
            when (event) {
                is ContentViewModel.NavEvent.OpenGraphic -> onNavigateToGraphic(event.graphicId)
                is ContentViewModel.NavEvent.NavigateToTopic -> {
                    scrollTarget[0] = event.section
                    viewModel.navigate(event.topicId)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(state.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.goBack() },
                        enabled = viewModel.canGoBack
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous")
                    }
                    IconButton(
                        onClick = { viewModel.goForward() },
                        enabled = viewModel.canGoForward
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next")
                    }
                    IconButton(onClick = { viewModel.toggleOutline() }) {
                        Icon(Icons.Default.Menu, "Outline")
                    }
                    IconButton(onClick = { viewModel.toggleFavorite() }) {
                        Icon(
                            if (state.content?.isFavorite == true) Icons.Default.Star
                            else Icons.Default.StarBorder,
                            "Favorite",
                            tint = if (state.content?.isFavorite == true)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            ) {
                Text("${ZOOM_MIN.toInt()}%", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = state.zoomPercent.toFloat(),
                    onValueChange = { viewModel.setZoom(it.toInt()) },
                    valueRange = ZOOM_MIN..ZOOM_MAX,
                    modifier = Modifier.weight(1f)
                )
                Text("${ZOOM_MAX.toInt()}%", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(4.dp))
                Text("${state.zoomPercent}%", style = MaterialTheme.typography.labelMedium)
            }

            Box(modifier = Modifier.weight(1f)) {
                if (fullHtml.isNotEmpty()) {
                    var loaded by remember(state.topicId) { mutableStateOf(false) }
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.builtInZoomControls = false

                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun appAction(json: String) {
                                        viewModel.handleActionUrl(json)
                                    }

                                    @JavascriptInterface
                                    fun navigateUrl(url: String) {
                                        when (val target = parseLink(url)) {
                                            is LinkTarget.Action -> viewModel.handleActionUrl(target.actionId)
                                            is LinkTarget.Topic -> {
                                                scrollTarget[0] = target.fragment
                                                viewModel.navigate(target.topicId)
                                            }
                                            is LinkTarget.Anchor -> {
                                                mainWebView?.post {
                                                    mainWebView?.evaluateJavascript(
                                                        "document.getElementById('${target.fragment}')?.scrollIntoView({behavior:'smooth'})",
                                                        null
                                                    )
                                                }
                                            }
                                            is LinkTarget.Unhandled -> {}
                                        }
                                    }
                                }, "Android")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        view?.evaluateJavascript(linkInterceptJs("Android"), null)
                                        val section = scrollTarget[0]
                                        if (section != null) {
                                            scrollTarget[0] = null
                                            view?.postDelayed({
                                                view?.evaluateJavascript(
                                                    "document.getElementById('$section')?.scrollIntoView({behavior:'smooth'})",
                                                    null
                                                )
                                            }, 300)
                                        }
                                    }
                                }

                                mainWebView = this
                            }
                        },
                        update = { webView ->
                            if (!loaded || webView.url == null) {
                                webView.loadData(
                                    fullHtml.replace(Regex("""target="_?blank"?"""), ""),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                                loaded = true
                            }
                            webView.settings.textZoom = state.zoomPercent
                            if (state.findQuery.isNotEmpty()) {
                                webView.findAllAsync(state.findQuery)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (state.showOutline && outlineFullHtml.isNotEmpty()) {
                    var outlineLoaded by remember(state.topicId) { mutableStateOf(false) }
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true

                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun navigateUrl(url: String) {
                                        when (val target = parseLink(url)) {
                                            is LinkTarget.Action -> viewModel.handleActionUrl(target.actionId)
                                            is LinkTarget.Topic -> {
                                                scrollTarget[0] = target.fragment
                                                viewModel.navigate(target.topicId)
                                            }
                                            is LinkTarget.Anchor -> {
                                                mainWebView?.post {
                                                    mainWebView?.evaluateJavascript(
                                                        "document.getElementById('${target.fragment}')?.scrollIntoView({behavior:'smooth'})",
                                                        null
                                                    )
                                                }
                                            }
                                            is LinkTarget.Unhandled -> {}
                                        }
                                    }
                                }, "Outline")

                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        view?.evaluateJavascript(linkInterceptJs("Outline"), null)
                                        val section = scrollTarget[0]
                                        if (section != null) {
                                            scrollTarget[0] = null
                                            view?.postDelayed({
                                                view?.evaluateJavascript(
                                                    "document.getElementById('$section')?.scrollIntoView({behavior:'smooth'})",
                                                    null
                                                )
                                            }, 300)
                                        }
                                    }
                                }

                                outlineWebView = this
                            }
                        },
                        update = { webView ->
                            if (!outlineLoaded || webView.url == null) {
                                webView.loadData(
                                    outlineFullHtml.replace(Regex("""target="_?blank"?"""), ""),
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                                outlineLoaded = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 48.dp)
                    )
                }
            }
        }

        if (state.showFind) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .zIndex(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = state.findQuery,
                        onValueChange = { viewModel.setFindQuery(it) },
                        placeholder = { Text(stringResource(R.string.find_in_page)) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusProperties { canFocus = state.showFind }
                    )
                    IconButton(
                        onClick = { mainWebView?.findNext(false) },
                        modifier = Modifier.focusProperties { canFocus = state.showFind }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Previous")
                    }
                    IconButton(
                        onClick = { mainWebView?.findNext(true) },
                        modifier = Modifier.focusProperties { canFocus = state.showFind }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, "Next")
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                if (state.showFind) viewModel.hideFind()
                else viewModel.showFind()
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .zIndex(2f)
        ) {
            Icon(
                if (state.showFind) Icons.Default.Close else Icons.Default.Search,
                contentDescription = if (state.showFind) "Close find" else "Find in page"
            )
        }
    }
}
