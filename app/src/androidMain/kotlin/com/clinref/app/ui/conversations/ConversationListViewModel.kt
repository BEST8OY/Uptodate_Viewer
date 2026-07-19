package com.clinref.app.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clinref.app.data.local.entity.ConversationEntity
import com.clinref.app.domain.ai.PatientProfile
import com.clinref.app.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository
) : ViewModel() {

    val conversations: StateFlow<List<ConversationEntity>> =
        conversationRepository.getAllConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createConversation(title: String, patientProfile: PatientProfile, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val id = conversationRepository.createConversation(title, patientProfile)
            onCreated(id)
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            conversationRepository.renameConversation(id, newTitle)
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            conversationRepository.deleteConversation(id)
        }
    }
}
