package com.clinref.app.ui.navigation

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.clinref.app.data.DatabaseManager
import com.clinref.app.ui.content.ContentScreen
import com.clinref.app.ui.content.GraphicSheet
import com.clinref.app.ui.favorites.FavoritesScreen
import com.clinref.app.ui.history.HistoryScreen
import com.clinref.app.ui.setup.SetupScreen
import com.clinref.app.ui.toc.TocScreen
import com.clinref.app.ui.chat.ChatScreen
import com.clinref.app.ui.conversations.ConversationListScreen
import com.clinref.app.ui.settings.AiSettingsScreen
import com.clinref.app.ui.settings.SettingsViewModel
import kotlinx.serialization.Serializable

@Serializable
sealed interface TopLevelRoute : NavKey {
    val title: String
}

val TopLevelRoute.icon: ImageVector
    get() = when (this) {
        TocRoute -> Icons.Default.Home
        HistoryRoute -> Icons.AutoMirrored.Filled.List
        FavoritesRoute -> Icons.Default.Favorite
        AiRoute -> Icons.AutoMirrored.Filled.Chat
    }

@Serializable
data object TocRoute : TopLevelRoute {
    override val title = "Contents"
}

@Serializable
data object HistoryRoute : TopLevelRoute {
    override val title = "History"
}

@Serializable
data object FavoritesRoute : TopLevelRoute {
    override val title = "Favorites"
}

@Serializable
data class ContentRoute(val topicId: String, val sectionId: String? = null) : NavKey

@Serializable
data object AiRoute : TopLevelRoute {
    override val title = "AI"
}

@Serializable
data object AiSettingsRoute : NavKey

@Serializable
data object ConversationListRoute : NavKey

@Serializable
data class ChatRoute(val conversationId: String) : NavKey

val topLevelRoutes: List<TopLevelRoute> = listOf(
    TocRoute,
    HistoryRoute,
    FavoritesRoute,
    AiRoute
)

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
        topLevelRoutes = topLevelRoutes.toSet()
    )

    val navigator = remember { Navigator(navigationState) }

    val isOnOverlayScreen by remember {
        derivedStateOf {
            val currentBackStack = navigationState.backStacks[navigationState.topLevelRoute]
            val current = currentBackStack?.lastOrNull()
            current !is TopLevelRoute
        }
    }

    var selectedGraphicId by remember { mutableStateOf<String?>(null) }

    val activity = LocalContext.current as? Activity

    BackHandler(
        enabled = !isOnOverlayScreen && navigationState.topLevelRoute != navigationState.startRoute
    ) {
        navigator.navigate(navigationState.startRoute)
    }

    val entryProvider = entryProvider {
        entry<TocRoute> {
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
                    selectedGraphicId = graphicId
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
                onNavigateToContent = { topicId, sectionId ->
                    navigator.navigate(ContentRoute(topicId, sectionId))
                },
                onGraphicSelected = { graphicId ->
                    selectedGraphicId = graphicId
                },
                onBack = { navigator.goBack() }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val motionScheme = MaterialTheme.motionScheme
        val spatialSpec = motionScheme.defaultSpatialSpec<IntOffset>()
        val floatSpatialSpec = motionScheme.defaultSpatialSpec<Float>()
        val effectsSpec = motionScheme.defaultEffectsSpec<Float>()
        val fastEffectsSpec = motionScheme.fastEffectsSpec<Float>()
        val fastSpatialSpec = motionScheme.fastSpatialSpec<IntOffset>()

        val forwardTransition: AnimatedContentTransitionScope<androidx.navigation3.runtime.NavEntry<NavKey>>.() -> ContentTransform = {
            val initialKey = initialState.key
            val targetKey = targetState.key

            if (initialKey is TopLevelRoute && targetKey is TopLevelRoute) {
                val fromIndex = topLevelRoutes.indexOf(initialKey)
                val toIndex = topLevelRoutes.indexOf(targetKey)
                if (toIndex > fromIndex) {
                    (slideInHorizontally(spatialSpec) { (it * 0.20f).roundToInt() } +
                        fadeIn(effectsSpec)) togetherWith
                        (slideOutHorizontally(spatialSpec) { -(it * 0.20f).roundToInt() } +
                            fadeOut(fastEffectsSpec))
                } else if (toIndex < fromIndex) {
                    (slideInHorizontally(spatialSpec) { -(it * 0.20f).roundToInt() } +
                        fadeIn(effectsSpec)) togetherWith
                        (slideOutHorizontally(spatialSpec) { (it * 0.20f).roundToInt() } +
                            fadeOut(fastEffectsSpec))
                } else {
                    fadeIn(effectsSpec) togetherWith fadeOut(fastEffectsSpec)
                }
            } else if (targetKey is AiSettingsRoute) {
                (slideInVertically(spatialSpec) { (it * 0.15f).roundToInt() } +
                    scaleIn(floatSpatialSpec, initialScale = 0.96f) +
                    fadeIn(effectsSpec)) togetherWith
                    (scaleOut(floatSpatialSpec, targetScale = 0.96f) +
                        fadeOut(fastEffectsSpec))
            } else {
                (slideInHorizontally(spatialSpec) { it } +
                    fadeIn(effectsSpec, initialAlpha = 0.85f)) togetherWith
                    (slideOutHorizontally(spatialSpec) { -it / 3 } +
                        fadeOut(fastEffectsSpec, targetAlpha = 0.5f))
            }
        }

        val popTransition: AnimatedContentTransitionScope<androidx.navigation3.runtime.NavEntry<NavKey>>.() -> ContentTransform = {
            val initialKey = initialState.key
            val targetKey = targetState.key

            if (initialKey is AiSettingsRoute) {
                (scaleIn(floatSpatialSpec, initialScale = 0.96f) +
                    fadeIn(effectsSpec)) togetherWith
                    (slideOutVertically(spatialSpec) { (it * 0.15f).roundToInt() } +
                        scaleOut(floatSpatialSpec, targetScale = 0.96f) +
                        fadeOut(fastEffectsSpec))
            } else if (initialKey is TopLevelRoute && targetKey is TopLevelRoute) {
                val fromIndex = topLevelRoutes.indexOf(initialKey)
                val toIndex = topLevelRoutes.indexOf(targetKey)
                if (toIndex > fromIndex) {
                    (slideInHorizontally(spatialSpec) { (it * 0.20f).roundToInt() } +
                        fadeIn(effectsSpec)) togetherWith
                        (slideOutHorizontally(spatialSpec) { -(it * 0.20f).roundToInt() } +
                            fadeOut(fastEffectsSpec))
                } else if (toIndex < fromIndex) {
                    (slideInHorizontally(spatialSpec) { -(it * 0.20f).roundToInt() } +
                        fadeIn(effectsSpec)) togetherWith
                        (slideOutHorizontally(spatialSpec) { (it * 0.20f).roundToInt() } +
                            fadeOut(fastEffectsSpec))
                } else {
                    fadeIn(effectsSpec) togetherWith fadeOut(fastEffectsSpec)
                }
            } else {
                (slideInHorizontally(spatialSpec) { -it / 3 } +
                    fadeIn(effectsSpec, initialAlpha = 0.5f)) togetherWith
                    (slideOutHorizontally(spatialSpec) { it } +
                        fadeOut(fastEffectsSpec, targetAlpha = 0.85f))
            }
        }

        NavDisplay(
            entries = navigationState.toDecoratedEntries(entryProvider),
            onBack = {
                navigator.goBack()
            },
            transitionSpec = forwardTransition,
            popTransitionSpec = popTransition,
            predictivePopTransitionSpec = popTransition,
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = !isOnOverlayScreen,
            enter = fadeIn(effectsSpec) +
                slideInVertically(spatialSpec) { it },
            exit = fadeOut(fastEffectsSpec) +
                slideOutVertically(fastSpatialSpec) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            NavigationBar {
                topLevelRoutes.forEach { route ->
                    val isSelected = route == navigationState.topLevelRoute
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (isSelected) {
                                navigator.onReselect(route)
                            } else {
                                navigator.navigate(route)
                            }
                        },
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

        selectedGraphicId?.let { graphicId ->
            GraphicSheet(
                graphicId = graphicId,
                onDismiss = { selectedGraphicId = null }
            )
        }
    }
}

