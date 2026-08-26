package com.clinref.app.ui.content

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.R
import com.clinref.app.domain.GraphicData
import kotlinx.coroutines.launch
import org.jsoup.Jsoup

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GraphicSheet(
    graphicId: String,
    onDismiss: () -> Unit,
    viewModel: GraphicViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(graphicId) {
        viewModel.loadGraphic(graphicId)
    }

    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) { ThemeColors.fromColorScheme(colorScheme) }
    val graphicCss = remember(themeColors) { CssBuilder(themeColors).buildGraphicPopupCss() }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Expanded,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var sheetLoading by remember(graphicId) { mutableStateOf(true) }
    var webViewError by remember(graphicId) { mutableStateOf(false) }

    fun retryLoading() {
        sheetLoading = true
        webViewError = false
        viewModel.retry()
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
                    contentDescription = stringResource(R.string.close_sheet),
                )
            }
        }

        if (webViewError || uiState is GraphicUiState.Error) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.graphic_load_failed))
                    TextButton(onClick = ::retryLoading) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        } else {
            when (val state = uiState) {
                GraphicUiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
                }

                GraphicUiState.Error -> Unit

                is GraphicUiState.Success -> {
                    val fullHtml = remember(state.data, graphicCss) {
                        buildGraphicHtml(state.data, graphicCss)
                    }
                    var loadedHtml by remember(graphicId) { mutableStateOf<String?>(null) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .semantics { contentDescription = "Graphic: $graphicId" },
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            sheetLoading = false
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?,
                                        ) {
                                            if (request?.isForMainFrame == true) {
                                                sheetLoading = false
                                                webViewError = true
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
                                        setSupportZoom(true)
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                    }
                                }
                            },
                            update = { webView ->
                                if (loadedHtml != fullHtml) {
                                    loadedHtml = fullHtml
                                    webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                                }
                            },
                            onRelease = { webView -> webView.destroy() },
                            modifier = Modifier.fillMaxSize(),
                        )

                        AnimatedVisibility(
                            visible = sheetLoading,
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
                                ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private val BASE64_VALIDATION_REGEX = Regex("^[A-Za-z0-9+/=\n\r ]+$")

internal fun buildGraphicHtml(graphicData: GraphicData, css: String): String {
    val doc = Jsoup.parseBodyFragment(graphicData.imageHtml)
    doc.outputSettings().prettyPrint(false)

    val base64Image = graphicData.base64Image
    if (base64Image != null && BASE64_VALIDATION_REGEX.matches(base64Image)) {
        doc.body().select("img[src]").first()
            ?.attr("src", "data:image/png;base64,$base64Image")
    }
    doc.body().getElementsByClass("graphic").forEach { element ->
        element.removeClass("graphic").addClass("graphic_view")
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes">
            <style>$css</style>
        </head>
        <body>${doc.body().html()}</body>
        </html>
    """.trimIndent()
}
