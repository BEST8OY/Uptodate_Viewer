package com.clinref.app.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import java.awt.Color
import java.awt.Desktop
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Toolkit
import java.net.URI
import javax.swing.JEditorPane
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.text.DefaultCaret
import javax.swing.text.html.HTMLEditorKit

internal inline fun runOnEdt(crossinline block: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) {
        block()
    } else {
        SwingUtilities.invokeLater { block() }
    }
}

internal fun openExternalUri(url: String) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {
    }
}

internal data class SwingThemeColors(
    val background: Color,
    val selection: Color,
    val selectedText: Color
)

@Composable
internal fun rememberSwingThemeColors(): SwingThemeColors {
    val bgColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    return remember(bgColor, primaryColor, onPrimaryColor) {
        SwingThemeColors(
            background = Color(bgColor.toArgb()),
            selection = Color(primaryColor.toArgb()),
            selectedText = Color(onPrimaryColor.toArgb())
        )
    }
}

@Composable
internal fun BindSwingThemeColors(
    editorPane: JEditorPane,
    scrollPane: JScrollPane,
    colors: SwingThemeColors
) {
    LaunchedEffect(colors) {
        runOnEdt {
            syncSwingColors(
                editorPane = editorPane,
                scrollPane = scrollPane,
                bgColor = colors.background,
                selectionColor = colors.selection,
                selectedTextColor = colors.selectedText
            )
        }
    }
}

internal data class SwingHtmlComponents(
    val editorPane: JEditorPane,
    val scrollPane: JScrollPane
)

internal fun createHtmlEditorPane(
    colors: SwingThemeColors,
    onHyperlink: (pane: JEditorPane, description: String) -> Unit = { _, _ -> }
): SwingHtmlComponents {
    val pane = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        val kit = HTMLEditorKit()
        editorKit = kit
        background = colors.background
        selectionColor = colors.selection
        selectedTextColor = colors.selectedText
        (caret as? DefaultCaret)?.let { defaultCaret ->
            defaultCaret.isSelectionVisible = true
        }
        addHyperlinkListener { e ->
            if (e.eventType == javax.swing.event.HyperlinkEvent.EventType.ACTIVATED) {
                val desc = e.description ?: e.url?.toString()
                if (desc != null) {
                    onHyperlink(this, desc)
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
    val sp = JScrollPane(pane).apply {
        border = null
        background = colors.background
        viewport.background = colors.background
    }
    return SwingHtmlComponents(pane, sp)
}

internal fun syncSwingColors(
    editorPane: JEditorPane,
    scrollPane: JScrollPane,
    bgColor: Color,
    selectionColor: Color,
    selectedTextColor: Color
) {
    editorPane.background = bgColor
    editorPane.selectionColor = selectionColor
    editorPane.selectedTextColor = selectedTextColor
    scrollPane.background = bgColor
    scrollPane.viewport.background = bgColor
}
