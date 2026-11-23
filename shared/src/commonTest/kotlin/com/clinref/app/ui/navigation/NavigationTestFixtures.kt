package com.clinref.app.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/**
 * Shared test fixture for Navigation 3 state and navigator tests.
 */
fun createTestNavigator(
    startRoute: NavKey = TocRoute,
    topLevelRoutes: List<TopLevelRoute> = listOf(TocRoute, HistoryRoute, FavoritesRoute, AiRoute)
): Navigator {
    val topLevelState = mutableStateOf(startRoute)
    val backStacks = topLevelRoutes.associate { route ->
        route as NavKey to NavBackStack<NavKey>(route)
    }
    val state = NavigationState(
        startRoute = startRoute,
        topLevelRoute = topLevelState,
        backStacks = backStacks
    )
    return Navigator(state)
}
