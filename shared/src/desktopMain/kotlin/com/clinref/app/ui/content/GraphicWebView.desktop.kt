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
import androidx.compose.runtime.rememberUpdatedState
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
    val currentOnPageFinished = rememberUpdatedState(onPageFinished)
    val currentOnPageError = rememberUpdatedState(onPageError)

    val bgColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val awtBgColor = remember(bgColor) { Color(bgColor.toArgb()) }
    val awtSelectionColor = remember(primaryColor) { Color(primaryColor.toArgb()) }
    val awtSelectedTextColor = remember(onPrimaryColor) { Color(onPrimaryColor.toArgb()) }

    val (editorPane, scrollPane) = remember {
        createHtmlEditorPane(
            bgColor = awtBgColor,
            selectionColor = awtSelectionColor,
            selectedTextColor = awtSelectedTextColor,
            onHyperlink = { _, desc ->
                if (desc.startsWith("http://") || desc.startsWith("https://")) {
                    try {
                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            Desktop.getDesktop().browse(URI(desc))
                        }
                    } catch (_: Exception) { }
                }
            }
        )
    }

    LaunchedEffect(awtBgColor, awtSelectionColor, awtSelectedTextColor) {
        runOnEdt {
            syncSwingColors(editorPane, scrollPane, awtBgColor, awtSelectionColor, awtSelectedTextColor)
        }
    }

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
