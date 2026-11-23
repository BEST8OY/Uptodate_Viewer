package com.clinref.app.ui.navigation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.navigation3.runtime.NavKey
import com.clinref.app.ui.chat.ChatOverlay
import com.clinref.app.ui.chat.ChatOverlaySaver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit-level robustness tests for Navigation 3 state restoration ([Saver]s
 * and polymorphic `navSavedStateConfig` serialization): valid roundtrips plus
 * corrupted, empty, and malformed saved data falling back safely.
 */

private val DummySaverScope = object : SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}

class NavSaverRobustnessTest {

    @Test
    fun `production topLevelRouteSaver restores valid routes and safely falls back on corrupted or empty data`() {
        val startRoute: NavKey = TocRoute
        val saver = topLevelRouteSaver(startRoute = startRoute, topLevelRoutes = topLevelRoutesSet)

        // 1. All valid TopLevelRoutes save stable routeId & restore correctly
        topLevelRoutes.forEach { route ->
            val state = mutableStateOf<NavKey>(route)
            val saved = with(saver) { DummySaverScope.save(state) }
            assertNotNull(saved, "Saved state must not be null")
            assertEquals(route.routeId, saved, "Saved state must be stable routeId")

            val restoredState = saver.restore(saved)
            assertNotNull(restoredState)
            assertEquals(route, restoredState.value, "Restored route must match original")
        }

        // 2. Adversarial: Corrupted / unknown route string falls back to startRoute
        val corruptedSaved = "NonExistentRoute_123"
        val restoredFromCorrupted = saver.restore(corruptedSaved)
        assertEquals(startRoute, restoredFromCorrupted?.value)

        // 3. Adversarial: Empty string falls back to startRoute
        val emptySaved = ""
        val restoredFromEmpty = saver.restore(emptySaved)
        assertEquals(startRoute, restoredFromEmpty?.value)

        // 4. Adversarial: Arbitrary sub-route name falls back to startRoute
        val subRouteSaved = "ContentRoute(topicId=123)"
        val restoredFromSubRoute = saver.restore(subRouteSaved)
        assertEquals(startRoute, restoredFromSubRoute?.value)

        // 5. Backward compatibility: Saved by human-readable title still restores
        topLevelRoutes.forEach { route ->
            val restoredFromTitle = saver.restore(route.title)
            assertEquals(route, restoredFromTitle?.value)
        }

        // 6. Backward compatibility: Saved by class simpleName still restores
        topLevelRoutes.forEach { route ->
            val restoredFromSimpleName = saver.restore(route::class.simpleName ?: "")
            assertEquals(route, restoredFromSimpleName?.value)
        }

        // 7. Parameterized factory honors custom startRoute on fallback
        val customStartRoute: NavKey = HistoryRoute
        val customSaver = topLevelRouteSaver(startRoute = customStartRoute, topLevelRoutes = topLevelRoutesSet)
        val customFallback = customSaver.restore("CorruptedData")
        assertEquals(customStartRoute, customFallback?.value)
    }

    @Test
    fun `production ChatOverlaySaver contract handles articles, graphics, null values, and corrupted data`() {
        // 1. Article request with section
        val req1: ChatOverlay = ChatOverlay.Article(topicId = "topic-asthma", sectionId = "sec-treatment")
        val saved1 = with(ChatOverlaySaver) { DummySaverScope.save(req1) }
        assertEquals(listOf("article", "topic-asthma", "sec-treatment"), saved1)
        val restored1 = ChatOverlaySaver.restore(saved1!!)
        assertEquals(req1, restored1)

        // 2. Article request without section
        val req2: ChatOverlay = ChatOverlay.Article(topicId = "topic-copd", sectionId = null)
        val saved2 = with(ChatOverlaySaver) { DummySaverScope.save(req2) }
        assertEquals(listOf("article", "topic-copd", null), saved2)
        val restored2 = ChatOverlaySaver.restore(saved2!!)
        assertEquals(req2, restored2)

        // 3. Graphic request
        val req3: ChatOverlay = ChatOverlay.Graphic(graphicId = "graphic-12345")
        val saved3 = with(ChatOverlaySaver) { DummySaverScope.save(req3) }
        assertEquals(listOf("graphic", "graphic-12345", null), saved3)
        val restored3 = ChatOverlaySaver.restore(saved3!!)
        assertEquals(req3, restored3)

        // 4. Adversarial: Empty list yields null
        val restoredFromEmptyList = ChatOverlaySaver.restore(emptyList<String?>())
        assertNull(restoredFromEmptyList, "Restoring from empty list must return null")

        // 5. Adversarial: Unknown tag yields null
        val restoredFromUnknown = ChatOverlaySaver.restore(listOf("unknown", "id"))
        assertNull(restoredFromUnknown, "Restoring from unknown tag must return null")

        // 6. Adversarial: First item null yields null
        val restoredFromNullTag = ChatOverlaySaver.restore(listOf(null, "sec-1"))
        assertNull(restoredFromNullTag, "Restoring from null tag must return null")

        // 7. Adversarial: null request (sheet closed) — returns null
        val savedNull = with(ChatOverlaySaver) { DummySaverScope.save(null) }
        assertNull(savedNull, "Null request (closed sheet) must save as null")
    }

    @Test
    fun `navSavedStateConfig polymorphic serializer roundtrips all active navigation routes`() {
        val json = Json {
            serializersModule = navSavedStateConfig.serializersModule
        }

        val testRoutes: List<NavKey> = listOf(
            TocRoute,
            HistoryRoute,
            FavoritesRoute,
            AiRoute,
            ContentRoute(topicId = "t-100", sectionId = "s-200"),
            ContentRoute(topicId = "t-101", sectionId = null),
            AiSettingsRoute,
            ChatRoute(conversationId = "conv-xyz-999")
        )

        testRoutes.forEach { route ->
            val serialized = json.encodeToString<NavKey>(route)
            val deserialized = json.decodeFromString<NavKey>(serialized)
            assertEquals(route, deserialized, "Roundtrip failed for $route")
        }
    }
}
