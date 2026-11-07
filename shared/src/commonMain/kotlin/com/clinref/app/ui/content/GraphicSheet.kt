package com.clinref.app.ui.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clinref.app.domain.GraphicData
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.adaptive.LocalIsWideScreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GraphicSheet(
    graphicId: String,
    onDismiss: () -> Unit,
    viewModel: GraphicViewModel = koinViewModel(),
) {
    val isWideScreen = LocalIsWideScreen.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(graphicId) {
        viewModel.loadGraphic(graphicId)
    }

    val colorScheme = MaterialTheme.colorScheme
    val themeColors = remember(colorScheme) { ThemeColors.fromColorScheme(colorScheme) }
    val graphicCss = remember(themeColors) { CssBuilder(themeColors).buildGraphicPopupCss() }

    var sheetLoading by remember(graphicId) { mutableStateOf(true) }
    var webViewError by remember(graphicId) { mutableStateOf(false) }

    fun retryLoading() {
        sheetLoading = true
        webViewError = false
        viewModel.retry()
    }

    val bodyContent = @Composable {
        if (webViewError || uiState is GraphicUiState.Error) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Failed to load graphic")
                    TextButton(onClick = ::retryLoading) {
                        Text("Retry")
                    }
                }
            }
        } else {
            when (val state = uiState) {
                GraphicUiState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ContainedLoadingIndicator(modifier = Modifier.size(48.dp))
                }

                GraphicUiState.Error -> Unit

                is GraphicUiState.Success -> {
                    val fullHtml = remember(state.data, graphicCss) {
                        buildGraphicHtml(state.data, graphicCss)
                    }

                    GraphicWebView(
                        graphicId = graphicId,
                        fullHtml = fullHtml,
                        sheetLoading = sheetLoading,
                        onPageFinished = { sheetLoading = false },
                        onPageError = {
                            sheetLoading = false
                            webViewError = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }

    if (isWideScreen) {
        Dialog(onDismissRequest = onDismiss) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .widthIn(max = AdaptiveTokens.MaxCatalogListWidth)
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
                    .padding(vertical = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Clinical Reference Figure / Table",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        bodyContent()
                    }
                }
            }
        }
    } else {
        val sheetState = rememberBottomSheetState(
            initialValue = SheetValue.Expanded,
            enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
        )
        val scope = rememberCoroutineScope()

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = null,
            sheetGesturesEnabled = false,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                IconButton(
                    onClick = {
                        scope.launch { sheetState.hide() }
                            .invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
            ) {
                bodyContent()
            }
        }
    }
}

private val BASE64_VALIDATION_REGEX = Regex("^[A-Za-z0-9+/=\n\r ]+$")

internal fun buildGraphicHtml(graphicData: GraphicData, css: String): String {
    var html = graphicData.imageHtml
    val base64Image = graphicData.base64Image
    if (base64Image != null && BASE64_VALIDATION_REGEX.matches(base64Image)) {
        html = html.replaceFirst(Regex("<img\\s+([^>]*?)src=[\"'][^\"']*[\"']"), "<img $1src=\"data:image/png;base64,$base64Image\"")
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

