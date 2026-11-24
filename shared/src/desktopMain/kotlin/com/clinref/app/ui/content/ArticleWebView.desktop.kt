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
import javax.swing.Timer
import javax.swing.text.DefaultHighlighter

private const val POSITION_DEBOUNCE_MS = 250
private const val LAYOUT_SETTLE_MS = 60L
private const val OFFSET_ANCHOR_PREFIX = "off:"
private const val TOP_ANCHOR = "top"

/** Tracks search matches and active match index for in-page find without triggering Compose recomposition. */
private class FindState {
    var matchRanges: List<IntRange> = emptyList()
    var activeIndex: Int = -1
}

/** Tracks restore/programmatic scroll boundaries and last observed viewport geometry. */
private class RestoreState {
    var restoring = false
    var applied = false
    var programmaticScroll = false
    var lastScrollY = 0
    var lastViewportHeight = 0
    var lastContentHeight = 0

    fun syncGeometry(scrollY: Int, viewportHeight: Int, contentHeight: Int) {
        lastScrollY = scrollY
        lastViewportHeight = viewportHeight
        lastContentHeight = contentHeight
    }
}

@Composable
actual fun HtmlContentWebView(
    document: ArticleDocument?,
    controller: ArticleWebViewController,
    modifier: Modifier
) {
    val bgColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val awtBgColor = remember(bgColor) { Color(bgColor.toArgb()) }
    val awtSelectionColor = remember(primaryColor) { Color(primaryColor.toArgb()) }
    val awtSelectedTextColor = remember(onPrimaryColor) { Color(onPrimaryColor.toArgb()) }

    val restoreState = remember { RestoreState() }
    val findState = remember { FindState() }

    val (editorPane, scrollPane) = remember {
        createHtmlEditorPane(
            bgColor = awtBgColor,
            selectionColor = awtSelectionColor,
            selectedTextColor = awtSelectedTextColor,
            onHyperlink = { pane, desc ->
                when {
                    desc.startsWith("appaction://") -> {
                        controller.onAction?.invoke(desc.removePrefix("appaction://"))
                    }
                    desc.startsWith("#") -> {
                        try {
                            restoreState.programmaticScroll = true
                            pane.scrollToReference(desc.removePrefix("#"))
                            val viewport = pane.parent as? javax.swing.JViewport
                            if (viewport != null) {
                                restoreState.syncGeometry(
                                    viewport.viewPosition.y,
                                    viewport.height,
                                    pane.height
                                )
                            }
                        } catch (_: Exception) {
                        } finally {
                            restoreState.programmaticScroll = false
                        }
                    }
                    desc.startsWith("http://") || desc.startsWith("https://") -> {
                        try {
                            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                Desktop.getDesktop().browse(URI(desc))
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        )
    }

    LaunchedEffect(awtBgColor, awtSelectionColor, awtSelectedTextColor) {
        runOnEdt {
            syncSwingColors(editorPane, scrollPane, awtBgColor, awtSelectionColor, awtSelectedTextColor)
        }
    }

    val currentDocument = rememberUpdatedState(document)

    fun clampedY(y: Int): Int {
        val viewport = scrollPane.viewport
        return y.coerceIn(0, (editorPane.height - viewport.height).coerceAtLeast(0))
    }

    fun reportPosition(doc: ArticleDocument? = currentDocument.value) {
        val targetDoc = doc ?: return
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
                contentRev = targetDoc.contentRev
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
            val viewport = scrollPane.viewport
            val y = viewport.viewPosition.y
            val viewportHeight = viewport.height
            val contentHeight = editorPane.height
            val layoutChanged = viewportHeight != restoreState.lastViewportHeight ||
                contentHeight != restoreState.lastContentHeight
            val dy = y - restoreState.lastScrollY
            restoreState.syncGeometry(y, viewportHeight, contentHeight)

            if (!restoreState.restoring && restoreState.applied && !restoreState.programmaticScroll) {
                debounce.restart()
                if (!layoutChanged && dy != 0) {
                    val maxY = (contentHeight - viewportHeight).coerceAtLeast(0)
                    controller.onScrollDelta?.invoke(
                        ArticleScrollDelta(
                            deltaY = dy.toFloat(),
                            scrollY = y.coerceAtLeast(0).toFloat(),
                            maxScrollY = maxY.toFloat()
                        )
                    )
                }
            }
        }
        scrollPane.viewport.addChangeListener(listener)

        val inactivePainter = DefaultHighlighter.DefaultHighlightPainter(Color(255, 235, 59, 160))
        val activePainter = DefaultHighlighter.DefaultHighlightPainter(Color(255, 152, 0, 220))

        fun updateHighlights() {
            val highlighter = editorPane.highlighter
            highlighter.removeAllHighlights()
            for (i in findState.matchRanges.indices) {
                val range = findState.matchRanges[i]
                val painter = if (i == findState.activeIndex) activePainter else inactivePainter
                try {
                    highlighter.addHighlight(range.first, range.last + 1, painter)
                } catch (_: Exception) {}
            }
            if (findState.activeIndex in findState.matchRanges.indices) {
                val activeRange = findState.matchRanges[findState.activeIndex]
                try {
                    restoreState.programmaticScroll = true
                    val rect = editorPane.modelToView2D(activeRange.first)?.bounds
                    if (rect != null) {
                        editorPane.scrollRectToVisible(rect)
                    }
                } catch (_: Exception) {
                } finally {
                    restoreState.syncGeometry(
                        scrollPane.viewport.viewPosition.y,
                        scrollPane.viewport.height,
                        editorPane.height
                    )
                    restoreState.programmaticScroll = false
                }
            }
        }

        controller.bindPlatform(
            scrollToSection = { secId ->
                runOnEdt {
                    restoreState.programmaticScroll = true
                    try {
                        editorPane.scrollToReference(secId)
                    } catch (_: Exception) {
                    } finally {
                        restoreState.syncGeometry(
                            scrollPane.viewport.viewPosition.y,
                            scrollPane.viewport.height,
                            editorPane.height
                        )
                        restoreState.programmaticScroll = false
                    }
                }
            },
            findAll = { query ->
                runOnEdt {
                    val doc = editorPane.document
                    val text = try {
                        doc.getText(0, doc.length)
                    } catch (_: Exception) { "" }

                    val matches = mutableListOf<IntRange>()
                    if (query.isNotEmpty() && text.isNotEmpty()) {
                        var idx = 0
                        while (true) {
                            val next = text.indexOf(query, idx, ignoreCase = true)
                            if (next < 0) break
                            matches.add(next until (next + query.length))
                            idx = next + query.length
                        }
                    }
                    findState.matchRanges = matches
                    findState.activeIndex = if (matches.isNotEmpty()) 0 else -1
                    updateHighlights()
                    controller.onFindResult?.invoke(matches.size, 0)
                }
            },
            clearFindMatches = {
                runOnEdt {
                    findState.matchRanges = emptyList()
                    findState.activeIndex = -1
                    editorPane.highlighter.removeAllHighlights()
                    controller.onFindResult?.invoke(0, 0)
                }
            },
            findNext = { forward ->
                runOnEdt {
                    if (findState.matchRanges.isNotEmpty()) {
                        findState.activeIndex = if (forward) {
                            (findState.activeIndex + 1) % findState.matchRanges.size
                        } else {
                            (findState.activeIndex - 1 + findState.matchRanges.size) % findState.matchRanges.size
                        }
                        updateHighlights()
                        controller.onFindResult?.invoke(findState.matchRanges.size, findState.activeIndex)
                    }
                }
            },
            flushPosition = {
                runOnEdt {
                    debounce.stop()
                    reportPosition()
                }
            }
        )
        onDispose {
            val doc = currentDocument.value
            debounce.stop()
            runOnEdt {
                if (doc != null) {
                    reportPosition(doc)
                }
            }
            scrollPane.viewport.removeChangeListener(listener)
            controller.unbindPlatform()
        }
    }

    LaunchedEffect(document?.revision) {
        val doc = document ?: return@LaunchedEffect
        findState.matchRanges = emptyList()
        findState.activeIndex = -1
        runOnEdt {
            editorPane.highlighter.removeAllHighlights()
        }
        controller.onFindResult?.invoke(0, 0)
        restoreState.restoring = true
        restoreState.applied = false
        try {
            runOnEdt {
                editorPane.text = doc.html
            }
            // Layout settles asynchronously; apply twice so the target survives the first pass.
            repeat(2) {
                delay(LAYOUT_SETTLE_MS)
                runOnEdt {
                    applyStart(doc)
                    restoreState.syncGeometry(
                        scrollPane.viewport.viewPosition.y,
                        scrollPane.viewport.height,
                        editorPane.height
                    )
                }
            }
            restoreState.applied = true
        } finally {
            restoreState.restoring = false
        }
    }

    SwingPanel(
        factory = {
            scrollPane.apply {
                background = awtBgColor
                viewport.background = awtBgColor
            }
        },
        modifier = modifier
    )
}
