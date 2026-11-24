package com.clinref.app.ui.content

import java.awt.Color
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

internal data class SwingHtmlComponents(
    val editorPane: JEditorPane,
    val scrollPane: JScrollPane
)

internal fun createHtmlEditorPane(
    bgColor: Color,
    selectionColor: Color,
    selectedTextColor: Color,
    onHyperlink: (pane: JEditorPane, description: String) -> Unit = { _, _ -> }
): SwingHtmlComponents {
    val pane = JEditorPane().apply {
        isEditable = false
        contentType = "text/html"
        val kit = HTMLEditorKit()
        editorKit = kit
        background = bgColor
        this.selectionColor = selectionColor
        this.selectedTextColor = selectedTextColor
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
        background = bgColor
        viewport.background = bgColor
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
