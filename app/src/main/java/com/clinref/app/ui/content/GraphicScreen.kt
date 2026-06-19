package com.clinref.app.ui.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GraphicScreen(
    graphicId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GraphicViewModel = hiltViewModel()
) {
    val graphicData by viewModel.graphicData.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(graphicId) {
        viewModel.loadGraphic(graphicId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = graphicData?.title?.ifEmpty { "Graphic" } ?: "Graphic",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    Modifier
                )
        ) {
            graphicData?.let { data ->
                val themeColors = ThemeColors.fromColorScheme(MaterialTheme.colorScheme)
                val fullHtml = remember(data, themeColors) {
                    val graphicCss = CssBuilder(themeColors).graphicViewer()
                    buildGraphicHtml(data, graphicCss)
                }
                GraphicSheetContent(
                    graphicId = data.id,
                    fullHtml = fullHtml,
                    isLoading = isLoading,
                    onLoadingFinished = { },
                    onLoadingError = { },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isLoading) {
                LoadingIndicator(
                    modifier = Modifier
                        .size(48.dp)
                        .align(Alignment.Center)
                )
            }
        }
    }
}
