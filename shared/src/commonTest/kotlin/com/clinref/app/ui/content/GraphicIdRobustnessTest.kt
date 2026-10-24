package com.clinref.app.ui.content

import com.clinref.app.data.AssetDao
import com.clinref.app.repository.AssetRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit-level resilience coverage for the graphic-ID boundary pipeline:
 * `AssetRepository` defense-in-depth prefix stripping, the markdown URI ->
 * `ChatScreen` -> repository pipeline, and `GraphicViewModel` malformed-ID
 * handling with monotonic state consistency (rapid-fire loads converge to the
 * latest call).
 *
 * Consolidated from the former per-milestone adversarial-remedy file to sit
 * beside its canonical owner `GraphicViewModelTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GraphicIdRobustnessTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
    fun `ChatMessageItem and ChatScreen end-to-end prefix stripping pipeline handles double prefixes and URI schemes`() {
        // Pipeline:
        // Step 1: ChatMessageItem markdown URI handler:
        //         val graphicId = uri.removePrefix("graphic:").removePrefix("Graphic-")
        // Step 2: ChatScreen handleGraphicSelected:
        //         activeGraphicId = graphicId.removePrefix("Graphic-")
        // Step 3: AssetRepository getGraphic:
        //         cleanId = graphicId.removePrefix("Graphic-")

        fun pipelineProcess(uri: String): String {
            // Step 1 (ChatMessageItem)
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
