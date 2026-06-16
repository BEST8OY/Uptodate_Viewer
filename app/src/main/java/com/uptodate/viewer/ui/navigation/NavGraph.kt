package com.uptodate.viewer.ui.navigation

import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.uptodate.viewer.data.DatabaseManager
import com.uptodate.viewer.ui.content.ContentScreen
import com.uptodate.viewer.ui.favorites.FavoritesScreen
import com.uptodate.viewer.ui.history.HistoryScreen
import com.uptodate.viewer.ui.search.SearchScreen
import com.uptodate.viewer.ui.setup.SetupScreen
import com.uptodate.viewer.ui.toc.TocScreen
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
data object SearchRoute : TopLevelRoute {
    override val title = "Search"
    override val icon = Icons.Default.Search
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
data object SetupRoute : NavKey

val topLevelRoutes: List<TopLevelRoute> = listOf(
    TocRoute,
    SearchRoute,
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
            onSetupComplete = {
                // Navigation will recompose when isConfigured changes
            }
        )
        return
    }

    val topLevelBackStack = remember { TopLevelBackStack<Any>(TocRoute) }
    val isOnContentScreen by remember {
        derivedStateOf { topLevelBackStack.backStack.lastOrNull() is ContentRoute }
    }

    Scaffold(
        bottomBar = {
            AnimatedVisibility(
                visible = !isOnContentScreen,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it })
            ) {
                NavigationBar {
                    topLevelRoutes.forEach { route ->
                        val isSelected = route == topLevelBackStack.topLevelKey
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                topLevelBackStack.addTopLevel(route)
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
        }
    ) { innerPadding ->
        NavDisplay(
            backStack = topLevelBackStack.backStack,
            onBack = { topLevelBackStack.removeLast() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<TocRoute> {
                    TocScreen(
                        onTopicSelected = { topicId ->
                            topLevelBackStack.add(ContentRoute(topicId))
                        }
                    )
                }
                entry<SearchRoute> {
                    SearchScreen(
                        onTopicSelected = { topicId ->
                            topLevelBackStack.add(ContentRoute(topicId))
                        }
                    )
                }
                entry<HistoryRoute> {
                    HistoryScreen(
                        onTopicSelected = { topicId ->
                            topLevelBackStack.add(ContentRoute(topicId))
                        }
                    )
                }
                entry<FavoritesRoute> {
                    FavoritesScreen(
                        onTopicSelected = { topicId ->
                            topLevelBackStack.add(ContentRoute(topicId))
                        }
                    )
                }
                entry<ContentRoute> { key ->
                    ContentScreen(
                        topicId = key.topicId,
                        onBack = { topLevelBackStack.removeLast() }
                    )
                }
            },
            transitionSpec = {
                slideInHorizontally(initialOffsetX = { it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { -it })
            },
            popTransitionSpec = {
                slideInHorizontally(initialOffsetX = { -it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { it })
            },
            predictivePopTransitionSpec = {
                slideInHorizontally(initialOffsetX = { -it }) togetherWith
                    slideOutHorizontally(targetOffsetX = { it })
            },
            modifier = Modifier
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        )
    }
}
