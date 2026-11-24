package com.clinref.app.ui.content

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.GraphicData
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.common.ClinRefBottomSheet
import org.koin.compose.viewmodel.koinViewModel

private val GRAPHIC_PREFIX_REGEX = Regex("^graphic-", RegexOption.IGNORE_CASE)

internal fun normalizeGraphicId(id: String): String =
    id.trim().replaceFirst(GRAPHIC_PREFIX_REGEX, "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphicSheet(
    graphicId: String,
    onDismiss: () -> Unit,
    viewModel: GraphicViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    val cleanId = remember(graphicId) { normalizeGraphicId(graphicId) }

    LaunchedEffect(cleanId) {
        viewModel.loadGraphic(cleanId)
    }

    DisposableEffect(cleanId) {
        onDispose {
            viewModel.clearGraphic()
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) { ThemeColors.fromColorScheme(colorScheme) }

    var sheetLoading by remember(cleanId) { mutableStateOf(true) }
    var webViewError by remember(cleanId) { mutableStateOf(false) }

    fun retryLoading() {
        sheetLoading = true
        webViewError = false
        viewModel.retry()
    }

    ClinRefBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        heightFraction = 0.88f,
        maxWidth = AdaptiveTokens.MaxSheetWideWidth,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        showDragHandle = false,
        showDivider = true,
        sheetGesturesEnabled = false
    ) { _, bottomInset ->
        val bottomInsetDp = bottomInset.value.toInt().coerceAtLeast(0)
        val graphicCss = remember(themeColors, bottomInsetDp) {
            CssBuilder(themeColors).buildGraphicPopupCss(bottomInsetDp = bottomInsetDp)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            GraphicBodyContent(
                cleanId = cleanId,
                uiState = uiState,
                graphicCss = graphicCss,
                sheetLoading = sheetLoading,
                webViewError = webViewError,
                onPageFinished = { sheetLoading = false },
                onPageError = {
                    sheetLoading = false
                    webViewError = true
                },
                onRetry = ::retryLoading,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GraphicBodyContent(
    cleanId: String,
    uiState: GraphicUiState,
    graphicCss: String,
    sheetLoading: Boolean,
    webViewError: Boolean,
    onPageFinished: () -> Unit,
    onPageError: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    when {
        webViewError || uiState is GraphicUiState.Error -> {
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = "Failed to load graphic",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Text("Retry")
                    }
                }
            }
        }
        uiState is GraphicUiState.Success && normalizeGraphicId(uiState.graphicId).equals(cleanId, ignoreCase = true) -> {
            val fullHtml = remember(uiState.data, graphicCss) {
                buildGraphicHtml(uiState.data, graphicCss)
            }

            Box(
                modifier = modifier.semantics {
                    contentDescription = "Graphic: $cleanId"
                }
            ) {
                GraphicWebView(
                    graphicId = cleanId,
                    fullHtml = fullHtml,
                    onPageFinished = onPageFinished,
                    onPageError = onPageError,
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
        else -> {
            // Initial loading or transitional stale-guard while fetching requested cleanId
            Box(
                modifier = modifier,
                contentAlignment = Alignment.Center
            ) {
                ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
            }
        }
    }
}

private val BASE64_VALIDATION_REGEX = Regex("^[A-Za-z0-9+/=\n\r ]+$")
private val IMG_SRC_REGEX = Regex("<img\\s+([^>]*?)src=[\"'][^\"']*[\"']", RegexOption.IGNORE_CASE)

internal fun buildGraphicHtml(graphicData: GraphicData, css: String): String {
    var html = graphicData.imageHtml
    val base64Image = graphicData.base64Image
    if (base64Image != null && BASE64_VALIDATION_REGEX.matches(base64Image)) {
        val cleanB64 = base64Image.filterNot(Char::isWhitespace)
        html = html.replaceFirst(IMG_SRC_REGEX, "<img $1src=\"data:image/png;base64,$cleanB64\"")
    }
    html = html.replace("class=\"graphic\"", "class=\"graphic_view\"")

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, minimum-scale=0.5, maximum-scale=5.0, user-scalable=yes">
            <style>$css</style>
        </head>
        <body>$html</body>
        </html>
    """.trimIndent()
}
