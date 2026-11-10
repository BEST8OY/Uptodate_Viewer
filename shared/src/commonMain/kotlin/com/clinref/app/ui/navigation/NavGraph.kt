package com.clinref.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import com.clinref.app.data.DatabaseManager
import com.clinref.app.ui.adaptive.AdaptiveTokens
import com.clinref.app.ui.adaptive.LocalBottomBarVisible
import com.clinref.app.ui.adaptive.LocalIsWideScreen
import com.clinref.app.ui.adaptive.LocalWindowWidthClass
import com.clinref.app.ui.adaptive.WindowWidthClass
import com.clinref.app.ui.adaptive.calculateWindowWidthClass
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
import kotlin.math.roundToInt

val TopLevelRoute.icon: ImageVector
    get() = when (this) {
        TocRoute -> Icons.Default.Home
        HistoryRoute -> Icons.AutoMirrored.Filled.List
        FavoritesRoute -> Icons.Default.Favorite
        AiRoute -> Icons.AutoMirrored.Filled.Chat
    }

@Immutable
private class NavMotion(
    val spatialSpec: FiniteAnimationSpec<IntOffset>,
    val floatSpatialSpec: FiniteAnimationSpec<Float>,
    val effectsSpec: FiniteAnimationSpec<Float>,
    val fastEffectsSpec: FiniteAnimationSpec<Float>,
    val exitSpatialSpec: FiniteAnimationSpec<IntOffset>,
    val exitFloatSpatialSpec: FiniteAnimationSpec<Float>
)

@Composable
private fun rememberNavMotion(): NavMotion {
    val motionScheme = MaterialTheme.motionScheme
    return remember(motionScheme) {
        NavMotion(
            spatialSpec = motionScheme.defaultSpatialSpec(),
            floatSpatialSpec = motionScheme.defaultSpatialSpec(),
            effectsSpec = motionScheme.defaultEffectsSpec(),
            fastEffectsSpec = motionScheme.fastEffectsSpec(),
            exitSpatialSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            exitFloatSpatialSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }
}

private fun Scene<NavKey>.navKey(): NavKey? =
    (key as? NavKey) ?: entries.lastOrNull()?.contentKey as? NavKey

@Composable
fun NavGraph(
    databaseManager: DatabaseManager
) {
    val isConfigured by databaseManager.isConfiguredFlow.collectAsStateWithLifecycle()

    if (!isConfigured) {
        SetupScreen(
            onSetupComplete = {}
        )
        return
    }

    val navigationState = rememberNavigationState(
        startRoute = TocRoute,
        topLevelRoutes = topLevelRoutesSet
    )

    val navigator = remember(navigationState) { Navigator(navigationState) }
    var selectedGraphicId by rememberSaveable { mutableStateOf<String?>(null) }

    val isOnSubRoute by remember {
        derivedStateOf {
            val currentBackStack = navigationState.backStacks[navigationState.topLevelRoute]
            val current = currentBackStack?.lastOrNull()
            current !is TopLevelRoute
        }
    }

    BackHandler(
        enabled = !isOnSubRoute && navigationState.topLevelRoute != navigationState.startRoute
    ) {
        navigator.navigate(navigationState.startRoute)
    }

    val entryProvider: (NavKey) -> NavEntry<NavKey> = remember(navigator, navigationState) {
        entryProvider {
            entry<TocRoute> {
                TocScreen(
                    onTopicSelected = { topicId ->
                        navigator.navigate(ContentRoute(topicId))
                    },
                    onGraphicSelected = { graphicId ->
                        selectedGraphicId = graphicId.removePrefix("Graphic-")
                    },
                    reselectEvents = navigator.reselectEvents
                )
            }
            entry<HistoryRoute> {
                HistoryScreen(
                    onTopicSelected = { topicId ->
                        if (topicId.startsWith("Graphic-")) {
                            selectedGraphicId = topicId.removePrefix("Graphic-")
                        } else {
                            navigator.navigate(ContentRoute(topicId))
                        }
                    },
                    currentRoute = navigationState.topLevelRoute,
                    reselectEvents = navigator.reselectEvents
                )
            }
            entry<FavoritesRoute> {
                FavoritesScreen(
                    onTopicSelected = { topicId ->
                        if (topicId.startsWith("Graphic-")) {
                            selectedGraphicId = topicId.removePrefix("Graphic-")
                        } else {
                            navigator.navigate(ContentRoute(topicId))
                        }
                    },
                    currentRoute = navigationState.topLevelRoute,
                    reselectEvents = navigator.reselectEvents
                )
            }
            entry<ContentRoute> { key ->
                ContentScreen(
                    topicId = key.topicId,
                    sectionId = key.sectionId,
                    onBack = { navigator.goBack() },
                    onHome = { navigator.navigate(TocRoute) },
                    onGraphicSelected = { graphicId ->
                        selectedGraphicId = graphicId.removePrefix("Graphic-")
                    }
                )
            }
            entry<AiRoute> {
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
            entry<AiSettingsRoute> {
                AiSettingsScreen(
                    onBack = { navigator.goBack() }
                )
            }
            entry<ChatRoute> { key ->
                ChatScreen(
                    conversationId = key.conversationId,
                    onNavigateToHome = { navigator.navigate(TocRoute) },
                    onBack = { navigator.goBack() },
                    onNavigateToSettings = { navigator.navigate(AiSettingsRoute) }
                )
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val windowWidthClass = calculateWindowWidthClass(maxWidth)
        val isWideScreen = maxWidth >= AdaptiveTokens.BreakpointMedium
        val isExpandedScreen = windowWidthClass == WindowWidthClass.EXPANDED
        val bottomBarVisible = !isOnSubRoute && !isWideScreen
        val navRailVisible = isWideScreen && (isExpandedScreen || !isOnSubRoute)
        val motion = rememberNavMotion()

        val forwardTransition: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
            val initialKey = initialState.navKey()
            val targetKey = targetState.navKey()

            when {
                initialKey is TopLevelRoute && targetKey is TopLevelRoute ->
                    peerLateralTransition(initialKey, targetKey, motion)
                targetKey is AiSettingsRoute ->
                    auxiliaryModalLiftTransition(motion)
                else ->
                    hierarchicalForwardTransition(motion)
            }
        }

        val popTransition: AnimatedContentTransitionScope<Scene<NavKey>>.() -> ContentTransform = {
            val initialKey = initialState.navKey()
            val targetKey = targetState.navKey()

            when {
                initialKey is AiSettingsRoute ->
                    auxiliaryModalRecedeTransition(motion)
                initialKey is TopLevelRoute && targetKey is TopLevelRoute ->
                    peerLateralTransition(initialKey, targetKey, motion)
                else ->
                    hierarchicalPopTransition(motion)
            }
        }

        val predictivePopTransition: AnimatedContentTransitionScope<Scene<NavKey>>.(Int) -> ContentTransform = { _ ->
            val initialKey = initialState.navKey()
            val targetKey = targetState.navKey()

            when {
                initialKey is AiSettingsRoute ->
                    auxiliaryModalRecedeTransition(motion)
                initialKey is TopLevelRoute && targetKey is TopLevelRoute ->
                    peerLateralTransition(initialKey, targetKey, motion)
                else ->
                    hierarchicalPopTransition(
                        motion,
                        initialScale = 0.94f,
                        targetScale = 0.90f
                    )
            }
        }

        CompositionLocalProvider(
            LocalBottomBarVisible provides bottomBarVisible,
            LocalIsWideScreen provides isWideScreen,
            LocalWindowWidthClass provides windowWidthClass
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                ClinRefNavRail(
                    visible = navRailVisible,
                    currentRoute = navigationState.topLevelRoute,
                    onNavigate = { navigator.navigate(it) },
                    motion = motion
                )

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                        motion = motion
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

private fun peerLateralTransition(
    from: TopLevelRoute,
    to: TopLevelRoute,
    motion: NavMotion
): ContentTransform {
    val fromIndex = topLevelRoutes.indexOf(from)
    val toIndex = topLevelRoutes.indexOf(to)
    return when {
        toIndex > fromIndex -> {
            (slideInHorizontally(motion.spatialSpec) { (it * 0.20f).roundToInt() } +
                fadeIn(motion.effectsSpec)) togetherWith
                (slideOutHorizontally(motion.spatialSpec) { -(it * 0.20f).roundToInt() } +
                    fadeOut(motion.fastEffectsSpec))
        }
        toIndex < fromIndex -> {
            (slideInHorizontally(motion.spatialSpec) { -(it * 0.20f).roundToInt() } +
                fadeIn(motion.effectsSpec)) togetherWith
                (slideOutHorizontally(motion.spatialSpec) { (it * 0.20f).roundToInt() } +
                    fadeOut(motion.fastEffectsSpec))
        }
        else -> fadeIn(motion.effectsSpec) togetherWith fadeOut(motion.fastEffectsSpec)
    }
}

private fun auxiliaryModalLiftTransition(motion: NavMotion): ContentTransform =
    (slideInVertically(motion.spatialSpec) { (it * 0.12f).roundToInt() } +
        scaleIn(motion.floatSpatialSpec, initialScale = 0.94f) +
        fadeIn(motion.effectsSpec)) togetherWith
        (scaleOut(motion.floatSpatialSpec, targetScale = 0.96f) +
            fadeOut(motion.fastEffectsSpec, targetAlpha = 0f))

private fun auxiliaryModalRecedeTransition(motion: NavMotion): ContentTransform =
    (scaleIn(motion.floatSpatialSpec, initialScale = 0.96f) +
        fadeIn(motion.effectsSpec)) togetherWith
        (slideOutVertically(motion.exitSpatialSpec) { (it * 0.12f).roundToInt() } +
            scaleOut(motion.exitFloatSpatialSpec, targetScale = 0.94f) +
            fadeOut(motion.fastEffectsSpec, targetAlpha = 0f))

private fun hierarchicalForwardTransition(motion: NavMotion): ContentTransform =
    (slideInHorizontally(motion.spatialSpec) { it } +
        fadeIn(motion.effectsSpec, initialAlpha = 0f)) togetherWith
        (slideOutHorizontally(motion.spatialSpec) { -it / 4 } +
            scaleOut(motion.floatSpatialSpec, targetScale = 0.96f) +
            fadeOut(motion.fastEffectsSpec, targetAlpha = 0f))

private fun hierarchicalPopTransition(
    motion: NavMotion,
    initialScale: Float = 0.96f,
    targetScale: Float = 0.96f
): ContentTransform =
    (slideInHorizontally(motion.spatialSpec) { -it / 4 } +
        scaleIn(motion.floatSpatialSpec, initialScale = initialScale) +
        fadeIn(motion.effectsSpec, initialAlpha = 0f)) togetherWith
        (slideOutHorizontally(motion.exitSpatialSpec) { it } +
            scaleOut(motion.exitFloatSpatialSpec, targetScale = targetScale) +
            fadeOut(motion.fastEffectsSpec, targetAlpha = 0f))

@Composable
private fun ClinRefNavRail(
    visible: Boolean,
    currentRoute: NavKey,
    onNavigate: (TopLevelRoute) -> Unit,
    motion: NavMotion,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.effectsSpec) +
            slideInHorizontally(motion.spatialSpec) { -it },
        exit = fadeOut(motion.fastEffectsSpec) +
            slideOutHorizontally(motion.exitSpatialSpec) { -it },
        modifier = modifier
    ) {
        NavigationRail {
            Spacer(modifier = Modifier.height(16.dp))
            topLevelRoutes.forEach { route ->
                val isSelected = route == currentRoute
                NavigationRailItem(
                    selected = isSelected,
                    onClick = { onNavigate(route) },
                    icon = {
                        Icon(
                            imageVector = route.icon,
                            contentDescription = route.title
                        )
                    },
                    label = { Text(route.title) }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.ClinRefBottomBar(
    visible: Boolean,
    currentRoute: NavKey,
    onNavigate: (TopLevelRoute) -> Unit,
    motion: NavMotion,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.effectsSpec) +
            slideInVertically(motion.spatialSpec) { it },
        exit = fadeOut(motion.fastEffectsSpec) +
            slideOutVertically(motion.exitSpatialSpec) { it },
        modifier = modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        NavigationBar {
            topLevelRoutes.forEach { route ->
                val isSelected = route == currentRoute
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onNavigate(route) },
                    icon = {
                        Icon(
                            imageVector = route.icon,
                            contentDescription = route.title
                        )
                    },
                    label = { Text(route.title) }
                )
            }
        }
    }
}

