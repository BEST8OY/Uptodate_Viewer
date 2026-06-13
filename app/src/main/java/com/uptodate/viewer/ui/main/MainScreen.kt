package com.uptodate.viewer.ui.main

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.uptodate.viewer.ui.content.ContentScreen
import com.uptodate.viewer.ui.content.ContentViewModel
import com.uptodate.viewer.ui.favorites.FavoritesScreen
import com.uptodate.viewer.ui.favorites.FavoritesViewModel
import com.uptodate.viewer.ui.graphic.GraphicScreen
import com.uptodate.viewer.ui.graphic.GraphicViewModel
import com.uptodate.viewer.ui.history.HistoryScreen
import com.uptodate.viewer.ui.history.HistoryViewModel
import com.uptodate.viewer.ui.navigation.ContentRoute
import com.uptodate.viewer.ui.navigation.FavoritesRoute
import com.uptodate.viewer.ui.navigation.GraphicRoute
import com.uptodate.viewer.ui.navigation.HistoryRoute
import com.uptodate.viewer.ui.navigation.TOP_LEVEL_ROUTES
import com.uptodate.viewer.ui.navigation.SearchRoute
import com.uptodate.viewer.ui.navigation.TocRoute
import com.uptodate.viewer.ui.navigation.TopLevelBackStack
import com.uptodate.viewer.ui.search.SearchScreen
import com.uptodate.viewer.ui.search.SearchViewModel
import com.uptodate.viewer.ui.toc.TocScreen
import com.uptodate.viewer.ui.toc.TocViewModel

@Composable
fun MainScreen(
    hasDatabases: Boolean,
    onDatabaseConfigured: () -> Unit,
    onSelectDatabaseDir: () -> Unit = {}
) {
    if (!hasDatabases) {
        DatabaseSetupScreen(onSelectDatabaseDir = onSelectDatabaseDir)
        return
    }

    val topLevelBackStack = remember { TopLevelBackStack<Any>(TocRoute) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TOP_LEVEL_ROUTES.forEach { route ->
                    val isSelected = route == topLevelBackStack.topLevelKey
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { topLevelBackStack.addTopLevel(route) },
                        icon = { Icon(route.icon, contentDescription = route.label) },
                        label = { Text(route.label) }
                    )
                }
            }
        }
    ) { _ ->
        NavDisplay(
            backStack = topLevelBackStack.backStack,
            onBack = { topLevelBackStack.removeLast() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<TocRoute> {
                    val vm: TocViewModel = hiltViewModel()
                    TocScreen(
                        viewModel = vm,
                        onTopicClick = { topicId ->
                            topLevelBackStack.add(ContentRoute(topicId))
                        }
                    )
                }
                entry<SearchRoute> {
                    val vm: SearchViewModel = hiltViewModel()
                    SearchScreen(
                        viewModel = vm,
                        onTopicClick = { topicId ->
                            topLevelBackStack.add(ContentRoute(topicId))
                        }
                    )
                }
                entry<FavoritesRoute> {
                    val vm: FavoritesViewModel = hiltViewModel()
                    FavoritesScreen(
                        viewModel = vm,
                        onTopicClick = { topicId ->
                            topLevelBackStack.add(ContentRoute(topicId))
                        }
                    )
                }
                entry<HistoryRoute> {
                    val vm: HistoryViewModel = hiltViewModel()
                    HistoryScreen(
                        viewModel = vm,
                        onTopicClick = { topicId ->
                            topLevelBackStack.add(ContentRoute(topicId))
                        }
                    )
                }
                entry<ContentRoute> { key ->
                    val vm: ContentViewModel = hiltViewModel()
                    vm.initTopic(key.topicId)
                    ContentScreen(
                        topicId = key.topicId,
                        viewModel = vm,
                        onNavigateToGraphic = { graphicId ->
                            topLevelBackStack.add(GraphicRoute(graphicId))
                        },
                        onBack = { topLevelBackStack.removeLast() }
                    )
                }
                entry<GraphicRoute> { key ->
                    val vm: GraphicViewModel = hiltViewModel()
                    vm.initGraphic(key.graphicId)
                    GraphicScreen(
                        graphicId = key.graphicId,
                        viewModel = vm,
                        onClose = { topLevelBackStack.removeLast() }
                    )
                }
            },
        )
    }
}
