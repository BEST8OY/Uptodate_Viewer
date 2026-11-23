package com.clinref.app.ui.content

import com.clinref.app.ui.common.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.koin.compose.viewmodel.koinViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    isModal: Boolean = false,
    viewModel: ContentViewModel = koinViewModel(),
    modifier: Modifier = Modifier
) {
    LaunchedEffect(topicId, sectionId) {
        if (viewModel.currentTopicId.value == topicId && sectionId != null) {
            viewModel.scrollToSection(sectionId)
        } else {
            viewModel.open(topicId, sectionId = sectionId)
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    LaunchedEffect(colorScheme) {
        viewModel.setThemeColors(ThemeColors.fromColorScheme(colorScheme))
    }

    val document by viewModel.document.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val showOutline by viewModel.showOutline.collectAsStateWithLifecycle()
    val outlineSections by viewModel.outlineSections.collectAsStateWithLifecycle()
    val activeSheet by viewModel.activeSheet.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val canGoBack by viewModel.canGoBack.collectAsStateWithLifecycle()
    val canGoForward by viewModel.canGoForward.collectAsStateWithLifecycle()
    val articleTitle by viewModel.articleTitle.collectAsStateWithLifecycle()
    val activeSectionId by viewModel.activeSectionId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val navigateToGraphic by viewModel.onNavigateToGraphic.collectAsStateWithLifecycle()
    val openExternalUrl by viewModel.onOpenExternalUrl.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current

    var showSearch by remember { mutableStateOf(false) }
    val searchFieldState = rememberTextFieldState()
    var searchResultCount by remember { mutableStateOf(0) }
    var searchResultIndex by remember { mutableStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val webView = rememberArticleWebViewController()
    val isTouchExploration = rememberTouchExplorationState()

    val pinToolbarVisible = showSearch ||
        showOutline ||
        isTouchExploration ||
        isLoading ||
        error != null ||
        document == null ||
        activeSheet != null

    val scrollState = rememberArticleScrollState(pinnedVisible = pinToolbarVisible)
    // Immediate 0-frame visibility when entering a pinned state (e.g., opening search).
    val isToolbarVisible = pinToolbarVisible || scrollState.isToolbarVisible

    SideEffect {
        webView.onAction = viewModel::handleAction
        webView.onPositionChanged = viewModel::onPositionChanged
        webView.onScrollDelta = scrollState::onScrollDelta
        webView.startResolver = viewModel::resolveStart
        webView.onFindResult = { count, activeOrdinal ->
            searchResultCount = count
            searchResultIndex = activeOrdinal
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                webView.flushPosition()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
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
            showOutline -> viewModel.dismissOutline()
            isModal -> onBack()
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
        when {
            showSearch -> closeSearch()
            showOutline -> viewModel.dismissOutline()
            canGoBack -> viewModel.goBack()
        }
    }

    LaunchedEffect(document?.revision) {
        scrollState.reveal()
    }

    LaunchedEffect(Unit) {
        viewModel.sectionJumps.collect { section ->
            scrollState.reveal()
            webView.scrollToSection(section)
        }
    }

    LaunchedEffect(navigateToGraphic) {
        navigateToGraphic?.let { graphicId ->
            onGraphicSelected(graphicId)
            viewModel.clearNavigationToGraphic()
        }
    }

    LaunchedEffect(openExternalUrl) {
        openExternalUrl?.let { url ->
            try {
                uriHandler.openUri(url)
            } catch (_: Exception) {}
            viewModel.clearOpenExternalUrl()
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
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    val isModifier = event.isCtrlPressed || event.isMetaPressed
                    when {
                        isModifier && event.key == Key.F && !isLoading && error == null && document != null -> {
                            showSearch = true
                            true
                        }
                        event.key == Key.Escape && showSearch -> {
                            closeSearch()
                            true
                        }
                        else -> false
                    }
                } else false
            },
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            ContentTopBar(
                title = articleTitle,
                onBackClick = { handleTopBarBackNavigation() },
                isModal = isModal
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .clipToBounds()
        ) {
            val isImeActive = WindowInsets.ime.asPaddingValues().calculateBottomPadding() > 0.dp
            val toolbarBottomPadding = if (isImeActive) 8.dp else 16.dp

            AnimatedVisibility(
                visible = isToolbarVisible,
                enter = slideInVertically(
                    animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>(),
                    initialOffsetY = { it }
                ) + fadeIn(
                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()
                ),
                exit = slideOutVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    targetOffsetY = { it }
                ) + fadeOut(
                    animationSpec = MaterialTheme.motionScheme.fastEffectsSpec()
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
                    .imePadding()
                    .padding(bottom = toolbarBottomPadding)
                    .zIndex(1f)
            ) {
                ContentFloatingToolbar(
                    showSearch = showSearch,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    isFavorite = isFavorite,
                    outlineEnabled = outlineSections.isNotEmpty(),
                    searchFieldState = searchFieldState,
                    searchResultCount = searchResultCount,
                    searchResultIndex = searchResultIndex,
                    onBackClick = { viewModel.goBack() },
                    onForwardClick = { viewModel.goForward() },
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
                    interactive = isToolbarVisible
                )
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    HtmlContentWebView(
                        document = document,
                        controller = webView,
                        modifier = Modifier.fillMaxSize()
                    )

                    if (!showOutline) {
                        error?.let { errorMsg ->
                            ContentErrorView(
                                errorMsg = errorMsg,
                                onRetry = { viewModel.open(topicId, sectionId = sectionId) },
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }

                    if (showOutline && outlineSections.isNotEmpty()) {
                        OutlineOverlay(
                            outlineSections = outlineSections,
                            activeSectionId = activeSectionId,
                            onSectionClick = { section ->
                                viewModel.setActiveSection(section.id)
                                val actionJson = section.actionJson
                                if (actionJson != null) {
                                    viewModel.handleOutlineAction(actionJson)
                                } else {
                                    viewModel.scrollToSection(section.id)
                                }
                            },
                            onDismiss = { viewModel.dismissOutline() }
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isLoading && !showOutline,
                enter = fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
                exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                modifier = Modifier.align(Alignment.Center),
            ) {
                ContentLoadingView()
            }
        }
    }

    when (val sheet = activeSheet) {
        is ContentSheet.References -> {
            ReferenceSheet(
                data = sheet.data,
                onDismiss = { viewModel.dismissSheet() },
                onJumpTo = { num -> viewModel.scrollToSection("ref_$num") },
                onOpenUrl = { url ->
                    try {
                        uriHandler.openUri(url)
                    } catch (_: Exception) {}
                }
            )
        }
        is ContentSheet.Grade -> {
            GradeSheet(
                data = sheet.data,
                onDismiss = { viewModel.dismissSheet() }
            )
        }
        null -> {}
    }
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
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .testTag("content_error")
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = "Error",
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = errorMsg,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        FilledTonalButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}

