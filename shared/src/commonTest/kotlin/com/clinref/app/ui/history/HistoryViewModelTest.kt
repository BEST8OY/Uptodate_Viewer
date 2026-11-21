package com.clinref.app.ui.history

import com.clinref.app.domain.HistoryEntry
import com.clinref.app.repository.HistoryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
class HistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val historyRepository: HistoryRepository = mockk(relaxed = true)
    private val historyFlow = MutableStateFlow<List<HistoryEntry>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { historyRepository.history } returns historyFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `history state flow exposes emitted entries from repository`() = runTest(testDispatcher) {
        val entries = listOf(
            HistoryEntry("topic-1", "Hypertension", 1000L),
            HistoryEntry("topic-2", "Diabetes", 2000L)
        )
        historyFlow.value = entries

        val viewModel = HistoryViewModel(historyRepository)
        val collectJob = backgroundScope.launch(testDispatcher) {
            viewModel.history.collect()
        }
        advanceUntilIdle()

        assertEquals(entries, viewModel.history.value)
        collectJob.cancel()
    }

    @Test
    fun `addHistory delegates to repository addOrPromote`() = runTest(testDispatcher) {
        val viewModel = HistoryViewModel(historyRepository)

        viewModel.addHistory("topic-10", "Asthma", 5000L)
        advanceUntilIdle()

        coVerify { historyRepository.addOrPromote("topic-10", "Asthma", 5000L) }
    }

    @Test
    fun `removeHistory delegates to repository remove`() = runTest(testDispatcher) {
        val viewModel = HistoryViewModel(historyRepository)

        viewModel.removeHistory("topic-10")
        advanceUntilIdle()

        coVerify { historyRepository.remove("topic-10") }
    }

    @Test
    fun `clearHistory delegates to repository clear`() = runTest(testDispatcher) {
        val viewModel = HistoryViewModel(historyRepository)

        viewModel.clearHistory()
        advanceUntilIdle()

        coVerify { historyRepository.clear() }
    }
}
