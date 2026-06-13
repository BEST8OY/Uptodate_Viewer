package com.uptodate.viewer.ui.graphic

import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.uptodate.viewer.R
import com.uptodate.viewer.ui.theme.ContentCssBuilder
import com.uptodate.viewer.ui.theme.ThemeColors

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun GraphicScreen(
    graphicId: String,
    onClose: () -> Unit,
    viewModel: GraphicViewModel
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

    val html = buildString {
        if (state.rawImageHtml.isNotEmpty()) {
            var imageHtml = state.rawImageHtml
            if (state.base64Image != null) {
                imageHtml = imageHtml.replace(
                    Regex("""src="[^"]+""""),
                    """src="data:image/png;base64,${state.base64Image}""""
                )
            }
            imageHtml = imageHtml.replace(
                Regex("""class\s*=\s*["']graphic["']"""),
                "class=\"graphic_view\""
            )
            append("""<html><head>$css<style>.graphic_view { max-width: 100%; height: auto; }</style></head><body>""")
            append(imageHtml)
            append("</body></html>")
        }
    }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            webViewRef = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.graphic_viewer)) },
                actions = {
                    Button(onClick = onClose, modifier = Modifier.padding(end = 8.dp)) {
                        Text(stringResource(R.string.close))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (html.isNotEmpty()) {
                AndroidView(
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            webViewClient = WebViewClient()

                            webViewRef = this
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else if (state.isLoading) {
                Text("Loading\u2026", modifier = Modifier.padding(16.dp))
            } else {
                Text("Graphic not found", modifier = Modifier.padding(16.dp))
            }
        }
    }
}
