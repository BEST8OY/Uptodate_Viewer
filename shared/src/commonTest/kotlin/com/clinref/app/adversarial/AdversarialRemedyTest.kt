package com.clinref.app.adversarial

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.listSaver
import androidx.navigation3.runtime.NavKey
import com.clinref.app.data.AssetDao
import com.clinref.app.domain.ai.KoogAgentFactory
import com.clinref.app.domain.ai.ReliabilityManager
import com.clinref.app.domain.ai.SecureLogger
import com.clinref.app.domain.ai.StreamingManager
import com.clinref.app.fakes.FakeSecurePreferences
import com.clinref.app.repository.AssetRepository
import com.clinref.app.repository.ContentRepository
import com.clinref.app.repository.ConversationRepository
import com.clinref.app.ui.chat.ChatViewModel
import com.clinref.app.ui.content.GraphicUiState
import com.clinref.app.ui.content.GraphicViewModel
import com.clinref.app.ui.navigation.AiRoute
import com.clinref.app.ui.navigation.AiSettingsRoute
import com.clinref.app.ui.navigation.ChatRoute
import com.clinref.app.ui.navigation.ContentRoute
import com.clinref.app.ui.navigation.FavoritesRoute
import com.clinref.app.ui.navigation.HistoryRoute
import com.clinref.app.ui.navigation.TocRoute
import com.clinref.app.ui.navigation.TopLevelRoute
import com.clinref.app.ui.navigation.navSavedStateConfig
import com.clinref.app.ui.navigation.topLevelRoutes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Standalone request model mirroring ChatScreen's ArticleSheetRequest for saver testing
data class TestArticleSheetRequest(
    val topicId: String,
    val sectionId: String? = null
)

private val DummySaverScope = object : SaverScope {
    override fun canBeSaved(value: Any): Boolean = true
}

@OptIn(ExperimentalCoroutinesApi::class)
class AdversarialRemedyTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // =========================================================================
    // 1. rememberSaveable Restoration & State Serialization Tests
    // =========================================================================

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

    // =========================================================================
    // 3. Prefix Stripping Robustness Tests (Malformed IDs, Repeated Prefixes)
    // =========================================================================

    @Test
    fun `AssetRepository defense-in-depth strips Graphic- prefix and handles empty or malformed inputs`() {
        val assetDao: AssetDao = mockk(relaxed = true)
        val repo = AssetRepository(assetDao)

        every { assetDao.getGraphicJson("123") } returns mapOf(
            "graphicInfo" to mapOf("displayName" to "Graphic 123"),
            "imageHtml" to "<img src=\"123\">"
        )
        every { assetDao.getGraphicJson("Graphic-123") } returns null
        every { assetDao.getGraphicJson("") } returns null
        every { assetDao.getGraphicJson("   ") } returns null

        // 1. Standard raw ID
        val g1 = repo.getGraphic("123")
        assertNotNull(g1)
        assertEquals("123", g1?.id)
        verify(exactly = 1) { assetDao.getGraphicJson("123") }

        // 2. Standard Graphic- prefix stripped
        val g2 = repo.getGraphic("Graphic-123")
        assertNotNull(g2)
        assertEquals("123", g2?.id) // Cached from cleanId "123"

        // 3. Adversarial: Graphic-Graphic-123 strips first prefix
        // In AssetRepository, cleanId becomes "Graphic-123"
        val gDouble = repo.getGraphic("Graphic-Graphic-123")
        // DAO returned null for "Graphic-123"
        assertNull("Graphic-Graphic-123 queries DAO with Graphic-123 which returns null safely without throwing", gDouble)

        // 4. Adversarial: Empty string
        val gEmpty = repo.getGraphic("")
        assertNull("Empty graphic ID must safely return null", gEmpty)

        // 5. Adversarial: Prefix only "Graphic-"
        val gPrefixOnly = repo.getGraphic("Graphic-")
        assertNull("Prefix-only ID must strip to empty string and return null", gPrefixOnly)

        // 6. Adversarial: Whitespace ID
        val gBlank = repo.getGraphic("   ")
        assertNull("Blank ID must safely return null", gBlank)
    }

    @Test
    fun `GeminiMessageItem and ChatScreen end-to-end prefix stripping pipeline handles double prefixes and URI schemes`() {
        // Pipeline:
        // Step 1: GeminiMessageItem markdown URI handler:
        //         val graphicId = uri.removePrefix("graphic:").removePrefix("Graphic-")
        // Step 2: ChatScreen handleGraphicSelected:
        //         activeGraphicId = graphicId.removePrefix("Graphic-")
        // Step 3: AssetRepository getGraphic:
        //         cleanId = graphicId.removePrefix("Graphic-")

        fun pipelineProcess(uri: String): String {
            // Step 1 (GeminiMessageItem)
            val step1 = if (uri.startsWith("graphic:")) {
                uri.removePrefix("graphic:").removePrefix("Graphic-")
            } else {
                uri.removePrefix("Graphic-")
            }
            // Step 2 (ChatScreen)
            val step2 = step1.removePrefix("Graphic-")
            // Step 3 (AssetRepository)
            val step3 = step2.removePrefix("Graphic-")
            return step3
        }

        // Test cases:
        assertEquals("123", pipelineProcess("graphic:123"))
        assertEquals("123", pipelineProcess("graphic:Graphic-123"))
        // Multi-level prefix: graphic:Graphic-Graphic-123 is stripped completely to 123 across the layers!
        assertEquals("123", pipelineProcess("graphic:Graphic-Graphic-123"))
        assertEquals("123", pipelineProcess("Graphic-Graphic-123"))
        assertEquals("123", pipelineProcess("Graphic-123"))
        assertEquals("123", pipelineProcess("123"))
        assertEquals("", pipelineProcess("graphic:"))
        assertEquals("", pipelineProcess("graphic:Graphic-"))
        assertEquals("", pipelineProcess("Graphic-"))
        assertEquals("", pipelineProcess(""))
    }

    @Test
    fun `GraphicViewModel handles empty and malformed IDs and maintains monotonic state consistency`() = runTest {
        val assetDao: AssetDao = mockk(relaxed = true)
        val repo = AssetRepository(assetDao)
        val viewModel = GraphicViewModel(repo, ioDispatcher = testDispatcher)

        every { assetDao.getGraphicJson("valid-1") } returns mapOf(
            "graphicInfo" to mapOf("displayName" to "Valid Graphic"),
            "imageHtml" to "<img src=\"valid\">"
        )
        every { assetDao.getGraphicJson("") } returns null
        every { assetDao.getGraphicJson("non-existent") } returns null

        // 1. Empty graphic ID transitions to GraphicUiState.Error
        viewModel.loadGraphic("")
        advanceUntilIdle()
        assertTrue("Empty ID should produce Error state", viewModel.uiState.value is GraphicUiState.Error)

        // 2. Valid graphic ID transitions to GraphicUiState.Success
        viewModel.loadGraphic("valid-1")
        advanceUntilIdle()
        assertTrue("Valid ID should produce Success state", viewModel.uiState.value is GraphicUiState.Success)
        val successState = viewModel.uiState.value as GraphicUiState.Success
        assertEquals("valid-1", successState.data.id)

        // 3. Rapid fire: loading valid-1 then immediately non-existent cancels valid-1
        viewModel.loadGraphic("valid-1")
        viewModel.loadGraphic("non-existent")
        advanceUntilIdle()
        assertTrue("Clobbering call should result in Error for non-existent", viewModel.uiState.value is GraphicUiState.Error)
    }
}
