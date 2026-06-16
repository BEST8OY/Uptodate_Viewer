package com.uptodate.viewer.ui.content

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

private sealed class OutlineItem {
    data class Section(val section: OutlineSection) : OutlineItem()
    data class GroupHeader(val title: String, val indented: Boolean = false) : OutlineItem()
    data class Spacer(val dp: Int) : OutlineItem()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ContentScreen(
    topicId: String,
    onBack: () -> Unit,
    viewModel: ContentViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    // Load topic when screen appears
    LaunchedEffect(topicId) {
        viewModel.resetNavigationHistory()
        viewModel.loadTopic(topicId)
    }

    // Set theme colors for CSS generation
    val colorScheme = MaterialTheme.colorScheme
    LaunchedEffect(colorScheme) {
        viewModel.setThemeColors(ThemeColors.fromColorScheme(colorScheme))
    }

    val processedHtml by viewModel.processedHtml.collectAsState()
    val isFavorite by viewModel.isFavorite.collectAsState()
    val showOutline by viewModel.showOutline.collectAsState()
    val outlineSections by viewModel.outlineSections.collectAsState()
    val graphicDialog by viewModel.graphicDialog.collectAsState()
    val contributorsDialog by viewModel.contributorsDialog.collectAsState()
    val scrollToSection by viewModel.scrollToSection.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val canGoBack by viewModel.canGoBack.collectAsState()
    val canGoForward by viewModel.canGoForward.collectAsState()
    val articleTitle by viewModel.articleTitle.collectAsState()
    val activeSectionId by viewModel.activeSectionId.collectAsState()
    val error by viewModel.error.collectAsState()

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var searchResultCount by remember { mutableStateOf(0) }
    var searchResultIndex by remember { mutableStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Scroll to section when requested
    LaunchedEffect(scrollToSection) {
        scrollToSection?.let { section ->
            webView?.evaluateJavascript(
                "document.getElementById('$section')?.scrollIntoView({behavior:'smooth'})",
                null
            )
            viewModel.clearScrollToSection()
        }
    }

    // Auto-focus search field when opened
    LaunchedEffect(showSearch) {
        if (showSearch) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Scaffold(
        topBar = {
            ContentTopBar(
                title = articleTitle,
                onBackClick = onBack
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Content area
                Box(modifier = Modifier.weight(1f)) {
                    HtmlContentWebView(
                        processedHtml = processedHtml,
                        onAction = { viewModel.handleAction(it) },
                        onFindResult = { searchResultCount = it },
                        onWebViewCreated = { webView = it },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (!showOutline) {
                        if (isLoading) {
                            ContentLoadingView(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }

                        error?.let { errorMsg ->
                            ContentErrorView(
                                errorMsg = errorMsg,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }

                    // Outline overlay
                    OutlineOverlay(
                        showOutline = showOutline,
                        outlineSections = outlineSections,
                        activeSectionId = activeSectionId,
                        onSectionClick = { section ->
                            viewModel.setActiveSection(section.id)
                            if (section.actionJson != null) {
                                viewModel.handleOutlineAction(section.actionJson)
                            } else {
                                webView?.evaluateJavascript(
                                    "document.getElementById('${section.id}')?.scrollIntoView({behavior:'smooth'})",
                                    null
                                )
                            }
                        },
                        onDismiss = { viewModel.toggleOutline() }
                    )
                }
            }

            // Floating action toolbar
            ContentFloatingToolbar(
                showSearch = showSearch,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                isFavorite = isFavorite,
                outlineEnabled = outlineSections.isNotEmpty(),
                searchQuery = searchQuery,
                searchResultCount = searchResultCount,
                searchResultIndex = searchResultIndex,
                onBackClick = { viewModel.goBack() },
                onForwardClick = { viewModel.goForward() },
                onFavoriteClick = { viewModel.toggleFavorite() },
                onOutlineClick = { viewModel.toggleOutline() },
                onSearchClick = { showSearch = !showSearch },
                onSearchQueryChange = {
                    searchQuery = it
                    searchResultIndex = 0
                    webView?.findAllAsync(it)
                },
                onSearchPrevious = {
                    if (searchResultCount > 0) {
                        webView?.findNext(false)
                        searchResultIndex = if (searchResultIndex > 0) searchResultIndex - 1 else searchResultCount - 1
                    }
                },
                onSearchNext = {
                    if (searchResultCount > 0) {
                        webView?.findNext(true)
                        searchResultIndex = if (searchResultIndex < searchResultCount - 1) searchResultIndex + 1 else 0
                    }
                },
                onSearchClose = {
                    showSearch = false
                    searchQuery = ""
                    searchResultIndex = 0
                    webView?.clearMatches()
                },
                searchFocusRequester = searchFocusRequester,
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .imePadding()
            )
        }
    }

    // Graphic dialog
    graphicDialog?.let { data ->
        GraphicSheet(
            graphicData = data,
            onDismiss = { viewModel.dismissGraphicDialog() }
        )
    }

    // Contributors dialog
    contributorsDialog?.let { data ->
        ContributorsDialog(
            contributors = data,
            onDismiss = { viewModel.dismissContributorsDialog() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentTopBar(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TopAppBar(
        title = {
            Text(
                text = title.ifEmpty { "Content" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentFloatingToolbar(
    showSearch: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isFavorite: Boolean,
    outlineEnabled: Boolean,
    searchQuery: String,
    searchResultCount: Int,
    searchResultIndex: Int,
    onBackClick: () -> Unit,
    onForwardClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onOutlineClick: () -> Unit,
    onSearchClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchPrevious: () -> Unit,
    onSearchNext: () -> Unit,
    onSearchClose: () -> Unit,
    searchFocusRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val vibrantColors = FloatingToolbarDefaults.vibrantFloatingToolbarColors()

    AnimatedContent(
        targetState = showSearch,
        transitionSpec = {
            fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 90)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = 90))
        },
        label = "SearchToolbarTransition",
        modifier = modifier
    ) { isSearching ->
        if (isSearching) {
            HorizontalFloatingToolbar(
                expanded = true,
                shape = CircleShape,
                colors = FloatingToolbarDefaults.standardFloatingToolbarColors(),
                leadingContent = {
                    IconButton(
                        onClick = {
                            onSearchClose()
                            keyboardController?.hide()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        if (searchQuery.isNotEmpty()) {
                            Text(
                                text = "${if (searchResultCount > 0) searchResultIndex + 1 else 0}/$searchResultCount",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }

                        IconButton(
                            onClick = onSearchPrevious,
                            enabled = searchQuery.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropUp,
                                contentDescription = "Find Previous",
                                tint = if (searchQuery.isNotEmpty()) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                }
                            )
                        }

                        IconButton(
                            onClick = onSearchNext,
                            enabled = searchQuery.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Find Next",
                                tint = if (searchQuery.isNotEmpty()) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                }
                            )
                        }
                    }
                },
                content = {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .focusRequester(searchFocusRequester),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Find in page",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            )
        } else {
            HorizontalFloatingToolbar(
                expanded = true,
                shape = CircleShape,
                floatingActionButton = {
                    FloatingToolbarDefaults.VibrantFloatingActionButton(
                        onClick = onSearchClick,
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                colors = vibrantColors,
                content = {
                    IconButton(onClick = onBackClick, enabled = canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = onForwardClick, enabled = canGoForward) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(
                        onClick = onOutlineClick,
                        enabled = outlineEnabled
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = "Outline")
                    }
                    IconButton(onClick = onFavoriteClick) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites"
                        )
                    }
                }
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlContentWebView(
    processedHtml: String?,
    onAction: (String) -> Unit,
    onFindResult: (Int) -> Unit,
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (url.startsWith("appaction://")) {
                            val actionId = url.removePrefix("appaction://")
                            onAction(actionId)
                            return true
                        }
                        if (url.startsWith("http://") || url.startsWith("https://")) {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                request.url
                            )
                            try {
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                            return true
                        }
                        return false
                    }
                }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                setFindListener { _, numberOfMatches, _ ->
                    onFindResult(numberOfMatches)
                }
                addJavascriptInterface(
                    JsBridge { jsonStr ->
                        onAction("manual_$jsonStr")
                    },
                    "Android"
                )
                webViewRef = this
                onWebViewCreated(this)
            }
        },
        update = { wv ->
            processedHtml?.let { html ->
                wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        },
        modifier = modifier
    )

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContentLoadingView(
    modifier: Modifier = Modifier
) {
    LoadingIndicator(
        modifier = modifier.size(48.dp)
    )
}

@Composable
private fun ContentErrorView(
    errorMsg: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = "Error",
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = errorMsg,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun OutlineOverlay(
    showOutline: Boolean,
    outlineSections: List<OutlineSection>,
    activeSectionId: String?,
    onSectionClick: (OutlineSection) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!showOutline || outlineSections.isEmpty()) return

    val displayItems = remember(outlineSections) {
        buildList {
            var lastType: SectionType? = null
            var lastGraphicGroup = ""
            add(OutlineItem.GroupHeader("Outline"))
            for (section in outlineSections) {
                if (section.sectionType != lastType) {
                    when (section.sectionType) {
                        SectionType.GRAPHIC -> {
                            add(OutlineItem.Spacer(8))
                            add(OutlineItem.GroupHeader("Graphics"))
                        }
                        SectionType.RELATED -> {
                            add(OutlineItem.Spacer(8))
                            add(OutlineItem.GroupHeader("Related Topics"))
                        }
                        else -> {}
                    }
                }
                if (section.sectionType == SectionType.GRAPHIC) {
                    val group = when (section.graphicSubtype) {
                        "graphic_table" -> "Tables"
                        "graphic_figure" -> "Figures"
                        "graphic_algorithm" -> "Algorithms"
                        "graphic_picture" -> "Pictures"
                        "graphic_diagnosticimage" -> "Diagnostic Images"
                        else -> "Other"
                    }
                    if (group != lastGraphicGroup) {
                        if (lastGraphicGroup.isNotEmpty()) add(OutlineItem.Spacer(4))
                        add(OutlineItem.GroupHeader(group, indented = true))
                        lastGraphicGroup = group
                    }
                } else {
                    lastGraphicGroup = ""
                }
                add(OutlineItem.Section(section))
                lastType = section.sectionType
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                .clickable { onDismiss() }
        )

        // Outline panel
        Column(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .shadow(8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(displayItems) { item ->
                    when (item) {
                        is OutlineItem.Spacer -> {
                            Spacer(modifier = Modifier.height(item.dp.dp))
                        }
                        is OutlineItem.GroupHeader -> {
                            Text(
                                text = item.title.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = if (item.indented) 28.dp else 16.dp,
                                    top = 12.dp,
                                    bottom = 6.dp
                                )
                            )
                        }
                        is OutlineItem.Section -> {
                            val section = item.section
                            val isTopic = section.sectionType == SectionType.TOPIC
                            val isActive = section.id == activeSectionId
                            Surface(
                                onClick = { onSectionClick(section) },
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                },
                                shape = MaterialTheme.shapes.small
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = (12 + section.depth * 16).dp,
                                            end = 12.dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isTopic && section.depth == 0) {
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .background(
                                                    MaterialTheme.colorScheme.primary,
                                                    MaterialTheme.shapes.extraSmall
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when {
                                            isActive -> MaterialTheme.colorScheme.onPrimaryContainer
                                            section.sectionType == SectionType.GRAPHIC -> MaterialTheme.colorScheme.tertiary
                                            section.sectionType == SectionType.RELATED -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        },
                                        fontWeight = if ((isTopic && section.depth == 0) || isActive) FontWeight.Medium else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
