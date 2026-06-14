package com.uptodate.viewer.ui.content

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.uptodate.viewer.domain.GraphicData

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GraphicDialog(
    graphicData: GraphicData?,
    onDismiss: () -> Unit
) {
    if (graphicData == null) return

    Dialog(onDismissRequest = onDismiss) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                }
            },
            update = { webView ->
                var html = graphicData.imageHtml

                // Replace base64 image if available
                if (graphicData.base64Image != null) {
                    val newSrc = "data:image/png;base64,${graphicData.base64Image}"
                    html = html.replace(Regex("""src="[^"]+"""", RegexOption.IGNORE_CASE),
                        """src="$newSrc"""")
                }

                // Fix class for viewer
                html = html.replace(
                    Regex("""class\s*=\s*["']graphic["']""", RegexOption.IGNORE_CASE),
                    """class="graphic_view"""
                )

                // Wrap in HTML with CSS
                val fullHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <style>
                        body { margin: 0; padding: 16px; font-family: sans-serif; }
                        .graphic_view { max-width: 100%; height: auto; }
                        img { max-width: 100%; height: auto; }
                    </style>
                </head>
                <body>$html</body>
                </html>
                """.trimIndent()

                webView.loadDataWithBaseURL(null, fullHtml, "text/html", "UTF-8", null)
            }
        )
    }
}
