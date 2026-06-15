package com.uptodate.viewer.ui.content

private sealed class OutlineItem {
    data class Section(val section: OutlineSection) : OutlineItem()
    data class GroupHeader(val title: String, val indented: Boolean = false) : OutlineItem()
    data class Spacer(val dp: Int) : OutlineItem()
}

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
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

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var searchResultCount by remember { mutableStateOf(0) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Content") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.goForward() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(onClick = { viewModel.toggleOutline() }) {
                        Icon(Icons.Default.Menu, contentDescription = "Outline")
                    }
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { viewModel.toggleFavorite() }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
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
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        placeholder = { Text("Find in page...") },
                        singleLine = true
                    )
                    Text(
                        text = if (searchResultCount >= 0) "$searchResultCount results" else "",
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    IconButton(onClick = {
                        webView?.findAllAsync(searchQuery)
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Find Next")
                    }
                    IconButton(onClick = {
                        showSearch = false
                        searchQuery = ""
                        webView?.clearMatches()
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
                                        context.startActivity(intent)
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

                // Outline overlay
                if (showOutline && outlineSections.isNotEmpty()) {
                    val displayItems = remember(outlineSections) {
                        buildList {
                            var lastType: SectionType? = null
                            var lastGraphicGroup = ""
                            for (section in outlineSections) {
                                if (section.sectionType != lastType) {
                                    if (lastType != null) add(OutlineItem.Spacer(12))
                                    when (section.sectionType) {
                                        SectionType.GRAPHIC -> add(OutlineItem.GroupHeader("Graphics"))
                                        SectionType.RELATED -> add(OutlineItem.GroupHeader("Related Topics"))
                                        else -> {}
                                    }
                                }
                                if (section.sectionType == SectionType.GRAPHIC) {
                                    val group = when {
                                        section.graphicLabel.startsWith("table") -> "Tables"
                                        section.graphicLabel.startsWith("figure") -> "Figures"
                                        else -> "Other"
                                    }
                                    if (group != lastGraphicGroup) {
                                        if (lastGraphicGroup.isNotEmpty()) add(OutlineItem.Spacer(8))
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

                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .fillMaxHeight()
                            .align(Alignment.TopStart)
                            .shadow(4.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Text(
                            text = "Outline",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(displayItems) { item ->
                                when (item) {
                                    is OutlineItem.Spacer -> {
                                        Spacer(modifier = Modifier.height(item.dp.dp))
                                    }
                                    is OutlineItem.GroupHeader -> {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(
                                                start = if (item.indented) 28.dp else 16.dp,
                                                top = 10.dp,
                                                bottom = 4.dp
                                            )
                                        )
                                    }
                                    is OutlineItem.Section -> {
                                        val section = item.section
                                        Text(
                                            text = section.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = when (section.sectionType) {
                                                SectionType.GRAPHIC -> MaterialTheme.colorScheme.tertiary
                                                SectionType.RELATED -> MaterialTheme.colorScheme.primary
                                                SectionType.TOPIC -> MaterialTheme.colorScheme.onSurface
                                            },
                                            fontWeight = if (section.depth == 0 && section.sectionType == SectionType.TOPIC) FontWeight.Medium else FontWeight.Normal,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (section.actionJson != null) {
                                                        viewModel.handleOutlineAction(section.actionJson)
                                                    } else {
                                                        webView?.evaluateJavascript(
                                                            "document.getElementById('${section.id}')?.scrollIntoView({behavior:'smooth'})",
                                                            null
                                                        )
                                                    }
                                                }
                                                .padding(
                                                    start = (12 + section.depth * 16).dp,
                                                    end = 12.dp,
                                                    top = 6.dp,
                                                    bottom = 6.dp
                                                )
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
