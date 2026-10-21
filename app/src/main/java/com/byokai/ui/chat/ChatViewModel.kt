package com.byokai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byokai.R
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRepository
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.repository.ConversationRepository
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialStore
import com.byokai.ui.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One visible chat turn.
 *
 * @property isPending true while the turn is still streaming (or was
 * interrupted by an error / stop); false once the terminal [ChatEvent]
 * for it has been processed.
 */
data class ChatUiMessage(
    val role: ChatRole,
    val text: String,
    val isPending: Boolean,
)

/**
 * UI state for the chat screen.
 */
data class ChatUiState(
    val configs: List<ProviderConfig> = emptyList(),
    /** Whether a key is stored per config id (value only, never the key). */
    val keyReady: Map<String, Boolean> = emptyMap(),
    val selectedConfigId: String? = null,
    val selectedModelId: String? = null,
    val messages: List<ChatUiMessage> = emptyList(),
    val input: String = "",
    val generating: Boolean = false,
    val error: UiText? = null,
    val isLoading: Boolean = true,
    /** Active conversation, or null in in-memory mode / before creation. */
    val conversationId: String? = null,
    /** Title shown in the top bar; null falls back to the generic title. */
    val conversationTitle: String? = null,
    /**
     * The default title given at creation (localized by the screen).
     * The conversation is renamed from the first user message only
     * while [conversationTitle] still equals this.
     */
    val defaultConversationTitle: String? = null,
) {
    /** Send is allowed only with a keyed config, a model, and non-blank input. */
    val canSend: Boolean
        get() = !generating &&
            selectedConfigId != null &&
            keyReady[selectedConfigId] == true &&
            selectedModelId != null &&
            input.isNotBlank()
}

/**
 * Backs the chat screen.
 *
 * Streams completions from [ChatRepository] into [uiState.messages].
 * All assistant text comes from the real stream ([ChatEvent.Delta] /
 * [ChatEvent.Done]) — nothing is ever fabricated. A failed or stopped
 * stream keeps its partial text marked pending ([ChatUiMessage.isPending])
 * so [retry] can drop it and resend.
 *
 * Conversation history (Milestone 3E): when [conversationRepository] is
 * present and a conversation is active, every turn is persisted —
 * the user message on [send], the assistant message on the terminal
 * stream event ([ChatEvent.Done] as complete, [ChatEvent.Error] / [stop]
 * as partial). The UI message is appended only after its row is stored,
 * so the screen never shows a turn that is not in history. A failed
 * persistence write surfaces as an error banner and aborts the send;
 * nothing is silently dropped.
 *
 * When [conversationRepository] is null the ViewModel runs in-memory
 * (used by unit tests and as a degradation path).
 *
 * No Android dependencies — JVM-unit-testable. Errors surface as
 * [UiText] in state (banner), never as raw throwables.
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val configRepository: ProviderConfigRepository,
    private val credentialStore: CredentialStore,
    private val conversationRepository: ConversationRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            configRepository.configs().collect { configs ->
                val ready = configs.associate { config ->
                    config.id to isKeyStored(config.id)
                }
                _uiState.update { state ->
                    var next = state.copy(configs = configs, keyReady = ready, isLoading = false)
                    if (next.selectedConfigId !in configs.map { it.id }) {
                        val first = configs.firstOrNull { ready[it.id] == true }
                        next = next.copy(
                            selectedConfigId = first?.id,
                            selectedModelId = first?.let { defaultModelFor(it) },
                            error = null,
                        )
                    }
                    next
                }
            }
        }
    }

    private suspend fun isKeyStored(configId: String): Boolean =
        (credentialStore.load(configId) as? Result.Success)?.data != null

    private fun defaultModelFor(config: ProviderConfig): String? =
        config.defaultModelId ?: config.modelIds.firstOrNull()

    /** Updates the message input field. */
    fun updateInput(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    /** Selects a provider config; the model resets to its default. */
    fun selectConfig(configId: String) {
        val config = _uiState.value.configs.firstOrNull { it.id == configId } ?: return
        _uiState.update {
            it.copy(
                selectedConfigId = configId,
                selectedModelId = defaultModelFor(config),
                error = null,
            )
        }
    }

    /** Selects the model for the current config. */
    fun selectModel(modelId: String) {
        _uiState.update { it.copy(selectedModelId = modelId, error = null) }
    }

    /**
     * Loads an existing conversation's history into the UI.
     *
     * Any in-flight stream is stopped first (its partial text is kept in
     * the previous conversation's history). [defaultTitle] is the
     * localized default title, used to detect a never-renamed
     * conversation on the first send.
     */
    fun loadConversation(id: String, defaultTitle: String) {
        stop()
        val repository = conversationRepository
        _uiState.update {
            it.copy(
                conversationId = null,
                conversationTitle = null,
                defaultConversationTitle = defaultTitle,
                messages = emptyList(),
                input = "",
                generating = false,
                error = null,
            )
        }
        if (repository == null) return
        viewModelScope.launch {
            val conversation = repository.get(id)
            if (conversation == null) {
                _uiState.update {
                    it.copy(error = UiText.Res(R.string.conversations_not_found))
                }
                return@launch
            }
            val stored = repository.messages(id).first()
            _uiState.update {
                it.copy(
                    conversationId = id,
                    conversationTitle = conversation.title,
                    messages = stored.map { message ->
                        ChatUiMessage(
                            role = message.role,
                            text = message.content,
                            isPending = message.isPartial,
                        )
                    },
                )
            }
        }
    }

    /**
     * Starts a fresh conversation, creating its history row eagerly with
     * [defaultTitle] (localized by the caller — ViewModels never resolve
     * resources). The title is replaced by the first user message's
     * leading text on the first [send].
     *
     * Any in-flight stream is stopped first (its partial text is kept in
     * the previous conversation's history).
     */
    fun newConversation(defaultTitle: String) {
        stop()
        val repository = conversationRepository
        if (repository == null) {
            resetForNewConversation(defaultTitle, null, null)
            return
        }
        viewModelScope.launch {
            when (val result = repository.create(defaultTitle)) {
                is Result.Success ->
                    resetForNewConversation(
                        defaultTitle,
                        result.data.id,
                        result.data.title,
                    )
                is Result.Error ->
                    _uiState.update {
                        it.copy(
                            conversationId = null,
                            conversationTitle = null,
                            defaultConversationTitle = defaultTitle,
                            messages = emptyList(),
                            input = "",
                            generating = false,
                            error = UiText.Res(R.string.conversations_create_error),
                        )
                    }
                // Repository writes never return Loading; defensive.
                is Result.Loading -> Unit
            }
        }
    }

    private fun resetForNewConversation(
        defaultTitle: String,
        conversationId: String?,
        conversationTitle: String?,
    ) {
        _uiState.update {
            it.copy(
                conversationId = conversationId,
                conversationTitle = conversationTitle,
                defaultConversationTitle = defaultTitle,
                messages = emptyList(),
                input = "",
                generating = false,
                error = null,
            )
        }
    }

    /**
     * Deletes the active conversation when it holds no messages.
     *
     * Called when leaving the chat screen: a conversation row created
     * for a chat the user never started would otherwise linger as an
     * empty entry in the list. Conversations with any message are kept.
     */
    fun discardIfEmpty() {
        val conversationId = _uiState.value.conversationId ?: return
        val repository = conversationRepository ?: return
        if (_uiState.value.messages.isNotEmpty()) return
        viewModelScope.launch {
            repository.delete(conversationId)
            _uiState.update { it.copy(conversationId = null, conversationTitle = null) }
        }
    }

    /**
     * Sends the current input.
     *
     * Guarded by [ChatUiState.canSend]; a second call while [generating]
     * is ignored (returns null). With an active conversation the user
     * turn is persisted before the stream starts, and the conversation
     * is created first when needed; a failed write aborts the send with
     * an error banner.
     *
     * Returns the launched [Job]. Joining it waits until persistence is
     * done and the stream has STARTED — not until the stream completes;
     * the stream itself runs in a child job (tests use
     * `advanceUntilIdle()` for full completion).
     */
    fun send(): Job? {
        val state = _uiState.value
        if (!state.canSend) return null
        val userText = state.input.trim()
        if (userText.isBlank()) return null
        return viewModelScope.launch {
            val repository = conversationRepository
            val conversationId = if (repository == null) {
                null // In-memory mode: no persistence.
            } else {
                ensureConversation() ?: return@launch
            }
            if (conversationId != null && !persistUserMessage(conversationId, userText)) {
                return@launch
            }
            if (conversationId != null) {
                renameFromFirstMessage(conversationId, userText)
            }
            _uiState.update {
                it.copy(
                    input = "",
                    messages = it.messages +
                        ChatUiMessage(ChatRole.USER, userText, isPending = false),
                )
            }
            startStream(_uiState.value.messages)
        }
    }

    /**
     * Resends the last user message, dropping any trailing pending
     * (failed or stopped) assistant turn first. No-op while generating
     * or when there is no user message to resend.
     *
     * The user turn was already persisted by the original [send], so
     * only the new assistant turn is persisted (on its terminal event).
     * A previously persisted partial stays in history marked partial —
     * the retry appends a new row rather than rewriting history.
     */
    fun retry(): Job? {
        val state = _uiState.value
        if (state.generating) return null
        val trimmed = state.messages.dropLastWhile {
            it.role == ChatRole.ASSISTANT && it.isPending
        }
        if (trimmed.none { it.role == ChatRole.USER }) return null
        return startStream(trimmed)
    }

    /**
     * Cancels the in-flight stream. The partial assistant message stays
     * in the list, still marked pending — the text received so far is
     * real stream output and is not discarded. With an active
     * conversation the partial text is persisted as a partial message.
     */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        persistPartialNow()
        _uiState.update { it.copy(generating = false) }
    }

    /** Dismisses the error banner. */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Returns the active conversation id, creating the row when
     * [newConversation]/[loadConversation] has not produced one yet
     * (e.g. creation failed earlier and the user retries the send).
     * Returns null when persistence is unavailable ([conversationRepository]
     * is null) or when creation failed — the error banner is already set
     * in the failure case, and the caller must abort the send.
     */
    private suspend fun ensureConversation(): String? {
        _uiState.value.conversationId?.let { return it }
        val repository = conversationRepository ?: return null
        val defaultTitle = _uiState.value.defaultConversationTitle
        if (defaultTitle == null) {
            _uiState.update {
                it.copy(error = UiText.Res(R.string.conversations_create_error))
            }
            return null
        }
        return when (val result = repository.create(defaultTitle)) {
            is Result.Success -> {
                _uiState.update {
                    it.copy(
                        conversationId = result.data.id,
                        conversationTitle = result.data.title,
                    )
                }
                result.data.id
            }
            is Result.Error -> {
                _uiState.update {
                    it.copy(error = UiText.Res(R.string.conversations_create_error))
                }
                null
            }
            // Repository writes never return Loading; defensive.
            is Result.Loading -> null
        }
    }

    /**
     * Persists the user turn. Returns false when the write failed (the
     * error banner is set); the caller must abort the send so the UI
     * never shows a turn that is not in history.
     */
    private suspend fun persistUserMessage(conversationId: String, userText: String): Boolean {
        val repository = conversationRepository ?: return true
        return when (
            repository.appendMessage(
                conversationId,
                ChatRole.USER,
                userText,
                isPartial = false,
            )
        ) {
            is Result.Success -> true
            is Result.Error -> {
                _uiState.update {
                    it.copy(error = UiText.Res(R.string.conversations_save_error))
                }
                false
            }
            // Repository writes never return Loading; defensive.
            is Result.Loading -> false
        }
    }

    /**
     * Replaces the default title with the first user message's leading
     * text ([TITLE_MAX_CHARS] chars), but only when the conversation
     * still carries its default title and has no user message yet.
     * A failed rename is cosmetic — the send still proceeds.
     */
    private suspend fun renameFromFirstMessage(conversationId: String, userText: String) {
        val repository = conversationRepository ?: return
        val state = _uiState.value
        val defaultTitle = state.defaultConversationTitle ?: return
        if (state.conversationTitle != defaultTitle) return
        if (state.messages.any { it.role == ChatRole.USER }) return
        val title = userText.take(TITLE_MAX_CHARS)
        if (title.isBlank()) return
        if (repository.rename(conversationId, title) is Result.Success) {
            _uiState.update { it.copy(conversationTitle = title) }
        }
    }

    /**
     * Persists the trailing pending assistant turn's current text as a
     * partial message, if there is an active conversation and non-empty
     * text. Fire-and-forget: a failed write surfaces as an error banner.
     */
    private fun persistPartialNow() {
        val conversationId = _uiState.value.conversationId ?: return
        val repository = conversationRepository ?: return
        val partial = _uiState.value.messages
            .lastOrNull { it.role == ChatRole.ASSISTANT && it.isPending }
            ?.text.orEmpty()
        if (partial.isEmpty()) return
        viewModelScope.launch {
            if (repository.appendMessage(
                    conversationId,
                    ChatRole.ASSISTANT,
                    partial,
                    isPartial = true,
                ) is Result.Error
            ) {
                _uiState.update {
                    it.copy(error = UiText.Res(R.string.conversations_save_error))
                }
            }
        }
    }

    /**
     * Appends a pending assistant turn to [baseMessages] and collects
     * the repository stream into it.
     */
    private fun startStream(baseMessages: List<ChatUiMessage>): Job? {
        val state = _uiState.value
        val configId = state.selectedConfigId ?: return null
        val modelId = state.selectedModelId ?: return null
        if (state.keyReady[configId] != true) return null
        val requestMessages = baseMessages.map { ChatMessage(it.role, it.text) }
        _uiState.update {
            it.copy(
                messages = baseMessages + ChatUiMessage(ChatRole.ASSISTANT, "", isPending = true),
                generating = true,
                error = null,
            )
        }
        val job = viewModelScope.launch {
            chatRepository.streamChat(ChatRequest(configId, modelId, requestMessages))
                .collect { event ->
                    when (event) {
                        is ChatEvent.Delta -> appendDelta(event.text)
                        is ChatEvent.Done -> finalizeAssistant(event.fullText)
                        is ChatEvent.Error -> failStream(event)
                    }
                }
        }
        streamJob?.cancel()
        streamJob = job
        return job
    }

    private fun appendDelta(text: String) {
        _uiState.update { state ->
            state.copy(messages = updatePendingAssistant(state.messages) {
                it.copy(text = it.text + text)
            })
        }
    }

    /**
     * [ChatEvent.Done.fullText] is authoritative for the finished turn.
     * The row is stored before the UI flips to non-pending so history
     * and screen can never disagree; a failed write keeps the banner
     * (the streamed text itself is still shown — it is real output).
     */
    private suspend fun finalizeAssistant(fullText: String) {
        val conversationId = _uiState.value.conversationId
        val repository = conversationRepository
        if (conversationId != null && repository != null) {
            if (repository.appendMessage(
                    conversationId,
                    ChatRole.ASSISTANT,
                    fullText,
                    isPartial = false,
                ) is Result.Error
            ) {
                _uiState.update {
                    it.copy(error = UiText.Res(R.string.conversations_save_error))
                }
            }
        }
        _uiState.update { state ->
            state.copy(
                messages = updatePendingAssistant(state.messages) {
                    it.copy(text = fullText, isPending = false)
                },
                generating = false,
            )
        }
    }

    /**
     * Keeps the partial text marked pending; the banner carries the
     * cause. The partial text is persisted as a partial message when a
     * conversation is active. A persistence failure here does not
     * replace the stream error — the stream failure is the actionable
     * one.
     */
    private suspend fun failStream(event: ChatEvent.Error) {
        val conversationId = _uiState.value.conversationId
        val repository = conversationRepository
        val partial = _uiState.value.messages
            .lastOrNull { it.role == ChatRole.ASSISTANT && it.isPending }
            ?.text.orEmpty()
        if (conversationId != null && repository != null && partial.isNotEmpty()) {
            repository.appendMessage(
                conversationId,
                ChatRole.ASSISTANT,
                partial,
                isPartial = true,
            )
        }
        _uiState.update { state ->
            state.copy(error = event.error.userMessage(), generating = false)
        }
    }

    /** Applies [transform] to the trailing pending assistant turn, if any. */
    private fun updatePendingAssistant(
        messages: List<ChatUiMessage>,
        transform: (ChatUiMessage) -> ChatUiMessage,
    ): List<ChatUiMessage> {
        val index = messages.indexOfLast { it.role == ChatRole.ASSISTANT && it.isPending }
        if (index < 0) return messages
        return messages.toMutableList().also { it[index] = transform(it[index]) }
    }

    companion object {
        /** Conversation titles are derived from the first 40 chars. */
        private const val TITLE_MAX_CHARS = 40
    }
}
