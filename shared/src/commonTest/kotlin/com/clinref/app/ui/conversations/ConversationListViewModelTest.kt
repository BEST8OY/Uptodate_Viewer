package com.clinref.app.ui.conversations

import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.repository.ConversationRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationListViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val conversationRepository: ConversationRepository = mockk(relaxed = true)
    private val conversationsFlow = MutableStateFlow<List<ConversationEntity>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { conversationRepository.getAllConversations() } returns conversationsFlow
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleEntities() = listOf(
        ConversationEntity(
            id = "c1",
            title = "Hypertension Consultation",
            patientProfile = "{}",
            createdAt = 1000L,
            updatedAt = 2000L,
            isRead = true,
            isPinned = true,
            lastMessagePreview = "Start ACE inhibitor"
        ),
        ConversationEntity(
            id = "c2",
            title = "Asthma Review",
            patientProfile = "{}",
            createdAt = 1100L,
            updatedAt = 2100L,
            isRead = false,
            isPinned = false,
            lastMessagePreview = "Adjust albuterol inhaler"
        )
    )

    @Test
    fun `initialization maps entities to UI models and applies ALL filter by default`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()

        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.conversations.size)
        assertEquals(2, viewModel.uiState.value.filteredConversations.size)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `filtering by PINNED shows only pinned conversations`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()
        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        viewModel.onFilterSelected(ConversationFilter.PINNED)

        assertEquals(1, viewModel.uiState.value.filteredConversations.size)
        assertEquals("c1", viewModel.uiState.value.filteredConversations.first().id)
    }

    @Test
    fun `filtering by UNREAD shows only unread conversations`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()
        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        viewModel.onFilterSelected(ConversationFilter.UNREAD)

        assertEquals(1, viewModel.uiState.value.filteredConversations.size)
        assertEquals("c2", viewModel.uiState.value.filteredConversations.first().id)
    }

    @Test
    fun `search query filters conversations by preview or title`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()
        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        viewModel.onSearchQueryChange("albuterol")

        assertEquals(1, viewModel.uiState.value.filteredConversations.size)
        assertEquals("c2", viewModel.uiState.value.filteredConversations.first().id)
    }

    @Test
    fun `toggleItemSelection updates selectedIds and activates selection mode`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()
        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        viewModel.toggleItemSelection("c1")
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.contains("c1"))

        viewModel.toggleItemSelection("c1")
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
    }

    @Test
    fun `togglePin delegates to conversation repository`() = runTest(testDispatcher) {
        val viewModel = ConversationListViewModel(conversationRepository)

        viewModel.togglePin("c1")
        advanceUntilIdle()

        coVerify { conversationRepository.togglePin("c1") }
    }

    @Test
    fun `deleteConversation delegates to conversation repository`() = runTest(testDispatcher) {
        val viewModel = ConversationListViewModel(conversationRepository)

        viewModel.deleteConversation("c1")
        advanceUntilIdle()

        coVerify { conversationRepository.deleteConversation("c1") }
    }
}
