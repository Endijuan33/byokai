package com.byokai.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.byokai.domain.repository.ConversationRepository

/**
 * Manual [ViewModelProvider.Factory] for [ConversationsViewModel].
 *
 * Interim until Hilt returns (see docs/ARCHITECTURE.md); replaced by
 * `@HiltViewModel` injection when KSP supports Kotlin 2.4.x.
 */
class ConversationsViewModelFactory(
    private val conversationRepository: ConversationRepository,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ConversationsViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return ConversationsViewModel(conversationRepository) as T
    }
}
