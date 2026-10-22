package com.byokai.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byokai.R
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.model.Conversation
import com.byokai.domain.repository.ConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the conversations screen.
 */
data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = true,
    /** Conversation id awaiting delete confirmation. */
    val pendingDeleteId: String? = null,
    /** Conversation id awaiting rename. */
    val pendingRenameId: String? = null,
    /** Current search query; blank shows every conversation. */
    val searchQuery: String = "",
)

/**
 * Backs the conversations screen: the persisted history list.
 *
 * The list is driven by the repository (all conversations, or the
 * title/content search when a query is present), so it reflects every
 * write (new chat, rename, message append, delete) without manual
 * refreshing. Failures surface on [errors] as [UiText], mirroring
 * ProvidersViewModel; delete confirmation follows the same
 * request/cancel/confirm shape, and rename reuses it.
 *
 * No Android dependencies — JVM-unit-testable.
 */
class ConversationsViewModel(
    private val repository: ConversationRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _errors = Channel<UiText>(Channel.BUFFERED)
    val errors: Flow<UiText> = _errors.receiveAsFlow()

    /**
     * One-shot navigation events: the id of a newly created
     * conversation, to be opened by the screen.
     */
    private val _openConversation = Channel<String>(Channel.BUFFERED)
    val openConversation: Flow<String> = _openConversation.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    init {
        viewModelScope.launch {
            _searchQuery.flatMapLatest { query ->
                if (query.isBlank()) {
                    repository.conversations()
                } else {
                    repository.searchConversations(query)
                }
            }.collect { conversations ->
                _uiState.update {
                    it.copy(conversations = conversations, isLoading = false)
                }
            }
        }
    }

    /** Updates the search query; the list re-queries reactively. */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _uiState.update { it.copy(searchQuery = query) }
    }

    /**
     * Creates a conversation with [defaultTitle] and emits its id on
     * [openConversation] for the screen to navigate to. The chat screen
     * renames it from the first user message.
     */
    fun createConversation(defaultTitle: String) {
        viewModelScope.launch {
            when (val result = repository.create(defaultTitle)) {
                is Result.Success -> _openConversation.trySend(result.data.id)
                is Result.Error ->
                    _errors.trySend(UiText.Res(R.string.conversations_create_error))
                // Repository writes never return Loading; defensive.
                is Result.Loading -> Unit
            }
        }
    }

    /** Opens the delete-confirmation dialog for [id]. */
    fun requestDelete(id: String) {
        _uiState.update { it.copy(pendingDeleteId = id) }
    }

    /** Dismisses the delete-confirmation dialog. */
    fun cancelDelete() {
        _uiState.update { it.copy(pendingDeleteId = null) }
    }

    /**
     * Deletes the pending conversation (its messages go with it via
     * foreign-key cascade). The dialog closes regardless; a failure
     * surfaces on [errors].
     */
    fun confirmDelete() {
        val id = _uiState.value.pendingDeleteId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingDeleteId = null) }
            if (repository.delete(id) is Result.Error) {
                _errors.trySend(UiText.Res(R.string.conversations_delete_error))
            }
        }
    }

    /** Opens the rename dialog for [id]. */
    fun requestRename(id: String) {
        _uiState.update { it.copy(pendingRenameId = id) }
    }

    /** Dismisses the rename dialog. */
    fun cancelRename() {
        _uiState.update { it.copy(pendingRenameId = null) }
    }

    /**
     * Renames the pending conversation. Blank titles are ignored (the
     * dialog stays open); the dialog closes on confirm and a failure
     * surfaces on [errors].
     */
    fun confirmRename(title: String) {
        val id = _uiState.value.pendingRenameId ?: return
        val clean = title.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingRenameId = null) }
            if (repository.rename(id, clean) is Result.Error) {
                _errors.trySend(UiText.Res(R.string.conversations_rename_error))
            }
        }
    }
}
