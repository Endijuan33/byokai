package com.byokai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.byokai.data.local.ImageProcessor
import com.byokai.domain.chat.ChatRepository
import com.byokai.domain.repository.AttachmentStorage
import com.byokai.domain.repository.ConversationRepository
import com.byokai.domain.repository.PromptRepository
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialStore

/**
 * Manual [ViewModelProvider.Factory] for [ChatViewModel].
 *
 * Interim until Hilt returns (see docs/ARCHITECTURE.md); replaced by
 * `@HiltViewModel` injection when KSP supports Kotlin 2.4.x.
 *
 * [conversationRepository] is required (not defaulted) so a missing
 * wiring is a compile error at the call site, not a silent fallback to
 * in-memory history.
 *
 * Phase 9B: [attachmentStorage] and [imageProcessor] are required too.
 * The coordinator must expose them on AppContainer (Worker A provides
 * the implementations); until then the call site in [ChatScreen] does
 * not compile — intentionally loud, not a silent no-op.
 */
class ChatViewModelFactory(
    private val chatRepository: ChatRepository,
    private val configRepository: ProviderConfigRepository,
    private val credentialStore: CredentialStore,
    private val conversationRepository: ConversationRepository,
    private val promptRepository: PromptRepository,
    private val attachmentStorage: AttachmentStorage,
    private val imageProcessor: ImageProcessor,
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return ChatViewModel(
            chatRepository,
            configRepository,
            credentialStore,
            conversationRepository,
            promptRepository,
            attachmentStorage,
            imageProcessor,
        ) as T
    }
}
