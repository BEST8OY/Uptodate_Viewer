package com.uptodate.viewer.ui.content

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ContributorsDialog(
    contributors: List<Map<String, Any?>>?,
    onDismiss: () -> Unit
) {
    if (contributors.isNullOrEmpty()) return

    Dialog(onDismissRequest = onDismiss) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                }
            },
            update = { webView ->
                val html = buildString {
                    append("""
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <style>
                            body { font-family: sans-serif; padding: 16px; color: #333; }
                            h3 { color: #2c3e50; border-bottom: 1px solid #eee; padding-bottom: 5px; margin-top: 20px; }
                            .name { font-weight: bold; font-size: 1.1em; color: #2980b9; }
                            .associations { color: #555; margin-top: 4px; }
                            .disclosure { font-style: italic; color: #7f8c8d; margin-top: 4px; font-size: 0.9em; }
                            hr { border: 0; border-top: 1px solid #eee; margin: 15px 0; }
                        </style>
                    </head>
                    <body>
                    """.trimIndent())

                    for (group in contributors) {
                        val title = group["headingTitle"] as? String ?: "Contributor"
                        append("<h3>$title</h3>")

                        @Suppress("UNCHECKED_CAST")
                        val personList = group["contributorList"] as? List<Map<String, Any?>> ?: emptyList()
                        for (person in personList) {
                            val name = person["name"] as? String ?: ""
                            @Suppress("UNCHECKED_CAST")
                            val associations = person["associations"] as? List<String> ?: emptyList()
                            val disclosure = person["disclosure"] as? String ?: ""

                            append("""<div class="contributor"><div class="name">$name</div>""")
                            if (associations.isNotEmpty()) {
                                append("""<div class="associations">${associations.joinToString("<br>")}</div>""")
                            }
                            if (disclosure.isNotEmpty()) {
                                append("""<div class="disclosure">$disclosure</div>""")
                            }
                            append("</div><hr>")
                        }
                    }

                    append("</body></html>")
                }

                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        )
    }
}
