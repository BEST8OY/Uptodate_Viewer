package com.clinref.app.ui.navigation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.listSaver
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit-level robustness tests for Navigation 3 state restoration ([Saver]s
 * and polymorphic `navSavedStateConfig` serialization): valid roundtrips plus
 * corrupted, empty, and malformed saved data falling back safely.
 *
 * Consolidated from the former per-milestone adversarial-remedy file to sit
 * beside its canonical owner `NavigationStateTest`.
 */

// Standalone request model mirroring ChatScreen's ArticleSheetRequest for saver testing
data class TestArticleSheetRequest(
    val topicId: String,
    val sectionId: String? = null
)

private val DummySaverScope = object : SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}

class NavSaverRobustnessTest {

    @Test
    fun `topLevelRoute Saver restores valid routes and safely falls back on corrupted or empty data`() {
        val startRoute: NavKey = TocRoute
        val routesSet = topLevelRoutes.toSet()

        val saver = Saver<MutableState<NavKey>, Any>(
            save = { state -> (state.value as? TopLevelRoute)?.title ?: state.value.toString() },
            restore = { savedName ->
                val restored = routesSet.firstOrNull {
                    it.title == savedName || it.toString() == savedName
                } ?: startRoute
                mutableStateOf(restored)
            }
        )

        // 1. All valid TopLevelRoutes save & restore correctly
        topLevelRoutes.forEach { route ->
            val state = mutableStateOf<NavKey>(route)
            val saved = with(saver) { DummySaverScope.save(state) }
            assertNotNull("Saved state must not be null", saved)

            val restoredState = saver.restore(saved!!)
            assertNotNull(restoredState)
            assertEquals("Restored route must match original", route, restoredState?.value)
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
    }

    @Test
    fun `ArticleSheetRequestSaver contract handles null sections, empty topics, and malformed lists`() {
        val articleSheetRequestSaver = listSaver<TestArticleSheetRequest, String?>(
            save = { listOf(it.topicId, it.sectionId) },
            restore = { list ->
                val topic = list.getOrNull(0) ?: return@listSaver null
                TestArticleSheetRequest(topicId = topic, sectionId = list.getOrNull(1))
            }
        )

        // 1. Full request with section
        val req1 = TestArticleSheetRequest(topicId = "topic-asthma", sectionId = "sec-treatment")
        val saved1 = with(articleSheetRequestSaver) { DummySaverScope.save(req1) }
        assertEquals(listOf("topic-asthma", "sec-treatment"), saved1)
        val restored1 = articleSheetRequestSaver.restore(saved1!!)
        assertEquals(req1, restored1)

        // 2. Request without section
        val req2 = TestArticleSheetRequest(topicId = "topic-copd", sectionId = null)
        val saved2 = with(articleSheetRequestSaver) { DummySaverScope.save(req2) }
        assertEquals(listOf("topic-copd", null), saved2)
        val restored2 = articleSheetRequestSaver.restore(saved2!!)
        assertEquals(req2, restored2)

        // 3. Adversarial: Empty list yields null
        val restoredFromEmptyList = articleSheetRequestSaver.restore(emptyList<String?>())
        assertNull("Restoring from empty list must return null", restoredFromEmptyList)

        // 4. Adversarial: First item null yields null
        val restoredFromNullTopic = articleSheetRequestSaver.restore(listOf(null, "sec-1"))
        assertNull("Restoring from null topic must return null", restoredFromNullTopic)

        // 5. Adversarial: Single element list restores with null section
        val restoredFromSingle = articleSheetRequestSaver.restore(listOf("topic-single"))
        assertEquals(TestArticleSheetRequest("topic-single", null), restoredFromSingle)

        // 6. Adversarial: Empty string topic is preserved
        val restoredFromEmptyTopic = articleSheetRequestSaver.restore(listOf("", null))
        assertEquals(TestArticleSheetRequest("", null), restoredFromEmptyTopic)
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
            assertEquals("Roundtrip failed for $route", route, deserialized)
        }
    }
}
