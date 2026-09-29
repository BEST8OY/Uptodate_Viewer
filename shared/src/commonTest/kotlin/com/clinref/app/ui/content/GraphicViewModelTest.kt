package com.clinref.app.ui.content

import com.clinref.app.domain.GraphicData
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GraphicViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val assetRepository: AssetRepository = mockk()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = GraphicViewModel(assetRepository, dispatcher)

    @Test
    fun `loadGraphic emits Success on decoded graphic`() = runTest(dispatcher.scheduler) {
        val data = GraphicData(id = "1", imageHtml = "<p>x</p>")
        every { assetRepository.getGraphic("1") } returns data
        val vm = viewModel()

        vm.loadGraphic("1")
        advanceUntilIdle()

        assertEquals(GraphicUiState.Success(data), vm.uiState.value)
    }

    @Test
    fun `loadGraphic emits Error on null result`() = runTest(dispatcher.scheduler) {
        every { assetRepository.getGraphic("missing") } returns null
        val vm = viewModel()

        vm.loadGraphic("missing")
        advanceUntilIdle()

        assertEquals(GraphicUiState.Error, vm.uiState.value)
    }

    @Test
    fun `loadGraphic emits Error on exception instead of crashing`() = runTest(dispatcher.scheduler) {
        every { assetRepository.getGraphic("boom") } throws RuntimeException("db closed")
        val vm = viewModel()

        vm.loadGraphic("boom")
        advanceUntilIdle()

        assertEquals(GraphicUiState.Error, vm.uiState.value)
    }

    @Test
    fun `retry reloads last requested graphic`() = runTest(dispatcher.scheduler) {
        val data = GraphicData(id = "7", imageHtml = "<img>")
        every { assetRepository.getGraphic("7") } throws RuntimeException("flaky") andThen data
        val vm = viewModel()

        vm.loadGraphic("7")
        advanceUntilIdle()
        assertEquals(GraphicUiState.Error, vm.uiState.value)

        vm.retry()
        advanceUntilIdle()

        assertEquals(GraphicUiState.Success(data), vm.uiState.value)
        verify(exactly = 2) { assetRepository.getGraphic("7") }
    }
}
