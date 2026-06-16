package com.uptodate.viewer.ui.content

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.uptodate.viewer.R
import com.uptodate.viewer.domain.GraphicData
import kotlinx.coroutines.launch

private val SRC_REGEX = Regex("""src="[^"]+"""", RegexOption.IGNORE_CASE)
private val GRAPHIC_CLASS_REGEX = Regex("""class\s*=\s*["']graphic["']""", RegexOption.IGNORE_CASE)
private val BASE64_VALIDATION_REGEX = Regex("^[A-Za-z0-9+/=\n\r ]+$")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GraphicSheet(
    graphicData: GraphicData?,
    onDismiss: () -> Unit,
) {
    if (graphicData == null) return

    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) { ThemeColors.fromColorScheme(colorScheme) }
    val graphicCss = remember(themeColors) { CssBuilder(themeColors).graphicViewer() }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    var isLoading by remember(graphicData) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        sheetState.show()
    }

    BottomSheet(
        state = sheetState,
        onDismissRequest = onDismiss,
        gesturesEnabled = false,
        dragHandle = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {},
                            onDragCancel = {},
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (dragAmount < -5f) {
                                    scope.launch { sheetState.expand() }
                                } else if (dragAmount > 5f) {
                                    scope.launch {
                                        sheetState.hide()
                                        onDismiss()
                                    }
                                }
                            },
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.close_sheet),
                    )
                }
            }
        },
    ) {
        GraphicSheetContent(
            graphicData = graphicData,
            graphicCss = graphicCss,
            isLoading = isLoading,
            onLoadingFinished = { isLoading = false },
            onLoadingError = { isLoading = false },
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ColumnScope.GraphicSheetContent(
    graphicData: GraphicData,
    graphicCss: String,
    isLoading: Boolean,
    onLoadingFinished: () -> Unit,
    onLoadingError: () -> Unit,
) {
    val currentOnLoadingFinished by rememberUpdatedState(onLoadingFinished)
    val currentOnLoadingError by rememberUpdatedState(onLoadingError)
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .semantics { contentDescription = "Graphic: ${graphicData.id}" },
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            currentOnLoadingFinished()
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            if (request?.isForMainFrame == true) {
                                currentOnLoadingError()
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            if (url.startsWith("http://") || url.startsWith("https://")) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                return true
                            }
                            return false
                        }
                    }
                    with(settings) {
                        javaScriptEnabled = false
                        allowFileAccess = false
                        allowFileAccessFromFileURLs = false
                        allowUniversalAccessFromFileURLs = false
                        setSupportZoom(true)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                }
            },
            update = { webView ->
                var html = graphicData.imageHtml

                if (graphicData.base64Image != null && BASE64_VALIDATION_REGEX.matches(graphicData.base64Image)) {
                    html = html.replace(
                        regex = SRC_REGEX,
                        replacement = """src="data:image/png;base64,${graphicData.base64Image}"""",
                    )
                }

                html = html.replace(
                    regex = GRAPHIC_CLASS_REGEX,
                    replacement = """class="graphic_view"""",
                )

                val fullHtml = """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes">
                        <style>$graphicCss</style>
                    </head>
                    <body>$html</body>
                    </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
            },
            onRelease = { webView -> webView.destroy() },
            modifier = Modifier.fillMaxSize(),
        )

        if (isLoading) {
            LoadingIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center),
            )
        }
    }
}
