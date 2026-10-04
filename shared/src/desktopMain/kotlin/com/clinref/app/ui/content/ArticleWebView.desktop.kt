package com.clinref.app.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
import com.clinref.app.domain.ReadingPosition
import kotlinx.coroutines.delay
import java.awt.Color
import java.awt.Desktop
import java.awt.Point
import java.awt.geom.Point2D
import java.net.URI
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultHighlighter
import javax.swing.text.html.HTMLEditorKit

private const val POSITION_DEBOUNCE_MS = 250
private const val LAYOUT_SETTLE_MS = 60L
private const val OFFSET_ANCHOR_PREFIX = "off:"
private const val TOP_ANCHOR = "top"

/** Reports are suppressed while a start target is being applied, and until one has been. */
private class RestoreState {
    var restoring = false
    var applied = false
}

@Composable
actual fun HtmlContentWebView(
    document: ArticleDocument?,
    controller: ArticleWebViewController,
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

    val currentDocument = rememberUpdatedState(document)
    val restoreState = remember { RestoreState() }

    fun clampedY(y: Int): Int {
        val viewport = scrollPane.viewport
        return y.coerceIn(0, (editorPane.height - viewport.height).coerceAtLeast(0))
    }

    fun reportPosition() {
        val doc = currentDocument.value ?: return
        if (restoreState.restoring || !restoreState.applied) return
        val viewport = scrollPane.viewport
        val y = viewport.viewPosition.y
        val maxY = (editorPane.height - viewport.height).coerceAtLeast(0)
        val progress = if (maxY > 0) (y.toFloat() / maxY).coerceIn(0f, 1f) else 0f
        val anchor = if (y <= 1) {
            TOP_ANCHOR
        } else {
            OFFSET_ANCHOR_PREFIX + editorPane.viewToModel2D(Point2D.Double(0.0, y.toDouble()))
        }
        controller.onPositionChanged?.invoke(
            ReadingPosition(
                anchor = anchor,
                progress = progress,
                sectionId = null,
                contentRev = doc.contentRev
            )
        )
    }

    fun applyStart(doc: ArticleDocument) {
        val viewport = scrollPane.viewport
        when (val start = controller.resolveStart(doc)) {
            StartTarget.Top -> viewport.viewPosition = Point(0, 0)
            is StartTarget.Section -> try {
                editorPane.scrollToReference(start.id)
            } catch (_: Exception) {}
            is StartTarget.Resume -> {
                val position = start.position
                val offset = position.anchor
                    .takeIf { position.contentRev == doc.contentRev && it.startsWith(OFFSET_ANCHOR_PREFIX) }
                    ?.removePrefix(OFFSET_ANCHOR_PREFIX)
                    ?.toIntOrNull()
                val y = when {
                    position.anchor == TOP_ANCHOR -> 0
                    offset != null -> try {
                        editorPane.modelToView2D(offset.coerceIn(0, editorPane.document.length))?.y?.toInt() ?: 0
                    } catch (_: Exception) {
                        0
                    }
                    else -> (position.progress * (editorPane.height - viewport.height).coerceAtLeast(0)).toInt()
                }
                viewport.viewPosition = Point(0, clampedY(y))
            }
        }
    }

    DisposableEffect(controller) {
        val debounce = Timer(POSITION_DEBOUNCE_MS) { reportPosition() }.apply { isRepeats = false }
        val listener = javax.swing.event.ChangeListener {
            if (!restoreState.restoring) debounce.restart()
        }
        scrollPane.viewport.addChangeListener(listener)
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
            findNext = { /* forward match traversal */ },
            flushPosition = {
                debounce.stop()
                reportPosition()
            }
        )
        onDispose {
            debounce.stop()
            reportPosition()
            scrollPane.viewport.removeChangeListener(listener)
            controller.unbindPlatform()
        }
    }

    LaunchedEffect(document?.revision) {
        val doc = document ?: return@LaunchedEffect
        restoreState.restoring = true
        restoreState.applied = false
        try {
            editorPane.text = doc.html
            // Layout settles asynchronously; apply twice so the target survives the first pass.
            repeat(2) {
                delay(LAYOUT_SETTLE_MS)
                applyStart(doc)
            }
            restoreState.applied = true
        } finally {
            restoreState.restoring = false
        }
    }

    SwingPanel(
        background = bgColor,
        factory = { scrollPane },
        modifier = modifier
    )
}
