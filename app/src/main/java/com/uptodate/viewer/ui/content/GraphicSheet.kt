package com.uptodate.viewer.ui.content

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.uptodate.viewer.domain.GraphicData

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
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

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
            },
            update = { webView ->
                var html = graphicData.imageHtml

                if (graphicData.base64Image != null) {
                    val newSrc = "data:image/png;base64,${graphicData.base64Image}"
                    html = html.replace(Regex("""src="[^"]+"""", RegexOption.IGNORE_CASE), """src="$newSrc"""")
                }

                html = html.replace(
                    Regex("""class\s*=\s*["']graphic["']""", RegexOption.IGNORE_CASE),
                    """class="graphic_view"""
                )

                val fullHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes">
                    $graphicCss
                </head>
                <body>$html</body>
                </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
            },
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxSize()
        )
    }
}
