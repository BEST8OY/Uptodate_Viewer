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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.clinref.app.data.DatabaseManager
import com.clinref.app.ui.content.ContentScreen
import com.clinref.app.ui.content.GraphicSheet
import com.clinref.app.ui.favorites.FavoritesScreen
import com.clinref.app.ui.history.HistoryScreen
import com.clinref.app.ui.search.SearchScreen
import com.clinref.app.ui.setup.SetupScreen
import com.clinref.app.ui.toc.TocScreen
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
data class ContentRoute(val topicId: String) : NavKey

@Serializable
data object SearchRoute : NavKey

val topLevelRoutes: List<TopLevelRoute> = listOf(
    TocRoute,
    HistoryRoute,
    FavoritesRoute
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
            current is ContentRoute || current is SearchRoute
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
                },
                onSearchClick = { navigator.navigate(SearchRoute) }
            )
        }
        entry<SearchRoute> {
            SearchScreen(
                onTopicSelected = { topicId ->
                    navigator.navigate(ContentRoute(topicId))
                },
                onGraphicSelected = { graphicId ->
                    selectedGraphicId = graphicId
                },
                onBack = { navigator.goBack() }
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
                }
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
                }
            )
        }
        entry<ContentRoute> { key ->
            ContentScreen(
                topicId = key.topicId,
                onBack = { navigator.goBack() },
                onHome = { navigator.navigate(TocRoute) },
                onGraphicSelected = { graphicId ->
                    selectedGraphicId = graphicId
                }
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
                if (currentRoute == navigationState.topLevelRoute && navigationState.topLevelRoute == navigationState.startRoute) {
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
            BottomAppBar(
                actions = {
                    topLevelRoutes.forEach { route ->
                        NavBarItem(
                            route = route,
                            isSelected = route == navigationState.topLevelRoute,
                            onClick = { navigator.navigate(route) }
                        )
                    }
                }
            )
        }

        selectedGraphicId?.let { graphicId ->
            GraphicSheet(
                graphicId = graphicId,
                onDismiss = { selectedGraphicId = null }
            )
        }
    }
}

@Composable
private fun RowScope.NavBarItem(
    route: TopLevelRoute,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.weight(1f)
    ) {
        Icon(
            imageVector = route.icon,
            contentDescription = route.title,
            tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
