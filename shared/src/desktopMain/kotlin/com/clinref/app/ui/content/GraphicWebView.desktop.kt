package com.clinref.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel

@Composable
actual fun GraphicWebView(
    graphicId: String,
    fullHtml: String,
    onPageFinished: () -> Unit,
    onPageError: () -> Unit,
    modifier: Modifier
) {
    val currentOnPageFinished = rememberUpdatedState(onPageFinished)
    val currentOnPageError = rememberUpdatedState(onPageError)
    val swingColors = rememberSwingThemeColors()

    val (editorPane, scrollPane) = remember {
        createHtmlEditorPane(
            colors = swingColors,
            onHyperlink = { _, desc -> openExternalUri(desc) }
        )
    }

    BindSwingThemeColors(editorPane, scrollPane, swingColors)

    LaunchedEffect(fullHtml) {
        runOnEdt {
            try {
                editorPane.text = fullHtml
                currentOnPageFinished.value()
            } catch (_: Exception) {
                currentOnPageError.value()
            }
        }
    }

    SwingPanel(
        factory = { scrollPane },
        modifier = modifier
    )
}
