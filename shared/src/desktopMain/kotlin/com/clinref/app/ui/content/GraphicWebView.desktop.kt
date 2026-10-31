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
import androidx.compose.runtime.DisposableEffect
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
import java.net.URI
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

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
    val awtBgColor = Color(bgColor.toArgb())

    val (editorPane, scrollPane) = remember {
        val pane = JEditorPane().apply {
            isEditable = false
            contentType = "text/html"
            editorKit = HTMLEditorKit()
            background = awtBgColor
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
        }
        val scroll = JScrollPane(pane).apply {
            border = null
            viewport.background = awtBgColor
        }
        pane to scroll
    }

    LaunchedEffect(fullHtml) {
        try {
            editorPane.text = fullHtml
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
            exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
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

