package com.clinref.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.metadata
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import androidx.navigation3.scene.Scene
import com.clinref.app.data.DatabaseManager
import com.clinref.app.ui.adaptive.LocalBottomBarPadding
import com.clinref.app.ui.adaptive.LocalWindowAdaptiveInfo
import com.clinref.app.ui.adaptive.WindowWidthClass
import com.clinref.app.ui.adaptive.calculateWindowAdaptiveInfo
import com.clinref.app.ui.chat.ChatScreen
import com.clinref.app.ui.common.BackHandler
import com.clinref.app.ui.content.ContentScreen
import com.clinref.app.ui.content.GraphicSheet
import com.clinref.app.ui.conversations.ConversationListScreen
import com.clinref.app.ui.favorites.FavoritesScreen
import com.clinref.app.ui.history.HistoryScreen
import com.clinref.app.ui.settings.AiSettingsScreen
import com.clinref.app.ui.setup.SetupScreen
import com.clinref.app.ui.toc.TocScreen

@Composable
fun NavGraph(
    databaseManager: DatabaseManager
) {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val containerWidthDp = with(density) { windowInfo.containerSize.width.toDp() }
    val containerHeightDp = with(density) { windowInfo.containerSize.height.toDp() }
    val adaptiveInfo = calculateWindowAdaptiveInfo(containerWidthDp, containerHeightDp)

    CompositionLocalProvider(
        LocalWindowAdaptiveInfo provides adaptiveInfo
    ) {
        val isConfigured by databaseManager.isConfiguredFlow.collectAsStateWithLifecycle()

        if (!isConfigured) {
            SetupScreen()
            return@CompositionLocalProvider
        }

        val navigationState = rememberNavigationState(
            startRoute = TocRoute,
            topLevelRoutes = topLevelRoutesSet
        )

    val navigator = remember(navigationState) { Navigator(navigationState) }
    var selectedGraphicId by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(
        enabled = !navigationState.isOnSubRoute && navigationState.topLevelRoute != navigationState.startRoute
    ) {
        navigator.goBack()
    }

    val entryProvider: (NavKey) -> NavEntry<NavKey> = remember(navigator, navigationState) {
        entryProvider {
            entry<TocRoute>(metadata = metadata { put(RouteKeyMetadata, TocRoute) }) {
                TocScreen(
                    onTopicSelected = { topicId ->
                        navigator.navigate(ContentRoute(topicId))
                    },
                    onGraphicSelected = { graphicId ->
                        selectedGraphicId = graphicId
                    },
                    reselectEvents = navigator.reselectEvents
                )
            }
            entry<HistoryRoute>(metadata = metadata { put(RouteKeyMetadata, HistoryRoute) }) {
                HistoryScreen(
                    onTopicSelected = { topicId ->
                        navigator.navigate(ContentRoute(topicId))
                    },
                    onGraphicSelected = { graphicId ->
                        selectedGraphicId = graphicId
                    },
                    reselectEvents = navigator.reselectEvents
                )
            }
            entry<FavoritesRoute>(metadata = metadata { put(RouteKeyMetadata, FavoritesRoute) }) {
                FavoritesScreen(
                    onTopicSelected = { topicId ->
                        navigator.navigate(ContentRoute(topicId))
                    },
                    onGraphicSelected = { graphicId ->
                        selectedGraphicId = graphicId
                    },
                    reselectEvents = navigator.reselectEvents
                )
            }
            entry<ContentRoute>(metadata = { key -> metadata { put(RouteKeyMetadata, key) } }) { key ->
                ContentScreen(
                    topicId = key.topicId,
                    sectionId = key.sectionId,
                    onBack = { navigator.popSubRoute(key) },
                    onHome = { navigator.navigateToHome() },
                    onGraphicSelected = { graphicId ->
                        selectedGraphicId = graphicId
                    }
                )
            }
            entry<AiRoute>(metadata = metadata { put(RouteKeyMetadata, AiRoute) }) {
                ConversationListScreen(
                    onConversationSelected = { conversationId ->
                        navigator.navigate(ChatRoute(conversationId))
                    },
                    onOpenSettings = {
                        navigator.navigate(AiSettingsRoute)
                    },
                    reselectEvents = navigator.reselectEvents
                )
            }
            entry<AiSettingsRoute>(metadata = metadata { put(RouteKeyMetadata, AiSettingsRoute) }) {
                AiSettingsScreen(
                    onBack = { navigator.popSubRoute(AiSettingsRoute) }
                )
            }
            entry<ChatRoute>(metadata = { key -> metadata { put(RouteKeyMetadata, key) } }) { key ->
                ChatScreen(
                    conversationId = key.conversationId,
                    onNavigateToHome = { navigator.navigateToHome() },
                    onBack = { navigator.popSubRoute(key) },
                    onNavigateToSettings = { navigator.navigate(AiSettingsRoute) }
                )
            }
        }
    }

    val windowWidthClass = adaptiveInfo.widthClass
    val isWideScreen = adaptiveInfo.useNavigationRail
    val isExpandedScreen = windowWidthClass == WindowWidthClass.EXPANDED
    val bottomBarVisible = !navigationState.isOnSubRoute && !isWideScreen
    val navRailVisible = isWideScreen && (isExpandedScreen || !navigationState.isOnSubRoute)
    val motion = rememberNavMotion()

    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    val bottomBarHeightDp = remember(bottomBarHeightPx, density) {
        with(density) { bottomBarHeightPx.toDp() }
    }
    val navBarBottom = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Bottom)
        .exclude(WindowInsets.ime)
        .asPaddingValues()
        .calculateBottomPadding()
    val effectiveBottomBarHeightDp = if (!adaptiveInfo.useNavigationRail) {
        if (bottomBarHeightDp > 0.dp) bottomBarHeightDp else (80.dp + navBarBottom)
    } else {
        0.dp
    }

    val forwardTransition: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = remember(motion) {
        {
            val initialKey = initialState.routeKey()
            val targetKey = targetState.routeKey()

            when {
                initialKey is TopLevelRoute && targetKey is TopLevelRoute ->
                    peerLateralTransition(initialKey, targetKey, motion)
                targetKey is AiSettingsRoute ->
                    auxiliaryModalLiftTransition(motion)
                else ->
                    hierarchicalForwardTransition(motion)
            }
        }
    }

    val popTransition: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = remember(motion) {
        {
            val initialKey = initialState.routeKey()

            when {
                initialKey is AiSettingsRoute ->
                    auxiliaryModalRecedeTransition(motion)
                else ->
                    hierarchicalPopTransition(motion)
            }
        }
    }

    val predictivePopTransition: AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform = remember(motion) {
        { swipeEdge ->
            val initialKey = initialState.routeKey()

            when {
                initialKey is AiSettingsRoute ->
                    auxiliaryModalRecedeTransition(motion)
                else -> {
                    val slideSign = if (swipeEdge == NavigationEvent.EDGE_RIGHT) -1 else 1
                    hierarchicalPopTransition(
                        motion = motion,
                        initialScale = 0.94f,
                        targetScale = 0.90f,
                        slideSign = slideSign
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && selectedGraphicId == null) {
                    val isModifier = (event.isCtrlPressed || event.isMetaPressed) &&
                        !event.isAltPressed && !event.isShiftPressed

                    when {
                        isModifier && event.key == Key.One -> {
                            navigator.navigate(TocRoute)
                            true
                        }
                        isModifier && event.key == Key.Two -> {
                            navigator.navigate(HistoryRoute)
                            true
                        }
                        isModifier && event.key == Key.Three -> {
                            navigator.navigate(FavoritesRoute)
                            true
                        }
                        isModifier && event.key == Key.Four -> {
                            navigator.navigate(AiRoute)
                            true
                        }
                        else -> false
                    }
                } else false
            }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    if (selectedGraphicId != null) {
                        selectedGraphicId = null
                        true
                    } else if (navigationState.isOnSubRoute || navigationState.topLevelRoute != navigationState.startRoute) {
                        navigator.goBack()
                        true
                    } else false
                } else false
            }
    ) {
        CompositionLocalProvider(
            LocalBottomBarPadding provides effectiveBottomBarHeightDp
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                ClinRefNavRail(
                    visible = navRailVisible,
                    currentRoute = navigationState.topLevelRoute,
                    onNavigate = { navigator.navigate(it) },
                    motion = motion
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .consumeWindowInsets(
                            if (navRailVisible) WindowInsets.safeDrawing.only(WindowInsetsSides.Start)
                            else WindowInsets(0, 0, 0, 0)
                        )
                ) {
                    NavDisplay(
                        entries = navigationState.toDecoratedEntries(entryProvider),
                        onBack = {
                            navigator.goBack()
                        },
                        transitionSpec = forwardTransition,
                        popTransitionSpec = popTransition,
                        predictivePopTransitionSpec = predictivePopTransition,
                        modifier = Modifier.fillMaxSize()
                    )

                    ClinRefBottomBar(
                        visible = bottomBarVisible,
                        currentRoute = navigationState.topLevelRoute,
                        onNavigate = { navigator.navigate(it) },
                        motion = motion,
                        onHeightChanged = { bottomBarHeightPx = it }
                    )
                }
            }
        }

        selectedGraphicId?.let { graphicId ->
            GraphicSheet(
                graphicId = graphicId,
                onDismiss = { selectedGraphicId = null }
            )
        }
    }
}
}
