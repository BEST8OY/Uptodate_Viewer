package com.clinref.app.ui.navigation

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
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

sealed interface TopLevelRoute : NavKey {
    val title: String
    val icon: ImageVector
}

@Serializable
data object TocRoute : TopLevelRoute {
    override val title = "Contents"
    override val icon = Icons.Default.Home
}

@Serializable
data object HistoryRoute : TopLevelRoute {
    override val title = "History"
    override val icon = Icons.AutoMirrored.Filled.List
}

@Serializable
data object FavoritesRoute : TopLevelRoute {
    override val title = "Favorites"
    override val icon = Icons.Default.Favorite
}

@Serializable
data class ContentRoute(val topicId: String, val sectionId: String? = null) : NavKey

@Serializable
data object AiRoute : TopLevelRoute {
    override val title = "AI"
    override val icon = Icons.Default.Chat
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
    val isConfigured = databaseManager.isConfigured()

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
            current is ContentRoute || current is ChatRoute || current is AiSettingsRoute
        }
    }

    var selectedGraphicId by remember { mutableStateOf<String?>(null) }

    val activity = LocalContext.current as? Activity

    val entryProvider = entryProvider {
        entry<TocRoute> {
            TocScreen(
                onTopicSelected = { topicId ->
                    navigator.navigate(ContentRoute(topicId))
                },
                onGraphicSelected = { graphicId ->
                    selectedGraphicId = graphicId
                }
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
                currentRoute = navigationState.topLevelRoute
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
                currentRoute = navigationState.topLevelRoute
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
                }
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

        NavDisplay(
            entries = navigationState.toDecoratedEntries(entryProvider),
            onBack = {
                val currentStack = navigationState.backStacks[navigationState.topLevelRoute]
                val currentRoute = currentStack?.lastOrNull()
                if (currentRoute == navigationState.topLevelRoute) {
                    activity?.finish()
                } else {
                    navigator.goBack()
                }
            },
            transitionSpec = {
                slideInHorizontally(motionScheme.defaultSpatialSpec()) { it } togetherWith
                    slideOutHorizontally(motionScheme.defaultSpatialSpec()) { -it }
            },
            popTransitionSpec = {
                slideInHorizontally(motionScheme.defaultSpatialSpec()) { -it } togetherWith
                    slideOutHorizontally(motionScheme.defaultSpatialSpec()) { it }
            },
            predictivePopTransitionSpec = {
                slideInHorizontally(motionScheme.defaultSpatialSpec()) { -it } togetherWith
                    slideOutHorizontally(motionScheme.defaultSpatialSpec()) { it }
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = !isOnOverlayScreen,
            enter = fadeIn(motionScheme.defaultSpatialSpec()),
            exit = fadeOut(motionScheme.defaultSpatialSpec()),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            NavigationBar {
                topLevelRoutes.forEach { route ->
                    NavigationBarItem(
                        selected = route == navigationState.topLevelRoute,
                        onClick = { navigator.navigate(route) },
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
