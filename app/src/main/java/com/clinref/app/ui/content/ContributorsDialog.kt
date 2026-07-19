package com.clinref.app.ui.content

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import com.clinref.app.data.ContributorGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributorsDialog(
    contributors: List<ContributorGroup>?,
    onDismiss: () -> Unit
) {
    if (contributors.isNullOrEmpty()) return

    val colorScheme = MaterialTheme.colorScheme

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Contributors") },
        text = {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webViewClient = WebViewClient()
                    }
                },
                update = { webView ->
                    val bgColor = colorScheme.surface.toHexString()
                    val textColor = colorScheme.onSurface.toHexString()
                    val headingColor = colorScheme.primary.toHexString()
                    val nameColor = colorScheme.primary.toHexString()
                    val assocColor = colorScheme.onSurfaceVariant.toHexString()
                    val disclosureColor = colorScheme.outline.toHexString()
                    val borderColor = colorScheme.outlineVariant.toHexString()

                    val html = buildString {
                        append("""
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1.0">
                            <style>
                                body { font-family: sans-serif; padding: 16px; color: $textColor; background: $bgColor; margin: 0; }
                                h3 { color: $headingColor; border-bottom: 1px solid $borderColor; padding-bottom: 5px; margin-top: 20px; }
                                .name { font-weight: bold; font-size: 1.1em; color: $nameColor; }
                                .associations { color: $assocColor; margin-top: 4px; }
                                .disclosure { font-style: italic; color: $disclosureColor; margin-top: 4px; font-size: 0.9em; }
                                hr { border: 0; border-top: 1px solid $borderColor; margin: 15px 0; }
                            </style>
                        </head>
                        <body>
                        """.trimIndent())

                        for (group in contributors) {
                            val title = group.headingTitle.ifEmpty { "Contributor" }
                            append("<h3>$title</h3>")

                            for (person in group.contributorList) {
                                append("""<div class="contributor"><div class="name">${person.name}</div>""")
                                if (person.associations.isNotEmpty()) {
                                    append("""<div class="associations">${person.associations.joinToString("<br>")}</div>""")
                                }
                                if (person.disclosure.isNotEmpty()) {
                                    append("""<div class="disclosure">${person.disclosure}</div>""")
                                }
                                append("</div><hr>")
                            }
                        }

                        append("</body></html>")
                    }

                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .heightIn(max = 500.dp)
    )
}

private fun androidx.compose.ui.graphics.Color.toHexString(): String {
    val argb = toArgb()
    return String.format("#%06X", argb and 0xFFFFFF)
}
