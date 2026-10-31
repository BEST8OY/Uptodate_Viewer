package com.clinref.app.ui.conversations

import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.repository.ConversationBackup
import com.clinref.app.repository.ConversationRepository
import io.mockk.coEvery
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
        assertEquals(1, viewModel.uiState.value.pinnedConversations.size)
        assertEquals(1, viewModel.uiState.value.recentConversations.size)
        assertEquals("Hypertension Consultation", viewModel.uiState.value.conversations[0].title)
        assertEquals("Asthma Review", viewModel.uiState.value.conversations[1].title)
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `initialization falls back to profile title when entity title is blank`() = runTest(testDispatcher) {
        conversationsFlow.value = listOf(
            ConversationEntity(
                id = "c3",
                title = "   ",
                patientProfile = """{"age":"45yo","sex":"Male","conditions":["Diabetes"]}""",
                createdAt = 1000L,
                updatedAt = 2000L
            )
        )

        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        assertEquals("45yo, Male, Diabetes", viewModel.uiState.value.conversations.first().title)
    }

    @Test
    fun `filtering by PINNED shows only pinned conversations`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()
        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        viewModel.onFilterSelected(ConversationFilter.PINNED)

        assertEquals(1, viewModel.uiState.value.filteredConversations.size)
        assertEquals("c1", viewModel.uiState.value.filteredConversations.first().id)
        assertEquals(1, viewModel.uiState.value.pinnedConversations.size)
        assertEquals(0, viewModel.uiState.value.recentConversations.size)
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
        assertTrue(viewModel.uiState.value.conversations.first { it.id == "c1" }.isSelected)

        viewModel.toggleItemSelection("c1")
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
        assertFalse(viewModel.uiState.value.conversations.first { it.id == "c1" }.isSelected)
    }

    @Test
    fun `clearSelectionMode resets selection mode and unselects items`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()
        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        viewModel.toggleItemSelection("c1")
        assertTrue(viewModel.uiState.value.isSelectionMode)

        viewModel.clearSelectionMode()
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
        assertFalse(viewModel.uiState.value.conversations.first { it.id == "c1" }.isSelected)
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

    @Test
    fun `deleteSingleAndReturnBackup returns backup and calls delete`() = runTest(testDispatcher) {
        val backup = ConversationBackup(entity = sampleEntities().first())
        coEvery { conversationRepository.getConversationBackup("c1") } returns backup

        val viewModel = ConversationListViewModel(conversationRepository)
        val result = viewModel.deleteSingleAndReturnBackup("c1")

        assertEquals(backup, result)
        coVerify { conversationRepository.deleteConversation("c1") }
    }

    @Test
    fun `deleteSelectedAndReturnBackups returns backups and clears selection state`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()
        val backup1 = ConversationBackup(entity = sampleEntities()[0])
        val backup2 = ConversationBackup(entity = sampleEntities()[1])
        coEvery { conversationRepository.getConversationBackup("c1") } returns backup1
        coEvery { conversationRepository.getConversationBackup("c2") } returns backup2

        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        viewModel.toggleItemSelection("c1")
        viewModel.toggleItemSelection("c2")
        assertTrue(viewModel.uiState.value.isSelectionMode)

        viewModel.showDeleteConfirmation()
        assertTrue(viewModel.uiState.value.showDeleteConfirmationDialog)

        val backups = viewModel.deleteSelectedAndReturnBackups()

        assertEquals(2, backups.size)
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
        assertFalse(viewModel.uiState.value.showDeleteConfirmationDialog)
        coVerify { conversationRepository.deleteConversation("c1") }
        coVerify { conversationRepository.deleteConversation("c2") }
    }

    @Test
    fun `restoreConversationBackup delegates to repository`() = runTest(testDispatcher) {
        val backup = ConversationBackup(entity = sampleEntities().first())
        val viewModel = ConversationListViewModel(conversationRepository)

        viewModel.restoreConversationBackup(backup)
        advanceUntilIdle()

        coVerify { conversationRepository.restoreConversationBackup(backup) }
    }

    @Test
    fun `renameConversation delegates trimmed title to repository`() = runTest(testDispatcher) {
        val viewModel = ConversationListViewModel(conversationRepository)

        viewModel.renameConversation("c1", "  Renamed Topic  ")
        advanceUntilIdle()

        coVerify { conversationRepository.renameConversation("c1", "Renamed Topic") }
    }

    @Test
    fun `renameConversation ignores blank titles`() = runTest(testDispatcher) {
        val viewModel = ConversationListViewModel(conversationRepository)

        viewModel.renameConversation("c1", "   ")
        advanceUntilIdle()

        coVerify(exactly = 0) { conversationRepository.renameConversation(any(), any()) }
    }

    @Test
    fun `markSelectedAsRead marks all selected conversations as read and exits selection mode`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()
        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        viewModel.toggleItemSelection("c1")
        viewModel.toggleItemSelection("c2")
        assertTrue(viewModel.uiState.value.isSelectionMode)

        viewModel.markSelectedAsRead()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
        coVerify { conversationRepository.markAsRead("c1") }
        coVerify { conversationRepository.markAsRead("c2") }
    }

    @Test
    fun `togglePinSelected toggles pin state across all selected items`() = runTest(testDispatcher) {
        conversationsFlow.value = sampleEntities()
        val viewModel = ConversationListViewModel(conversationRepository)
        advanceUntilIdle()

        viewModel.toggleItemSelection("c1") // already pinned
        viewModel.toggleItemSelection("c2") // not pinned

        viewModel.togglePinSelected()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedIds.isEmpty())
        // Since at least one is unpinned (c2), target is pinned: only c2 should be toggled to pinned
        coVerify { conversationRepository.togglePin("c2") }
        coVerify(exactly = 0) { conversationRepository.togglePin("c1") }
    }
}
