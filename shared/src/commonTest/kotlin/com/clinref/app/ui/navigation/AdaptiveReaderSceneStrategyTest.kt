package com.clinref.app.ui.navigation

import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.SceneStrategyScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveReaderSceneStrategyTest {

    private fun <T : NavKey> testEntry(key: T): NavEntry<NavKey> {
        return NavEntry(
            key = key,
            contentKey = key,
            metadata = emptyMap()
        ) {
            // no-op for tests
        }
    }

    private val scope = object : SceneStrategyScope<NavKey>() {}

    @Test
    fun `returns null when entries size is less than 2`() {
        val strategy = AdaptiveReaderSceneStrategy<NavKey>(isExpanded = true)
        val entries = listOf(testEntry(ChatRoute("c1")))

        with(strategy) {
            val scene = scope.calculateScene(entries)
            assertNull(scene)
        }
    }

    @Test
    fun `returns null when last entry is not ContentRoute`() {
        val strategy = AdaptiveReaderSceneStrategy<NavKey>(isExpanded = true)
        val entries = listOf(
            testEntry(AiRoute),
            testEntry(ChatRoute("c1"))
        )

        with(strategy) {
            val scene = scope.calculateScene(entries)
            assertNull(scene)
        }
    }

    @Test
    fun `returns null when no preceding ChatRoute exists on stack`() {
        val strategy = AdaptiveReaderSceneStrategy<NavKey>(isExpanded = true)
        // Opened from Toc instead of Chat
        val entries = listOf(
            testEntry(TocRoute),
            testEntry(ContentRoute("topic-1"))
        )

        with(strategy) {
            val scene = scope.calculateScene(entries)
            assertNull(scene)
        }
    }

    @Test
    fun `returns SupportingPaneScene on expanded screen with ChatRoute and ContentRoute`() {
        val strategy = AdaptiveReaderSceneStrategy<NavKey>(isExpanded = true)
        val chatEntry = testEntry(ChatRoute("c1"))
        val contentEntry = testEntry(ContentRoute("topic-1", "sec-A"))
        val entries = listOf(
            testEntry(AiRoute),
            chatEntry,
            contentEntry
        )

        with(strategy) {
            val scene = scope.calculateScene(entries)
            assertNotNull(scene)
            assertTrue("Expected SupportingPaneScene", scene is SupportingPaneScene<*>)
            val supporting = scene as SupportingPaneScene<NavKey>
            assertEquals(chatEntry, supporting.firstEntry)
            assertEquals(contentEntry, supporting.secondEntry)
            assertEquals(listOf(testEntry(AiRoute), chatEntry), supporting.previousEntries)
            assertEquals(listOf(chatEntry, contentEntry), supporting.entries)
        }
    }

    @Test
    fun `returns InWindowOverlayScene on compact screen preserving underlying stack`() {
        val strategy = AdaptiveReaderSceneStrategy<NavKey>(isExpanded = false)
        val chatEntry = testEntry(ChatRoute("c1"))
        val contentEntry = testEntry(ContentRoute("topic-1", "sec-A"))
        val entries = listOf(
            testEntry(AiRoute),
            chatEntry,
            contentEntry
        )

        with(strategy) {
            val scene = scope.calculateScene(entries)
            assertNotNull(scene)
            assertTrue("Expected InWindowOverlayScene", scene is InWindowOverlayScene<*>)
            val overlay = scene as InWindowOverlayScene<NavKey>
            assertEquals(contentEntry, overlay.entry)
            assertEquals(listOf(testEntry(AiRoute), chatEntry), overlay.overlaidEntries)
            assertEquals(listOf(testEntry(AiRoute), chatEntry), overlay.previousEntries)
            assertEquals(listOf(contentEntry), overlay.entries)
        }
    }

    @Test
    fun `handles multiple chained ContentRoutes preserving root ChatRoute as primary pane`() {
        val strategyExpanded = AdaptiveReaderSceneStrategy<NavKey>(isExpanded = true)
        val chatEntry = testEntry(ChatRoute("c1"))
        val content1 = testEntry(ContentRoute("topic-1"))
        val content2 = testEntry(ContentRoute("topic-2"))
        val entries = listOf(
            testEntry(AiRoute),
            chatEntry,
            content1,
            content2
        )

        with(strategyExpanded) {
            val scene = scope.calculateScene(entries)
            assertNotNull(scene)
            val supporting = scene as SupportingPaneScene<NavKey>
            assertEquals(chatEntry, supporting.firstEntry)
            assertEquals(content2, supporting.secondEntry)
            assertEquals(listOf(testEntry(AiRoute), chatEntry, content1), supporting.previousEntries)
        }
    }

    @Test
    fun `returns ModalOverlayScene when last entry is GraphicRoute preserving underlying scene`() {
        val strategy = AdaptiveReaderSceneStrategy<NavKey>(isExpanded = true)
        val contentEntry = testEntry(ContentRoute("topic-1"))
        val graphicEntry = testEntry(GraphicRoute("graphic-999"))
        val entries = listOf(
            testEntry(TocRoute),
            contentEntry,
            graphicEntry
        )

        with(strategy) {
            val scene = scope.calculateScene(entries)
            assertNotNull(scene)
            assertTrue("Expected ModalOverlayScene", scene is ModalOverlayScene<*>)
            val overlay = scene as ModalOverlayScene<NavKey>
            assertEquals(graphicEntry, overlay.entry)
            assertEquals(listOf(testEntry(TocRoute), contentEntry), overlay.overlaidEntries)
            assertEquals(listOf(testEntry(TocRoute), contentEntry), overlay.previousEntries)
            assertEquals(listOf(graphicEntry), overlay.entries)
        }
    }
}
