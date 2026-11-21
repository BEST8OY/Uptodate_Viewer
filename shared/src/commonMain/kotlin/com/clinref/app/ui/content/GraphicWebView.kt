package com.clinref.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun GraphicWebView(
    graphicId: String,
    fullHtml: String,
    sheetLoading: Boolean,
    onPageFinished: () -> Unit,
    onPageError: () -> Unit,
    modifier: Modifier = Modifier
)

