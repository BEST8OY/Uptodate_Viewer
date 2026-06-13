package com.uptodate.viewer.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

sealed interface TopLevelRoute : NavKey {
    val icon: ImageVector
    val label: String
}

@Serializable
data object TocRoute : TopLevelRoute {
    override val icon = Icons.AutoMirrored.Filled.List
    override val label = "Contents"
}

@Serializable
data object SearchRoute : TopLevelRoute {
    override val icon = Icons.Default.Search
    override val label = "Search"
}

@Serializable
data object FavoritesRoute : TopLevelRoute {
    override val icon = Icons.Default.Star
    override val label = "Favorites"
}

@Serializable
data object HistoryRoute : TopLevelRoute {
    override val icon = Icons.Default.History
    override val label = "History"
}

@Serializable
data class ContentRoute(val topicId: String) : NavKey

@Serializable
data class GraphicRoute(val graphicId: String) : NavKey

val TOP_LEVEL_ROUTES: List<TopLevelRoute> = listOf(
    TocRoute, SearchRoute, FavoritesRoute, HistoryRoute
)
