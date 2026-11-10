package com.clinref.app.ui.search

import androidx.lifecycle.SavedStateHandle
import com.clinref.app.domain.Audience
import com.clinref.app.domain.SearchResult
import com.clinref.app.repository.SearchRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class SearchViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val searchRepository: SearchRepository = mockk(relaxed = true)

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
    ): SearchViewModel {
        return SearchViewModel(
            searchRepository = searchRepository,
            savedStateHandle = savedStateHandle,
            ioDispatcher = testDispatcher
        )
    }

    @Test
    fun `onQueryChanged with query length less than or equal to 2 clears suggestions`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.onQueryChanged("he")
        advanceUntilIdle()

        assertTrue(viewModel.suggestions.value.isEmpty())
        verify(exactly = 0) { searchRepository.getSuggestions(any()) }
    }

    @Test
    fun `onQueryChanged with query length greater than 2 debounces and fetches suggestions`() = runTest(testDispatcher) {
        every { searchRepository.getSuggestions("heart") } returns listOf("heart failure", "heart attack")
        val viewModel = createViewModel()

        viewModel.onQueryChanged("heart")

        // Before debounce period
        advanceTimeBy(200)
        assertTrue(viewModel.suggestions.value.isEmpty())

        // Complete debounce (300ms total)
        advanceTimeBy(150)
        advanceUntilIdle()

        assertEquals(listOf("heart failure", "heart attack"), viewModel.suggestions.value)
    }

    @Test
    fun `search with blank query is ignored`() = runTest(testDispatcher) {
        val viewModel = createViewModel()

        viewModel.search("   ")
        advanceUntilIdle()

        assertFalse(viewModel.isLoading.value)
        assertTrue(viewModel.searchResults.value.isEmpty())
        verify(exactly = 0) { searchRepository.searchTopics(any(), any()) }
    }

    @Test
    fun `search with valid query populates results and clears suggestions and loading`() = runTest(testDispatcher) {
        val expectedResults = listOf(
            SearchResult.Topic("Heart Failure Management", "topic-101"),
            SearchResult.Graphic("Heart Anatomy", "graphic-202")
        )
        every { searchRepository.searchTopics("heart", Audience.ALL) } returns expectedResults
        val viewModel = createViewModel()

        viewModel.search("heart")
        advanceUntilIdle()

        assertFalse(viewModel.isLoading.value)
        assertNull(viewModel.error.value)
        assertEquals(expectedResults, viewModel.searchResults.value)
        assertTrue(viewModel.suggestions.value.isEmpty())
    }

    @Test
    fun `search failure records error message and turns off loading`() = runTest(testDispatcher) {
        every { searchRepository.searchTopics("asthma", Audience.ALL) } throws RuntimeException("Database error")
        val viewModel = createViewModel()

        viewModel.search("asthma")
        advanceUntilIdle()

        assertFalse(viewModel.isLoading.value)
        assertEquals("Database error", viewModel.error.value)
        assertTrue(viewModel.searchResults.value.isEmpty())
    }

    @Test
    fun `onAudienceChanged updates selected audience and re-runs search if query is non-empty`() = runTest(testDispatcher) {
        val patientResults = listOf(
            SearchResult.Topic("Heart Failure (Patient Education)", "topic-patient-1")
        )
        every { searchRepository.searchTopics("heart", Audience.PATIENT) } returns patientResults
        val viewModel = createViewModel()

        viewModel.searchQueryState.value = "heart"
        viewModel.onAudienceChanged(Audience.PATIENT)
        advanceUntilIdle()

        assertEquals(Audience.PATIENT, viewModel.selectedAudience.value)
        assertEquals(patientResults, viewModel.searchResults.value)
    }

    @Test
    fun `restoring saved query from SavedStateHandle auto-initiates search on creation`() = runTest(testDispatcher) {
        val restoredResults = listOf(
            SearchResult.Topic("Hypertension", "topic-303")
        )
        every { searchRepository.searchTopics("hypertension", Audience.ALL) } returns restoredResults

        val savedState = SavedStateHandle(mapOf("search_query" to "hypertension"))
        val viewModel = createViewModel(savedState)
        advanceUntilIdle()

        assertEquals(restoredResults, viewModel.searchResults.value)
    }
}
