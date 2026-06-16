package com.uptodate.viewer.ui.content

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.uptodate.viewer.R
import com.uptodate.viewer.domain.GraphicData
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GraphicSheet(
    graphicData: GraphicData?,
    onDismiss: () -> Unit,
) {
    graphicData ?: return

    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) { ThemeColors.fromColorScheme(colorScheme) }
    val graphicCss = remember(themeColors) { CssBuilder(themeColors).graphicViewer() }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var isLoading by remember(graphicData) { mutableStateOf(true) }

    val fullHtml = remember(graphicData, graphicCss) { buildGraphicHtml(graphicData, graphicCss) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        sheetGesturesEnabled = false,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            IconButton(
                onClick = {
                    scope.launch { sheetState.hide() }
                        .invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.close_sheet),
                )
            }
        }

        GraphicSheetContent(
            fullHtml = fullHtml,
            isLoading = isLoading,
            onLoadingFinished = { isLoading = false },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

private fun buildGraphicHtml(graphicData: GraphicData, css: String): String {
    var html = graphicData.imageHtml

    if (graphicData.base64Image != null) {
        html = html.replace(
            regex = Regex("""src="[^"]+"""", RegexOption.IGNORE_CASE),
            replacement = """src="data:image/png;base64,${graphicData.base64Image}"""",
        )
    }

    html = html.replace(
        regex = Regex("""class\s*=\s*["']graphic["']""", RegexOption.IGNORE_CASE),
        replacement = """class="graphic_view"""",
    )

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes">
            <style>$css</style>
        </head>
        <body>$html</body>
        </html>
    """.trimIndent()
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GraphicSheetContent(
    fullHtml: String,
    isLoading: Boolean,
    onLoadingFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnLoadingFinished by rememberUpdatedState(onLoadingFinished)

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            currentOnLoadingFinished()
                        }
                    }
                    with(settings) {
                        javaScriptEnabled = true
                        allowFileAccess = true
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                }
            },
            update = { webView ->
                if (webView.tag != fullHtml) {
                    webView.tag = fullHtml
                    webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                }
            },
            onRelease = { webView -> webView.destroy() },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(modifier = Modifier.size(48.dp))
            }
        }
    }
}
