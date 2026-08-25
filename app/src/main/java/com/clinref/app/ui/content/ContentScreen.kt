package com.clinref.app.ui.content

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContentScreen(
    topicId: String,
    sectionId: String? = null,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onGraphicSelected: (String) -> Unit,
    viewModel: ContentViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    LaunchedEffect(topicId, sectionId) {
        if (viewModel.currentTopicId.value != topicId) {
            viewModel.resetNavigationHistory()
            viewModel.loadTopic(topicId, sectionId = sectionId)
        } else if (sectionId != null) {
            viewModel.scrollToSection(sectionId)
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    LaunchedEffect(colorScheme) {
        viewModel.setThemeColors(ThemeColors.fromColorScheme(colorScheme))
    }

    val processedHtml by viewModel.processedHtml.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val showOutline by viewModel.showOutline.collectAsStateWithLifecycle()
    val outlineSections by viewModel.outlineSections.collectAsStateWithLifecycle()
    val contributorsDialog by viewModel.contributorsDialog.collectAsStateWithLifecycle()
    val scrollToSectionId by viewModel.scrollToSectionId.collectAsStateWithLifecycle()
    val scrollToSection by viewModel.scrollToSection.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val canGoBack by viewModel.canGoBack.collectAsStateWithLifecycle()
    val canGoForward by viewModel.canGoForward.collectAsStateWithLifecycle()
    val articleTitle by viewModel.articleTitle.collectAsStateWithLifecycle()
    val activeSectionId by viewModel.activeSectionId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val navigateToGraphic by viewModel.onNavigateToGraphic.collectAsStateWithLifecycle()

    var showSearch by remember { mutableStateOf(false) }
    val searchFieldState = rememberTextFieldState()
    var searchResultCount by remember { mutableStateOf(0) }
    // Authoritative active-match ordinal reported by WebView's FindListener; 0-based.
    var searchResultIndex by remember { mutableStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val webView = rememberArticleWebViewController()

    // Hides the nav toolbar as the user scrolls the article down and reveals it on upward
    // scroll. The WebView does not participate in Compose nested scroll, so its scroll deltas
    // drive the behavior directly; translation is applied via graphicsLayer on the toolbar
    // because the library's own scrollBehavior computes its collapse distance from the
    // immediate parent node (here a content-sized AnimatedContent wrapper), which would leave
    // part of the toolbar visible.
    val coroutineScope = rememberCoroutineScope()
    val motionScheme = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val context = LocalContext.current
    val accessibilityManager = remember {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    val toolbarHide = remember(coroutineScope, motionScheme) {
        FloatingToolbarHideBehavior(coroutineScope, motionScheme.defaultSpatialSpec()).apply {
            gate = {
                !(showSearch || showOutline) &&
                    accessibilityManager?.isTouchExplorationEnabled != true
            }
        }
    }
    toolbarHide.hideActivationThresholdPx = with(density) { 48.dp.toPx() }
    val bottomMarginPx = with(density) { 16.dp.toPx() }

    SideEffect {
        webView.onAction = viewModel::handleAction
        webView.onFindResult = { count, activeOrdinal ->
            searchResultCount = count
            searchResultIndex = activeOrdinal
        }
        webView.onContentScrolled = { dy -> toolbarHide.onScrolled(dy) }
    }

    val closeSearch: () -> Unit = {
        keyboardController?.hide()
        focusManager.clearFocus()
        showSearch = false
        searchFieldState.clearText()
        searchResultIndex = 0
        webView.clearFindMatches()
    }

    fun handleTopBarBackNavigation() {
        when {
            showSearch -> closeSearch()
            showOutline -> viewModel.toggleOutline()
            canGoBack -> viewModel.goBack()
            else -> onBack()
        }
    }

    LaunchedEffect(searchFieldState) {
        snapshotFlow { searchFieldState.text.toString() }
            .collect { query ->
                searchResultIndex = 0
                if (query.isNotEmpty()) {
                    webView.findAll(query)
                } else {
                    webView.clearFindMatches()
                }
            }
    }

    BackHandler(enabled = showSearch || showOutline || canGoBack) {
        handleTopBarBackNavigation()
    }

    // Reveal the toolbar whenever content changes or an overlay (search/outline) closes,
    // since upward-scroll reveal is unavailable while an overlay intercepts input.
    LaunchedEffect(processedHtml, showSearch, showOutline) {
        toolbarHide.reveal()
    }

    LaunchedEffect(scrollToSection) {
        scrollToSection?.let { section ->
            toolbarHide.suppressFor()
            toolbarHide.reveal()
            webView.scrollToSection(section)
            viewModel.clearScrollToSection()
        }
    }

    LaunchedEffect(navigateToGraphic) {
        navigateToGraphic?.let { graphicId ->
            onGraphicSelected(graphicId)
            viewModel.clearNavigationToGraphic()
        }
    }

    LaunchedEffect(showSearch) {
        if (showSearch) {
            focusManager.clearFocus()
            delay(80)
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Scaffold(
        topBar = {
            ContentTopBar(
                title = articleTitle,
                onBackClick = { handleTopBarBackNavigation() }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Composition order (this Box's first child) gives the toolbar focus priority for
            // a11y traversal; zIndex(1f) additionally draws it above the content Column.
            ContentFloatingToolbar(
                showSearch = showSearch,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                isFavorite = isFavorite,
                outlineEnabled = outlineSections.isNotEmpty(),
                searchFieldState = searchFieldState,
                searchResultCount = searchResultCount,
                searchResultIndex = searchResultIndex,
                onBackClick = {
                    viewModel.goBack()
                },
                onForwardClick = {
                    viewModel.goForward()
                },
                onHomeClick = onHome,
                onFavoriteClick = { viewModel.toggleFavorite() },
                onOutlineClick = { viewModel.toggleOutline() },
                onSearchClick = { showSearch = !showSearch },
                onSearchPrevious = {
                    if (searchResultCount > 0) webView.findNext(forward = false)
                },
                onSearchNext = {
                    if (searchResultCount > 0) webView.findNext(forward = true)
                },
                onSearchClose = closeSearch,
                searchFocusRequester = searchFocusRequester,
                modifier = modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .imePadding()
                    .zIndex(1f)
                    .onSizeChanged { size ->
                        toolbarHide.hideDistancePx = size.height + bottomMarginPx
                    }
                    .graphicsLayer { translationY = toolbarHide.translationY }
            )

            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    HtmlContentWebView(
                        processedHtml = processedHtml,
                        controller = webView,
                        initialSectionId = scrollToSectionId,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (!showOutline) {
                        error?.let { errorMsg ->
                            ContentErrorView(
                                errorMsg = errorMsg,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }

                    OutlineOverlay(
                        showOutline = showOutline,
                        outlineSections = outlineSections,
                        activeSectionId = activeSectionId,
                        onSectionClick = { section ->
                            viewModel.setActiveSection(section.id)
                            if (section.actionJson != null) {
                                viewModel.handleOutlineAction(section.actionJson)
                            } else {
                                webView.scrollToSection(section.id)
                            }
                            if (section.sectionType != SectionType.GRAPHIC) {
                                viewModel.toggleOutline()
                            }
                        },
                        onDismiss = { viewModel.toggleOutline() }
                    )
                }
            }

            if (!showOutline) {
                AnimatedVisibility(
                    visible = isLoading,
                    enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    ContentLoadingView()
                }
            }
        }
    }

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
private fun ContentLoadingView(
    modifier: Modifier = Modifier
) {
    ContainedLoadingIndicator(
        modifier = modifier.size(48.dp)
    )
}

@Composable
private fun ContentErrorView(
    errorMsg: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(32.dp)
            .testTag("content_error"),
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
