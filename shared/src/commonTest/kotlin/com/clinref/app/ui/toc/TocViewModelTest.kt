package com.clinref.app.ui.toc

import androidx.lifecycle.SavedStateHandle
import com.clinref.app.domain.TocItem
import com.clinref.app.repository.TocRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TocViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val tocRepository: TocRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle()
    ): TocViewModel {
        return TocViewModel(
            tocRepository = tocRepository,
            savedStateHandle = savedStateHandle,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `init loads root TOC items successfully`() = runTest(testDispatcher) {
        val rootItems = listOf(
            TocItem(id = "root-1", title = "Cardiovascular", isLeaf = false, type = "folder", childrenInfo = null),
            TocItem(id = "root-2", title = "Pulmonology", isLeaf = false, type = "folder", childrenInfo = null)
        )
        every { tocRepository.getTocItems() } returns rootItems

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.isLoading.value)
        assertNull(viewModel.error.value)
        assertEquals(rootItems, viewModel.tocItems.value)
    }

    @Test
    fun `init failure records error and clears loading state`() = runTest(testDispatcher) {
        every { tocRepository.getTocItems() } throws RuntimeException("SQLite read error")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.isLoading.value)
        assertEquals("SQLite read error", viewModel.error.value)
        assertTrue(viewModel.tocItems.value.isEmpty())
    }

    @Test
    fun `loadChildren fetches and attaches child items to correct parent item`() = runTest(testDispatcher) {
        val rootItem = TocItem(id = "root-1", title = "Cardiology", isLeaf = false, type = "folder", childrenInfo = null)
        val children = listOf(
            TocItem(id = "child-101", title = "Heart Failure", isLeaf = true, type = "topic", childrenInfo = null)
        )
        every { tocRepository.getTocItems() } returns listOf(rootItem)
        every { tocRepository.getTocItems("root-1") } returns children

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loadChildren("root-1")
        advanceUntilIdle()

        val updatedRoot = viewModel.tocItems.value.first()
        assertEquals(children, updatedRoot.childrenInfo)
    }

    @Test
    fun `toggleExpanded alternates presence in expandedIds set`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.toggleExpanded("item-1")
        advanceUntilIdle()
        assertTrue(viewModel.expandedIds.value.contains("item-1"))

        viewModel.toggleExpanded("item-1")
        advanceUntilIdle()
        assertFalse(viewModel.expandedIds.value.contains("item-1"))
    }

    @Test
    fun `resolveTopicId delegates directly to repository`() = runTest(testDispatcher) {
        every { tocRepository.getTopicIdFromTocId("toc-456") } returns "topic-999"
        val viewModel = createViewModel()

        val resolved = viewModel.resolveTopicId("toc-456")
        assertEquals("topic-999", resolved)
        verify { tocRepository.getTopicIdFromTocId("toc-456") }
    }

    @Test
    fun `retry reloads root TOC items`() = runTest(testDispatcher) {
        val rootItems = listOf(
            TocItem(id = "root-1", title = "Oncology", isLeaf = false, type = "folder", childrenInfo = null)
        )
        every { tocRepository.getTocItems() } throws RuntimeException("Failed first") andThen rootItems

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertEquals("Failed first", viewModel.error.value)

        viewModel.retry()
        advanceUntilIdle()

        assertNull(viewModel.error.value)
        assertEquals(rootItems, viewModel.tocItems.value)
    }
}
