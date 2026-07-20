package com.clinref.app.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationSummary(
    val id: String,
    val patientProfile: PatientProfile,
    val lastPreviewText: String,
    val lastUpdateMs: Long,
    val isUnread: Boolean,
    val totalTokens: Int,
    val tokenLimitTripped: Boolean
)

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val allConversations: StateFlow<List<ConversationEntity>> =
        conversationRepository.getAllConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiConversations: StateFlow<List<ConversationSummary>> =
        combine(allConversations, _searchQuery) { convos, query ->
            convos.map { entity ->
                val profile = try {
                    val raw = entity.patientProfile
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString<PatientProfile>(raw)
                } catch (_: Exception) {
                    PatientProfile()
                }
                ConversationSummary(
                    id = entity.id,
                    patientProfile = profile,
                    lastPreviewText = entity.lastMessagePreview.ifBlank { "No messages yet" },
                    lastUpdateMs = entity.updatedAt,
                    isUnread = !entity.isRead,
                    totalTokens = entity.totalTokens,
                    tokenLimitTripped = entity.totalTokens >= entity.tokenLimit
                )
            }.filter { summary ->
                if (query.isBlank()) return@filter true
                summary.patientProfile.conditions.any { it.contains(query, ignoreCase = true) } ||
                    summary.lastPreviewText.contains(query, ignoreCase = true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadCount: StateFlow<Int> =
        conversationRepository.getUnreadCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun createConversation(title: String, patientProfile: PatientProfile, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = conversationRepository.createConversation(title, patientProfile)
            onCreated(id)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
        }
    }

    fun markAsRead(id: String) {
        viewModelScope.launch {
            conversationRepository.markAsRead(id)
        }
    }
}
