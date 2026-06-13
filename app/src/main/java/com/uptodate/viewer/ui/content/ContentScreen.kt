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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
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

private const val ZOOM_MIN = 50f
private const val ZOOM_MAX = 200f

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(state.title, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.goBack() },
                        enabled = viewModel.canGoBack
                    ) {
                        Icon(Icons.Default.ArrowBack, "Previous")
                    }
                    IconButton(
                        onClick = { viewModel.goForward() },
                        enabled = viewModel.canGoForward
                    ) {
                        Icon(Icons.Default.ArrowForward, "Next")
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
                                settings.allowFileAccess = false
                                settings.allowFileAccessFromFileURLs = false
                                settings.allowUniversalAccessFromFileURLs = false

                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun appAction(json: String) {
                                        viewModel.handleActionUrl(json)
                                    }
                                }, "Android")

                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: android.webkit.WebResourceRequest?
                                    ): Boolean {
                                        val url = request?.url?.toString() ?: return false
                                        if (url.startsWith("$AppAction.SCHEME://")) {
                                            val actionId = url.removePrefix("$AppAction.SCHEME://")
                                            viewModel.handleActionUrl(actionId)
                                            return true
                                        }
                                        return false
                                    }
                                }

                                mainWebView = this
                            }
                        },
                        update = { webView ->
                            webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                            webView.settings.textZoom = state.zoomPercent

                            if (state.findQuery.isNotEmpty()) {
                                webView.findAllAsync(state.findQuery)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (state.showOutline && outlineFullHtml.isNotEmpty()) {
                    AndroidView(
                        factory = { context ->
                            WebView(context).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )

                                outlineWebView = this
                            }
                        },
                        update = { webView ->
                            webView.loadDataWithBaseURL(null, outlineFullHtml, "text/html", "UTF-8", null)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 48.dp)
                    )
                }
            }
        }

        HorizontalFloatingToolbar(
            expanded = state.showFind,
            floatingActionButton = {
                FloatingToolbarDefaults.VibrantFloatingActionButton(
                    onClick = {
                        if (state.showFind) viewModel.hideFind()
                        else viewModel.showFind()
                    }
                ) {
                    Icon(
                        if (state.showFind) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = if (state.showFind) "Close find" else "Find in page"
                    )
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .zIndex(1f),
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            content = {
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
                    Icon(Icons.Default.NavigateBefore, "Previous")
                }
                IconButton(
                    onClick = { mainWebView?.findNext(true) },
                    modifier = Modifier.focusProperties { canFocus = state.showFind }
                ) {
                    Icon(Icons.Default.NavigateNext, "Next")
                }
            }
        )
    }
}
