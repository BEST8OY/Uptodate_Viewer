package com.clinref.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

@Serializable
sealed interface TopLevelRoute : NavKey {
    val routeId: String
}

@Serializable
data object TocRoute : TopLevelRoute {
    override val routeId = "toc"
}

@Serializable
data object HistoryRoute : TopLevelRoute {
    override val routeId = "history"
}

@Serializable
data object FavoritesRoute : TopLevelRoute {
    override val routeId = "favorites"
}

@Serializable
data object AiRoute : TopLevelRoute {
    override val routeId = "ai"
}

@Serializable
data class ContentRoute(val topicId: String, val sectionId: String? = null) : NavKey

@Serializable
data object AiSettingsRoute : NavKey

@Serializable
data class ChatRoute(val conversationId: String) : NavKey

val topLevelRoutes: List<TopLevelRoute> = listOf(
    TocRoute,
    HistoryRoute,
    FavoritesRoute,
    AiRoute
)

val topLevelRoutesSet: Set<TopLevelRoute> = topLevelRoutes.toSet()

/**
 * Polymorphic serializer configuration for all Navigation 3 keys.
 * Co-located with route definitions to ensure newly added keys are registered.
 */
val navSavedStateConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(TocRoute::class, TocRoute.serializer())
            subclass(HistoryRoute::class, HistoryRoute.serializer())
            subclass(FavoritesRoute::class, FavoritesRoute.serializer())
            subclass(AiRoute::class, AiRoute.serializer())
            subclass(ContentRoute::class, ContentRoute.serializer())
            subclass(AiSettingsRoute::class, AiSettingsRoute.serializer())
            subclass(ChatRoute::class, ChatRoute.serializer())
        }
    }
}
