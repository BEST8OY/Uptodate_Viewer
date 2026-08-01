package com.clinref.app.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.repository.ConversationBackup
import com.clinref.app.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class ConversationUiModel(
    val id: String,
    val title: String,
    val lastPreview: String,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val isUnread: Boolean = false,
    val patientProfile: PatientProfile = PatientProfile(),
    val isSelected: Boolean = false
)

enum class ConversationFilter(val displayName: String) {
    ALL("All Sessions"),
    PINNED("Pinned"),
    UNREAD("Unread")
}

data class ConversationListUiState(
    val conversations: List<ConversationUiModel> = emptyList(),
    val filteredConversations: List<ConversationUiModel> = emptyList(),
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val activeFilter: ConversationFilter = ConversationFilter.ALL,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val showDeleteConfirmationDialog: Boolean = false,
    val snackbarMessage: String? = null
)

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(ConversationListUiState())
    val uiState: StateFlow<ConversationListUiState> = _uiState.asStateFlow()

    init {
        observeConversations()
    }

    private fun observeConversations() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                conversationRepository.getAllConversations().collect { entities ->
                    val uiModels = entities.map { entity ->
                        val profile = try {
                            json.decodeFromString<PatientProfile>(entity.patientProfile)
                        } catch (_: Exception) {
                            PatientProfile()
                        }
                        ConversationUiModel(
                            id = entity.id,
                            title = buildTitle(profile),
                            lastPreview = entity.lastMessagePreview.ifBlank { "No messages yet" },
                            timestamp = entity.updatedAt,
                            isPinned = entity.isPinned,
                            isUnread = !entity.isRead,
                            patientProfile = profile
                        )
                    }
                    _uiState.update { state ->
                        state.copy(
                            conversations = uiModels,
                            isLoading = false
                        )
                    }
                    applyFiltersAndSearch()
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        applyFiltersAndSearch()
    }

    fun onSearchActiveChange(active: Boolean) {
        _uiState.update { state ->
            state.copy(
                isSearchActive = active,
                searchQuery = if (!active) "" else state.searchQuery
            )
        }
        applyFiltersAndSearch()
    }

    fun onFilterSelected(filter: ConversationFilter) {
        _uiState.update { it.copy(activeFilter = filter) }
        applyFiltersAndSearch()
    }

    fun toggleSelectionMode() {
        _uiState.update { state ->
            val newMode = !state.isSelectionMode
            state.copy(
                isSelectionMode = newMode,
                selectedIds = if (!newMode) emptySet() else state.selectedIds
            )
        }
    }

    fun toggleItemSelection(id: String) {
        _uiState.update { state ->
            val newSelected = if (state.selectedIds.contains(id)) {
                state.selectedIds - id
            } else {
                state.selectedIds + id
            }
            state.copy(
                selectedIds = newSelected,
                isSelectionMode = newSelected.isNotEmpty()
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            val allIds = state.filteredConversations.map { it.id }.toSet()
            val newSelected = if (state.selectedIds.size == allIds.size) emptySet() else allIds
            state.copy(
                selectedIds = newSelected,
                isSelectionMode = newSelected.isNotEmpty()
            )
        }
    }

    fun togglePin(id: String) {
        viewModelScope.launch {
            conversationRepository.togglePin(id)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
        }
    }

    suspend fun deleteSingleAndReturnBackup(id: String): ConversationBackup? {
        val backup = conversationRepository.getConversationBackup(id)
        conversationRepository.deleteConversation(id)
        return backup
    }

    suspend fun getConversationEntity(id: String): ConversationEntity? {
        return conversationRepository.getConversation(id)
    }

    fun restoreConversation(entity: ConversationEntity) {
        viewModelScope.launch {
            conversationRepository.restoreConversation(entity)
        }
    }

    fun restoreConversationBackup(backup: ConversationBackup) {
        viewModelScope.launch {
            conversationRepository.restoreConversationBackup(backup)
        }
    }

    fun showDeleteConfirmation() {
        if (_uiState.value.selectedIds.isNotEmpty()) {
            _uiState.update { it.copy(showDeleteConfirmationDialog = true) }
        }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmationDialog = false) }
    }

    suspend fun deleteSelectedAndReturnBackups(): List<ConversationBackup> {
        val selected = _uiState.value.selectedIds
        if (selected.isEmpty()) return emptyList()

        val backups = selected.mapNotNull { conversationRepository.getConversationBackup(it) }
        selected.forEach { id ->
            conversationRepository.deleteConversation(id)
        }

        _uiState.update { state ->
            state.copy(
                isSelectionMode = false,
                selectedIds = emptySet(),
                showDeleteConfirmationDialog = false
            )
        }
        return backups
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun markAsRead(id: String) {
        viewModelScope.launch {
            conversationRepository.markAsRead(id)
        }
    }

    fun createNewSession(profile: PatientProfile, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val title = buildTitle(profile)
            val id = conversationRepository.createConversation(title, profile)
            onCreated(id)
        }
    }

    private fun applyFiltersAndSearch() {
        _uiState.update { state ->
            var list = state.conversations

            list = when (state.activeFilter) {
                ConversationFilter.ALL -> list
                ConversationFilter.PINNED -> list.filter { it.isPinned }
                ConversationFilter.UNREAD -> list.filter { it.isUnread }
            }

            if (state.searchQuery.isNotBlank()) {
                val query = state.searchQuery.trim().lowercase()
                list = list.filter {
                    it.title.lowercase().contains(query) ||
                        it.lastPreview.lowercase().contains(query) ||
                        it.patientProfile.conditions.any { cond -> cond.lowercase().contains(query) }
                }
            }

            list = list.sortedWith(
                compareByDescending<ConversationUiModel> { it.isPinned }
                    .thenByDescending { it.timestamp }
            )

            state.copy(filteredConversations = list)
        }
    }

    private fun buildTitle(profile: PatientProfile): String {
        val parts = mutableListOf<String>()
        if (profile.age.isNotBlank()) parts.add(profile.age)
        if (profile.sex.isNotBlank()) parts.add(profile.sex)
        if (profile.conditions.isNotEmpty()) parts.add(profile.conditions.first())
        return if (parts.isNotEmpty()) parts.joinToString(", ") else "Consultation"
    }
}
