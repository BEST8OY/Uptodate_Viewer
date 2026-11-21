package com.clinref.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.awt.Color
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.JEditorPane
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.event.HyperlinkEvent
import javax.swing.text.DefaultCaret
import javax.swing.text.html.HTMLEditorKit

private inline fun runOnEdt(crossinline block: () -> Unit) {
    if (javax.swing.SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        javax.swing.SwingUtilities.invokeLater { block() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
actual fun GraphicWebView(
    graphicId: String,
    fullHtml: String,
    sheetLoading: Boolean,
    onPageFinished: () -> Unit,
    onPageError: () -> Unit,
    modifier: Modifier
) {
    val bgColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val awtBgColor = remember(bgColor) { Color(bgColor.toArgb()) }
    val awtSelectionColor = remember(primaryColor) { Color(primaryColor.toArgb()) }
    val awtSelectedTextColor = remember(onPrimaryColor) { Color(onPrimaryColor.toArgb()) }

    val (editorPane, scrollPane) = remember {
        val pane = JEditorPane().apply {
            isEditable = false
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            background = awtBgColor
            selectionColor = awtSelectionColor
            selectedTextColor = awtSelectedTextColor
            (caret as? DefaultCaret)?.let { defaultCaret ->
                defaultCaret.isSelectionVisible = true
            }
            addHyperlinkListener { e ->
                if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                    val desc = e.description ?: e.url?.toString()
                    if (desc != null && (desc.startsWith("http://") || desc.startsWith("https://"))) {
                        try {
                            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                                Desktop.getDesktop().browse(URI(desc))
                            }
                        } catch (_: Exception) { }
                    }
                }
            }
            val popup = JPopupMenu().apply {
                val copyItem = JMenuItem("Copy").apply {
                    accelerator = KeyStroke.getKeyStroke(
                        KeyEvent.VK_C,
                        Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
                    )
                    addActionListener { copy() }
                }
                val selectAllItem = JMenuItem("Select All").apply {
                    accelerator = KeyStroke.getKeyStroke(
                        KeyEvent.VK_A,
                        Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
                    )
                    addActionListener { selectAll() }
                }
                add(copyItem)
                add(selectAllItem)
            }
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)
                override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
                private fun maybeShowPopup(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        requestFocusInWindow()
                        popup.getComponent(0).isEnabled = selectionStart != selectionEnd
                        popup.show(this@apply, e.x, e.y)
                    }
                }
            })
        }
        val scroll = JScrollPane(pane).apply {
            border = null
            background = awtBgColor
            viewport.background = awtBgColor
        }
        pane to scroll
    }

    LaunchedEffect(awtBgColor, awtSelectionColor, awtSelectedTextColor) {
        runOnEdt {
            editorPane.background = awtBgColor
            editorPane.selectionColor = awtSelectionColor
            editorPane.selectedTextColor = awtSelectedTextColor
            scrollPane.background = awtBgColor
            scrollPane.viewport.background = awtBgColor
        }
    }

    LaunchedEffect(fullHtml) {
        try {
            runOnEdt {
                editorPane.text = fullHtml
            }
            onPageFinished()
        } catch (_: Exception) {
            onPageError()
        }
    }

    Box(
        modifier = modifier.semantics {
            contentDescription = "Graphic: $graphicId"
        }
    ) {
        SwingPanel(
            factory = { scrollPane },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = sheetLoading,
            enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
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

