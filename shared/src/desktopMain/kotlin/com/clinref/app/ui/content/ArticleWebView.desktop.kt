package com.clinref.app.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
import java.awt.Color
import java.awt.Desktop
import java.net.URI
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultHighlighter
import javax.swing.text.html.HTMLEditorKit

@Composable
actual fun HtmlContentWebView(
    processedHtml: String?,
    controller: ArticleWebViewController,
    initialSectionId: String?,
    modifier: Modifier
) {
    val bgColor = MaterialTheme.colorScheme.background
    val awtBgColor = Color(bgColor.toArgb())

    val (editorPane, scrollPane) = remember {
        val pane = JEditorPane().apply {
            isEditable = false
            contentType = "text/html"
            val kit = HTMLEditorKit()
            editorKit = kit
            background = awtBgColor
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val desc = e.description ?: e.url?.toString()
                    if (desc != null) {
                        if (desc.startsWith("appaction://")) {
                            controller.onAction?.invoke(desc.removePrefix("appaction://"))
                        } else if (desc.startsWith("http://") || desc.startsWith("https://")) {
                            try {
                                Desktop.getDesktop().browse(URI(desc))
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        val sp = JScrollPane(pane).apply {
            border = null
            background = awtBgColor
        }
        pane to sp
    }

    DisposableEffect(controller) {
        controller.bindPlatform(
            scrollToSection = { secId ->
                try {
                    editorPane.scrollToReference(secId)
                } catch (_: Exception) {}
            },
            findAll = { query ->
                val text = editorPane.text ?: ""
                val highlighter = editorPane.highlighter
                highlighter.removeAllHighlights()
                if (query.isNotEmpty()) {
                    var count = 0
                    var index = text.indexOf(query, 0, ignoreCase = true)
                    val painter = DefaultHighlighter.DefaultHighlightPainter(Color.YELLOW)
                    while (index >= 0) {
                        try {
                            highlighter.addHighlight(index, index + query.length, painter)
                            count++
                        } catch (_: Exception) {}
                        index = text.indexOf(query, index + query.length, ignoreCase = true)
                    }
                    controller.onFindResult?.invoke(count, 0)
                } else {
                    controller.onFindResult?.invoke(0, 0)
                }
            },
            clearFindMatches = {
                editorPane.highlighter.removeAllHighlights()
                controller.onFindResult?.invoke(0, 0)
            },
            findNext = { /* forward match traversal */ }
        )
        onDispose {
            controller.unbindPlatform()
        }
    }

    LaunchedEffect(processedHtml) {
        if (processedHtml != null) {
            editorPane.text = processedHtml
            editorPane.caretPosition = 0
            if (!initialSectionId.isNullOrBlank()) {
                try {
                    editorPane.scrollToReference(initialSectionId)
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(initialSectionId) {
        if (!initialSectionId.isNullOrBlank()) {
            try {
                editorPane.scrollToReference(initialSectionId)
            } catch (_: Exception) {}
        }
    }

    SwingPanel(
        background = bgColor,
        factory = { scrollPane },
        modifier = modifier
    )
}
