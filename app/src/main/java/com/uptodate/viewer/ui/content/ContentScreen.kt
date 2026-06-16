package com.uptodate.viewer.ui.content

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
    viewModel: ContentViewModel = hiltViewModel()
) {
    // Load topic when screen appears
    LaunchedEffect(topicId) {
        viewModel.loadTopic(topicId)
    }

    // Set theme colors for CSS generation
    val colorScheme = MaterialTheme.colorScheme
    LaunchedEffect(colorScheme) {
        viewModel.setThemeColors(ThemeColors.fromColorScheme(colorScheme))
    }


    val topicContent by viewModel.topicContent.collectAsState()
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
            TopAppBar(
                title = {
                    Text(
                        text = articleTitle.ifEmpty { "Content" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }, enabled = canGoBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
            // Search bar
            if (showSearch) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            webView?.findAllAsync(it)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                            .focusRequester(searchFocusRequester),
                        placeholder = { Text("Find in page...") },
                        singleLine = true
                    )
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "${searchResultCount.coerceAtLeast(0)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        IconButton(onClick = {
                            webView?.findNext(false)
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowUp,
                                contentDescription = "Find Previous",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        IconButton(onClick = {
                            webView?.findNext(true)
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowDown,
                                contentDescription = "Find Next",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(onClick = {
                        showSearch = false
                        searchQuery = ""
                        webView?.clearMatches()
                        keyboardController?.hide()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close Search")
                    }
                }
            }

            // Content area
            Box(modifier = Modifier.weight(1f)) {
                // Main content (full width)
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
                                        viewModel.handleAction(actionId)
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
                            setFindListener { numberOfMatches ->
                                searchResultCount = numberOfMatches
                            }
                            addJavascriptInterface(
                                JsBridge { jsonStr ->
                                    viewModel.handleAction("manual_$jsonStr")
                                },
                                "Android"
                            )
                            webView = this
                        }
                    },
                    update = { wv ->
                        processedHtml?.let { html ->
                            wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                DisposableEffect(Unit) {
                    onDispose {
                        webView?.apply {
                            stopLoading()
                            destroy()
                        }
                    }
                }

                if (!showOutline) {
                    if (isLoading) {
                        LoadingIndicator(
                            modifier = Modifier
                                .size(48.dp)
                                .align(Alignment.Center)
                        )
                    }

                    error?.let { errorMsg ->
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
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
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // Outline overlay
                if (showOutline && outlineSections.isNotEmpty()) {
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

                    // Scrim
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                            .clickable { viewModel.toggleOutline() }
                    )

                    // Outline panel
                    Column(
                        modifier = Modifier
                            .width(260.dp)
                            .fillMaxHeight()
                            .align(Alignment.TopStart)
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
                                            onClick = {
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
                                                    fontWeight = if (isTopic && section.depth == 0 || isActive) FontWeight.Medium else FontWeight.Normal
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
        }

        // Floating action toolbar
        HorizontalFloatingToolbar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset(y = -ScreenOffset)
                .zIndex(1f),
            expanded = true,
            leadingContent = {
                IconButton(onClick = { viewModel.goBack() }, enabled = canGoBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                IconButton(onClick = { viewModel.goForward() }, enabled = canGoForward) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                }
            },
            trailingContent = {
                IconButton(onClick = { viewModel.toggleFavorite() }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites"
                    )
                }
            },
            content = {
                IconButton(
                    onClick = { viewModel.toggleOutline() },
                    enabled = outlineSections.isNotEmpty()
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Outline")
                }
                IconButton(onClick = { showSearch = !showSearch }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
        )
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
