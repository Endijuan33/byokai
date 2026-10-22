package com.byokai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byokai.R
import com.byokai.core.chat.ContextPolicy
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRepository
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.Message
import com.byokai.domain.model.PromptTemplate
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.repository.ConversationRepository
import com.byokai.domain.repository.PromptRepository
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialStore
import com.byokai.ui.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One visible chat turn.
 *
 * @property id the persisted [com.byokai.domain.model.Message.id] when
 * this turn is stored in history, null for turns not yet persisted
 * (the in-flight assistant turn, or every turn in in-memory mode).
 * Used to target message actions (regenerate, edit) at the right row.
 * @property isPending true while the turn is still streaming (or was
 * interrupted by an error / stop); false once the terminal [ChatEvent]
 * for it has been processed.
 */
data class ChatUiMessage(
    val role: ChatRole,
    val text: String,
    val isPending: Boolean,
    val id: String? = null,
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
     * System prompt of the active conversation (Milestone 4C); null
     * when unset. Sent to the provider through the adapter's
     * system-prompt channel — never as a user message.
     */
    val systemPrompt: String? = null,
    /** Prompt templates offered in the system-prompt picker dialog. */
    val prompts: List<PromptTemplate> = emptyList(),
    /** Whether the system-prompt picker dialog is open. */
    val systemPromptDialogOpen: Boolean = false,
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
    private val promptRepository: PromptRepository? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /**
     * The planned request context (Milestone 4D): which messages will
     * actually be sent next, how many of the oldest were truncated, and
     * the heuristic token ESTIMATE (labeled "est." in the UI — never an
     * official provider count).
     *
     * Derived from [uiState], so the label tracks the conversation as
     * it grows. Truncation here mirrors exactly what [startStream]
     * sends; persisted history is never affected.
     */
    val contextPlan: StateFlow<ContextPolicy.Plan?> = uiState
        .map { state ->
            if (state.messages.isEmpty() && state.systemPrompt.isNullOrBlank()) {
                null
            } else {
                ContextPolicy.plan(
                    messages = state.messages.map { ChatMessage(it.role, it.text) },
                    systemPrompt = state.systemPrompt,
                    // Adapters do not report context windows yet
                    // (ModelInfo.contextWindow is null); the policy's
                    // conservative fallback caps apply.
                    contextWindow = null,
                )
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var streamJob: Job? = null

    /**
     * The in-flight partial-turn persist started by [stop], if any.
     * [send] joins it before persisting the new user turn so the
     * database keeps turn order across a stop/send sequence.
     */
    private var partialPersistJob: Job? = null

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
        // Prompt templates for the per-conversation system-prompt
        // picker (Milestone 4C). Seeded here as well as in the prompts
        // screen so the picker has builtins even when the user never
        // opened the management screen.
        viewModelScope.launch {
            promptRepository?.ensureSeeded()
            promptRepository?.prompts()?.collect { prompts ->
                _uiState.update { it.copy(prompts = prompts) }
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

    /**
     * Selects a provider config; the model resets to its default.
     *
     * An in-flight stream is stopped first: it belongs to the previous
     * config, and continuing it while the UI shows the new selection
     * would be misleading. The partial text is kept (persisted when a
     * conversation is active).
     */
    fun selectConfig(configId: String) {
        if (_uiState.value.selectedConfigId == configId) return
        val config = _uiState.value.configs.firstOrNull { it.id == configId } ?: return
        stop()
        _uiState.update {
            it.copy(
                selectedConfigId = configId,
                selectedModelId = defaultModelFor(config),
                error = null,
            )
        }
    }

    /**
     * Selects the model for the current config.
     *
     * Stops an in-flight stream first, for the same reason as
     * [selectConfig]: the stream in flight was requested with the
     * previous model.
     */
    fun selectModel(modelId: String) {
        if (_uiState.value.selectedModelId == modelId) return
        stop()
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
                    systemPrompt = conversation.systemPrompt,
                    systemPromptDialogOpen = false,
                    messages = stored.map { message ->
                        ChatUiMessage(
                            role = message.role,
                            text = message.content,
                            isPending = message.isPartial,
                            id = message.id,
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
                systemPrompt = null,
                systemPromptDialogOpen = false,
                messages = emptyList(),
                input = "",
                generating = false,
                error = null,
            )
        }
    }

    /** Opens the per-conversation system-prompt picker dialog. */
    fun openSystemPromptDialog() {
        _uiState.update { it.copy(systemPromptDialogOpen = true) }
    }

    /** Closes the system-prompt picker without saving. */
    fun closeSystemPromptDialog() {
        _uiState.update { it.copy(systemPromptDialogOpen = false) }
    }

    /**
     * Sets the active conversation's system prompt (null clears it).
     * Persisted via [ConversationRepository.setSystemPrompt]; the next
     * stream sends it through the adapter's system-prompt channel.
     */
    fun setSystemPrompt(systemPrompt: String?) {
        val conversationId = _uiState.value.conversationId ?: return
        val repository = conversationRepository ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(systemPromptDialogOpen = false) }
            when (repository.setSystemPrompt(conversationId, systemPrompt?.ifBlank { null })) {
                is Result.Success ->
                    _uiState.update { it.copy(systemPrompt = systemPrompt?.ifBlank { null }) }
                is Result.Error ->
                    _uiState.update { it.copy(error = UiText.Res(R.string.conversations_save_error)) }
                is Result.Loading -> Unit
            }
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
     * The send is claimed synchronously (compare-and-set on
     * [ChatUiState.canSend]): a second call — e.g. a fast double tap —
     * cannot slip through before the coroutine starts, so the user turn
     * is persisted exactly once and only one stream starts. A failed
     * claim returns null. The shared [sendPipeline] does the actual work.
     *
     * Returns the launched [Job]. Joining it waits until persistence is
     * done and the stream has STARTED — not until the stream completes;
     * the stream itself runs in a child job (tests use
     * `advanceUntilIdle()` for full completion).
     */
    fun send(): Job? {
        val userText = _uiState.value.input.trim()
        if (userText.isBlank()) return null
        if (!claimSend()) return null
        _uiState.update { it.copy(input = "") }
        return viewModelScope.launch { sendPipeline(userText) }
    }

    /**
     * Synchronously claims a full send (compare-and-set on
     * [ChatUiState.canSend] — keyed config, model, non-blank input, and
     * no in-flight stream). Used by [send]; a failed claim returns
     * false and the caller must do nothing (Fase 5 invariant).
     */
    private fun claimSend(): Boolean {
        var claimed = false
        _uiState.update { state ->
            if (state.canSend) {
                claimed = true
                state.copy(generating = true)
            } else {
                state
            }
        }
        return claimed
    }

    /**
     * Synchronously claims the generation slot (compare-and-set on
     * [ChatUiState.generating]). Returns false when a stream is already
     * running — the caller must do nothing. Every entry point that
     * starts a stream ([retry], [regenerate], [editAndResend]) claims
     * first ([send] claims via [claimSend]), so two triggers can never
     * start two streams for one action (Fase 5 invariant).
     */
    private fun claimGeneration(): Boolean {
        var claimed = false
        _uiState.update { state ->
            if (!state.generating) {
                claimed = true
                state.copy(generating = true)
            } else {
                state
            }
        }
        return claimed
    }

    /**
     * The shared send body: join any in-flight partial persist (Fase 5
     * ordering), ensure/persist the user turn, then stream. The caller
     * must have claimed the generation slot and prepared
     * [ChatUiState.messages] (fresh input for [send], truncated history
     * for [editAndResend]).
     */
    private suspend fun sendPipeline(userText: String) {
        // A stop() immediately before this send may still be
        // persisting its partial turn; wait for it so the database
        // keeps turn order (partial before the new user turn).
        partialPersistJob?.join()
        partialPersistJob = null
        val repository = conversationRepository
        val conversationId = if (repository == null) {
            null // In-memory mode: no persistence.
        } else {
            ensureConversation() ?: run {
                _uiState.update { it.copy(generating = false) }
                return
            }
        }
        val persisted = if (conversationId != null) {
            when (val result = persistUserMessage(conversationId, userText)) {
                is Result.Success -> result.data
                is Result.Error -> {
                    _uiState.update { it.copy(generating = false) }
                    return
                }
                // Repository writes never return Loading; defensive.
                is Result.Loading -> return
            }
        } else {
            null
        }
        if (conversationId != null) {
            renameFromFirstMessage(conversationId, userText)
        }
        _uiState.update {
            it.copy(
                messages = it.messages +
                    ChatUiMessage(
                        ChatRole.USER,
                        userText,
                        isPending = false,
                        id = persisted?.id,
                    ),
            )
        }
        startStream(_uiState.value.messages)
    }

    /**
     * Regenerates the trailing assistant turn: deletes it from history
     * and the screen, then streams a fresh response for the same
     * history. The user turn is NOT duplicated — it was persisted by
     * the original send and stays in history.
     *
     * Only the last message can be regenerated, only while idle, and
     * only when it is an assistant turn. A failed stream keeps its
     * partial text marked pending (see [retry] for that path).
     */
    fun regenerate(message: ChatUiMessage): Job? {
        val state = _uiState.value
        if (state.generating) return null
        val last = state.messages.lastOrNull() ?: return null
        if (last.role != ChatRole.ASSISTANT) return null
        if (!isSameUiMessage(last, message)) return null
        if (!claimGeneration()) return null
        return viewModelScope.launch {
            partialPersistJob?.join()
            partialPersistJob = null
            val repository = conversationRepository
            val conversationId = state.conversationId
            if (repository != null && conversationId != null && last.id != null) {
                if (repository.deleteMessagesFrom(conversationId, last.id)
                    is Result.Error
                ) {
                    _uiState.update {
                        it.copy(
                            generating = false,
                            error = UiText.Res(R.string.conversations_save_error),
                        )
                    }
                    return@launch
                }
            }
            val truncated = _uiState.value.messages.dropLast(1)
            _uiState.update { it.copy(messages = truncated) }
            startStream(truncated) ?: run {
                _uiState.update { it.copy(generating = false) }
            }
        }
    }

    /**
     * Edits a user message: deletes it and every message after it from
     * history and the screen (single transaction in the repository —
     * never a partial truncation), then sends [newText] as a fresh
     * user turn through the normal [sendPipeline].
     *
     * Only while idle; blank text is a no-op.
     */
    fun editAndResend(message: ChatUiMessage, newText: String): Job? {
        val text = newText.trim()
        if (text.isBlank()) return null
        val state = _uiState.value
        if (state.generating) return null
        if (message.role != ChatRole.USER) return null
        val index = indexOfUiMessage(state.messages, message)
        if (index < 0) return null
        if (!claimGeneration()) return null
        return viewModelScope.launch {
            partialPersistJob?.join()
            partialPersistJob = null
            val repository = conversationRepository
            val conversationId = state.conversationId
            if (repository != null && conversationId != null && message.id != null) {
                if (repository.deleteMessagesFrom(conversationId, message.id)
                    is Result.Error
                ) {
                    _uiState.update {
                        it.copy(
                            generating = false,
                            error = UiText.Res(R.string.conversations_save_error),
                        )
                    }
                    return@launch
                }
            }
            _uiState.update { it.copy(messages = it.messages.take(index)) }
            sendPipeline(text)
        }
    }

    /**
     * Matches a [ChatUiMessage] against the current list: by stable
     * [ChatUiMessage.id] when present, by data-class equality otherwise
     * (in-memory mode, where nothing is persisted).
     */
    private fun isSameUiMessage(a: ChatUiMessage, b: ChatUiMessage): Boolean =
        if (a.id != null && b.id != null) a.id == b.id else a == b

    /** Index of [message] in [messages], or -1 when absent. */
    private fun indexOfUiMessage(
        messages: List<ChatUiMessage>,
        message: ChatUiMessage,
    ): Int {
        if (message.id != null) {
            val byId = messages.indexOfFirst { it.id == message.id }
            if (byId >= 0) return byId
        }
        return messages.indexOf(message)
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
        if (!claimGeneration()) return null
        val trimmed = _uiState.value.messages.dropLastWhile {
            it.role == ChatRole.ASSISTANT && it.isPending
        }
        if (trimmed.none { it.role == ChatRole.USER }) {
            _uiState.update { it.copy(generating = false) }
            return null
        }
        return startStream(trimmed) ?: run {
            _uiState.update { it.copy(generating = false) }
            null
        }
    }

    /**
     * Cancels the in-flight stream. The partial assistant message stays
     * in the list, still marked pending — the text received so far is
     * real stream output and is not discarded. With an active
     * conversation the partial text is persisted as a partial message;
     * the persist job is tracked so a subsequent [send] can wait for it
     * and keep database turn order.
     */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        partialPersistJob = persistPartialNow()
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
     * Persists the user turn. Returns the stored row on success; a
     * failed write sets the error banner and returns [Result.Error] so
     * the caller aborts the send — the UI never shows a turn that is
     * not in history.
     */
    private suspend fun persistUserMessage(
        conversationId: String,
        userText: String,
    ): Result<Message> {
        val repository = conversationRepository ?: return Result.Error(
            IllegalStateException("no conversation repository"),
        )
        return when (
            val result = repository.appendMessage(
                conversationId,
                ChatRole.USER,
                userText,
                isPartial = false,
            )
        ) {
            is Result.Success -> result
            is Result.Error -> {
                _uiState.update {
                    it.copy(error = UiText.Res(R.string.conversations_save_error))
                }
                result
            }
            // Repository writes never return Loading; defensive.
            is Result.Loading -> result
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
     * text. Returns the launched [Job], or null when there is nothing to
     * persist. A failed write surfaces as an error banner.
     */
    private fun persistPartialNow(): Job? {
        val conversationId = _uiState.value.conversationId ?: return null
        val repository = conversationRepository ?: return null
        val partial = _uiState.value.messages
            .lastOrNull { it.role == ChatRole.ASSISTANT && it.isPending }
            ?.text.orEmpty()
        if (partial.isEmpty()) return null
        return viewModelScope.launch {
            val persistedId = when (
                val result = repository.appendMessage(
                    conversationId,
                    ChatRole.ASSISTANT,
                    partial,
                    isPartial = true,
                )
            ) {
                is Result.Success -> result.data.id
                is Result.Error -> {
                    _uiState.update {
                        it.copy(error = UiText.Res(R.string.conversations_save_error))
                    }
                    null
                }
                // Repository writes never return Loading; defensive.
                is Result.Loading -> null
            }
            if (persistedId != null) {
                _uiState.update { state ->
                    state.copy(
                        messages = updatePendingAssistant(state.messages) {
                            it.copy(id = persistedId)
                        },
                    )
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
        // Request-side truncation only (Milestone 4D): the persisted
        // history keeps every turn; the provider receives the newest
        // messages that fit the context budget.
        val plan = ContextPolicy.plan(
            messages = requestMessages,
            systemPrompt = state.systemPrompt,
            // Adapters do not report context windows yet
            // (ModelInfo.contextWindow is null); the policy's
            // conservative fallback caps apply.
            contextWindow = null,
        )
        _uiState.update {
            it.copy(
                messages = baseMessages + ChatUiMessage(ChatRole.ASSISTANT, "", isPending = true),
                generating = true,
                error = null,
            )
        }
        val job = viewModelScope.launch {
            chatRepository.streamChat(
                ChatRequest(
                    providerConfigId = configId,
                    modelId = modelId,
                    messages = plan.messages,
                    systemPrompt = state.systemPrompt,
                ),
            )
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
        val persistedId: String? =
            if (conversationId != null && repository != null) {
                when (
                    val result = repository.appendMessage(
                        conversationId,
                        ChatRole.ASSISTANT,
                        fullText,
                        isPartial = false,
                    )
                ) {
                    is Result.Success -> result.data.id
                    is Result.Error -> {
                        _uiState.update {
                            it.copy(error = UiText.Res(R.string.conversations_save_error))
                        }
                        null
                    }
                    // Repository writes never return Loading; defensive.
                    is Result.Loading -> null
                }
            } else {
                null
            }
        _uiState.update { state ->
            state.copy(
                messages = updatePendingAssistant(state.messages) {
                    it.copy(
                        text = fullText,
                        isPending = false,
                        id = persistedId ?: it.id,
                    )
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
            val persistedId = when (
                val result = repository.appendMessage(
                    conversationId,
                    ChatRole.ASSISTANT,
                    partial,
                    isPartial = true,
                )
            ) {
                is Result.Success -> result.data.id
                else -> null
            }
            if (persistedId != null) {
                _uiState.update { state ->
                    state.copy(
                        messages = updatePendingAssistant(state.messages) {
                            it.copy(id = persistedId)
                        },
                    )
                }
            }
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
