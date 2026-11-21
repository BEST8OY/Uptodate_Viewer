package com.clinref.app.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.repository.ConversationBackup
import com.clinref.app.repository.ConversationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

data class ConversationUiModel(
    val id: String,
    val title: String,
    val lastPreview: String,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val patientProfile: PatientProfile = PatientProfile(),
    val isSelected: Boolean = false
)

data class ConversationListUiState(
    val conversations: List<ConversationUiModel> = emptyList(),
    val filteredConversations: List<ConversationUiModel> = emptyList(),
    val pinnedConversations: List<ConversationUiModel> = emptyList(),
    val recentConversations: List<ConversationUiModel> = emptyList(),
    val searchQuery: String = "",
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val showDeleteConfirmationDialog: Boolean = false
)

class ConversationListViewModel(
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
                    val currentSelected = _uiState.value.selectedIds
                    val uiModels = entities.map { entity ->
                        val profile = try {
                            json.decodeFromString<PatientProfile>(entity.patientProfile)
                        } catch (_: Exception) {
                            PatientProfile()
                        }
                        ConversationUiModel(
                            id = entity.id,
                            title = entity.title.ifBlank { buildTitle(profile) },
                            lastPreview = entity.lastMessagePreview.ifBlank { "No messages yet" },
                            timestamp = entity.updatedAt,
                            isPinned = entity.isPinned,
                            patientProfile = profile,
                            isSelected = entity.id in currentSelected
                        )
                    }

                    val query = _uiState.value.searchQuery
                    val (filtered, pinned, recent) = filterAndPartition(uiModels, query)

                    _uiState.update { state ->
                        state.copy(
                            conversations = uiModels,
                            filteredConversations = filtered,
                            pinnedConversations = pinned,
                            recentConversations = recent,
                            isLoading = false
                        )
                    }
                }
            } catch (_: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { state ->
            val (filtered, pinned, recent) = filterAndPartition(
                conversations = state.conversations,
                query = query
            )
            state.copy(
                searchQuery = query,
                filteredConversations = filtered,
                pinnedConversations = pinned,
                recentConversations = recent
            )
        }
    }

    fun toggleSelectionMode() {
        _uiState.update { state ->
            val newMode = !state.isSelectionMode
            val newSelected = if (!newMode) emptySet() else state.selectedIds
            val updatedConversations = state.conversations.map { it.copy(isSelected = it.id in newSelected) }
            val (filtered, pinned, recent) = filterAndPartition(updatedConversations, state.searchQuery)
            state.copy(
                conversations = updatedConversations,
                filteredConversations = filtered,
                pinnedConversations = pinned,
                recentConversations = recent,
                isSelectionMode = newMode,
                selectedIds = newSelected
            )
        }
    }

    fun clearSelectionMode() {
        _uiState.update { state ->
            val updatedConversations = state.conversations.map { it.copy(isSelected = false) }
            val (filtered, pinned, recent) = filterAndPartition(updatedConversations, state.searchQuery)
            state.copy(
                conversations = updatedConversations,
                filteredConversations = filtered,
                pinnedConversations = pinned,
                recentConversations = recent,
                isSelectionMode = false,
                selectedIds = emptySet()
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
            val updatedConversations = state.conversations.map { it.copy(isSelected = it.id in newSelected) }
            val (filtered, pinned, recent) = filterAndPartition(updatedConversations, state.searchQuery)
            state.copy(
                conversations = updatedConversations,
                filteredConversations = filtered,
                pinnedConversations = pinned,
                recentConversations = recent,
                selectedIds = newSelected,
                isSelectionMode = newSelected.isNotEmpty()
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            val allIds = state.filteredConversations.map { it.id }.toSet()
            val updatedConversations = state.conversations.map { it.copy(isSelected = it.id in allIds) }
            val (filtered, pinned, recent) = filterAndPartition(updatedConversations, state.searchQuery)
            state.copy(
                conversations = updatedConversations,
                filteredConversations = filtered,
                pinnedConversations = pinned,
                recentConversations = recent,
                selectedIds = allIds,
                isSelectionMode = allIds.isNotEmpty()
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
            val remaining = state.conversations.filterNot { it.id in selected }
            val (filtered, pinned, recent) = filterAndPartition(remaining, state.searchQuery)
            state.copy(
                conversations = remaining,
                filteredConversations = filtered,
                pinnedConversations = pinned,
                recentConversations = recent,
                isSelectionMode = false,
                selectedIds = emptySet(),
                showDeleteConfirmationDialog = false
            )
        }
        return backups
    }

    fun markAsRead(id: String) {
        viewModelScope.launch {
            conversationRepository.markAsRead(id)
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            conversationRepository.renameConversation(id, trimmed)
        }
    }

    fun togglePinSelected() {
        val selected = _uiState.value.selectedIds
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val selectedList = _uiState.value.conversations.filter { it.id in selected }
            val shouldPin = selectedList.any { !it.isPinned }
            selectedList.forEach { item ->
                if (item.isPinned != shouldPin) {
                    conversationRepository.togglePin(item.id)
                }
            }
            clearSelectionMode()
        }
    }

    fun createNewSession(profile: PatientProfile, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val title = buildTitle(profile)
            val id = conversationRepository.createConversation(title, profile)
            onCreated(id)
        }
    }

    private fun filterAndPartition(
        conversations: List<ConversationUiModel>,
        query: String
    ): Triple<List<ConversationUiModel>, List<ConversationUiModel>, List<ConversationUiModel>> {
        var list = conversations

        if (query.isNotBlank()) {
            val trimmed = query.trim().lowercase()
            list = list.filter {
                it.title.lowercase().contains(trimmed) ||
                    it.lastPreview.lowercase().contains(trimmed) ||
                    it.patientProfile.conditions.any { cond -> cond.lowercase().contains(trimmed) }
            }
        }

        val sorted = list.sortedWith(
            compareByDescending<ConversationUiModel> { it.isPinned }
                .thenByDescending { it.timestamp }
        )
        val pinned = sorted.filter { it.isPinned }
        val recent = sorted.filter { !it.isPinned }
        return Triple(sorted, pinned, recent)
    }

    private fun buildTitle(profile: PatientProfile): String {
        val parts = mutableListOf<String>()
        if (profile.age.isNotBlank()) parts.add(profile.age)
        if (profile.sex.isNotBlank()) parts.add(profile.sex)
        if (profile.conditions.isNotEmpty()) parts.add(profile.conditions.first())
        return if (parts.isNotEmpty()) parts.joinToString(", ") else "Consultation"
    }
}
