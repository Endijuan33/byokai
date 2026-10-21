package com.byokai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.byokai.domain.chat.ChatRepository
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialStore

/**
 * Manual [ViewModelProvider.Factory] for [ChatViewModel].
 *
 * Interim until Hilt returns (see docs/ARCHITECTURE.md); replaced by
 * `@HiltViewModel` injection when KSP supports Kotlin 2.4.x.
 */
class ChatViewModelFactory(
    private val chatRepository: ChatRepository,
    private val configRepository: ProviderConfigRepository,
    private val credentialStore: CredentialStore,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return ChatViewModel(chatRepository, configRepository, credentialStore) as T
    }
}
