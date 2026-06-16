package com.uptodate.viewer.ui.content

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GraphicSheet(
    graphicData: GraphicData?,
    onDismiss: () -> Unit
) {
    if (graphicData == null) return

    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) {
        ThemeColors.fromColorScheme(colorScheme)
    }
    val graphicCss = remember(themeColors) {
        CssBuilder(themeColors).graphicViewer()
    }

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Expanded
    )
    val coroutineScope = rememberCoroutineScope()
    var isLoading by remember(graphicData) { mutableStateOf(true) }

    val fullHtml = remember(graphicData, graphicCss) {
        var html = graphicData.imageHtml

        if (graphicData.base64Image != null) {
            val newSrc = "data:image/png;base64,${graphicData.base64Image}"
            html = html.replace(Regex("""src="[^"]+"""", RegexOption.IGNORE_CASE), """src="$newSrc"""")
        }

        html = html.replace(
            Regex("""class\s*=\s*["']graphic["']""", RegexOption.IGNORE_CASE),
            """class="graphic_view""""
        )

        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes">
            <style>$graphicCss</style>
        </head>
        <body>$html</body>
        </html>
        """.trimIndent()
    }

    BottomSheet(
        state = sheetState,
        onDismissRequest = onDismiss,
        gesturesEnabled = false,
        dragHandle = null
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            GraphicSheetContent(
                fullHtml = fullHtml,
                isLoading = isLoading,
                onLoadingFinished = { isLoading = false },
                modifier = Modifier.fillMaxSize()
            )

            IconButton(
                onClick = {
                    coroutineScope.launch {
                        sheetState.hide()
                        onDismiss()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(id = R.string.close_sheet)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GraphicSheetContent(
    fullHtml: String,
    isLoading: Boolean,
    onLoadingFinished: () -> Unit,
    modifier: Modifier = Modifier
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
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
            },
            update = { webView ->
                if (webView.tag != fullHtml) {
                    webView.tag = fullHtml
                    webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator(
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }
}
