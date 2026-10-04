package com.clinref.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface TopLevelRoute : NavKey {
    val title: String
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
data object AiRoute : TopLevelRoute {
    override val title = "AI"
}

@Serializable
data class ContentRoute(val topicId: String, val sectionId: String? = null) : NavKey

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
