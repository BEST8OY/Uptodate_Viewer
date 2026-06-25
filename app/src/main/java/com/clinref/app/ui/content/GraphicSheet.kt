package com.clinref.app.ui.content

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.GraphicData
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GraphicSheet(
    graphicId: String,
    onDismiss: () -> Unit,
    viewModel: GraphicViewModel = hiltViewModel()
) {
    val graphicData by viewModel.graphicData.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(graphicId) {
        viewModel.loadGraphic(graphicId)
    }

    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) { ThemeColors.fromColorScheme(colorScheme) }
    val graphicCss = remember(themeColors) { CssBuilder(themeColors).graphicViewer() }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var sheetLoading by remember(graphicId) { mutableStateOf(true) }

    val fullHtml = graphicData?.let { data ->
        remember(data, graphicCss) { buildGraphicHtml(data, graphicCss) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        sheetGesturesEnabled = false,
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
                    contentDescription = "Close",
                )
            }
        }

        if (graphicData != null && fullHtml != null) {
            GraphicSheetContent(
                graphicId = graphicId,
                fullHtml = fullHtml,
                isLoading = sheetLoading,
                onLoadingFinished = { sheetLoading = false },
                onLoadingError = { sheetLoading = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator(modifier = Modifier.size(48.dp))
            }
        }
    }
}

private val SRC_REGEX = Regex("""src="[^"]+"""", RegexOption.IGNORE_CASE)
private val GRAPHIC_CLASS_REGEX = Regex("""class\s*=\s*["']graphic["']""", RegexOption.IGNORE_CASE)
private val BASE64_VALIDATION_REGEX = Regex("^[A-Za-z0-9+/=\n\r ]+$")

internal fun buildGraphicHtml(graphicData: GraphicData, css: String): String {
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
internal fun GraphicSheetContent(
    graphicId: String,
    fullHtml: String,
    isLoading: Boolean,
    onLoadingFinished: () -> Unit,
    onLoadingError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnLoadingFinished by rememberUpdatedState(onLoadingFinished)
    val currentOnLoadingError by rememberUpdatedState(onLoadingError)
    val context = LocalContext.current

    Box(
        modifier = modifier.semantics {
            contentDescription = "Graphic: $graphicId"
        },
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
                val htmlHash = fullHtml.hashCode()
                if (webView.tag != htmlHash) {
                    webView.tag = htmlHash
                    webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                }
            },
            onRelease = { webView -> webView.destroy() },
            modifier = Modifier.fillMaxSize(),
        )

        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
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
