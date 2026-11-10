package com.clinref.app.ui.favorites

import com.clinref.app.domain.FavoriteEntry
import com.clinref.app.repository.FavoriteRepository
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
class FavoritesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val favoriteRepository: FavoriteRepository = mockk(relaxed = true)
    private val favoritesFlow = MutableStateFlow<List<FavoriteEntry>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { favoriteRepository.favorites } returns favoritesFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `favorites state flow exposes emitted entries from repository`() = runTest(testDispatcher) {
        val entries = listOf(
            FavoriteEntry("fav-1", "Cardiomyopathy", 1500L),
            FavoriteEntry("fav-2", "Atrial Fibrillation", 2500L)
        )
        favoritesFlow.value = entries

        val viewModel = FavoritesViewModel(favoriteRepository)
        val collectJob = backgroundScope.launch(testDispatcher) {
            viewModel.favorites.collect()
        }
        advanceUntilIdle()

        assertEquals(entries, viewModel.favorites.value)
        collectJob.cancel()
    }

    @Test
    fun `addFavorite delegates to repository add`() = runTest(testDispatcher) {
        val viewModel = FavoritesViewModel(favoriteRepository)

        viewModel.addFavorite("fav-10", "Endocarditis", 3000L)
        advanceUntilIdle()

        coVerify { favoriteRepository.add("fav-10", "Endocarditis", 3000L) }
    }

    @Test
    fun `removeFavorite delegates to repository remove`() = runTest(testDispatcher) {
        val viewModel = FavoritesViewModel(favoriteRepository)

        viewModel.removeFavorite("fav-10")
        advanceUntilIdle()

        coVerify { favoriteRepository.remove("fav-10") }
    }

    @Test
    fun `clearFavorites delegates to repository clearAll`() = runTest(testDispatcher) {
        val viewModel = FavoritesViewModel(favoriteRepository)

        viewModel.clearFavorites()
        advanceUntilIdle()

        coVerify { favoriteRepository.clearAll() }
    }
}
