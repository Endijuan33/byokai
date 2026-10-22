package com.byokai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.byokai.R
import com.byokai.core.chat.ContextPolicy
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.data.local.ImageProcessor
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRepository
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.model.Message
import com.byokai.domain.model.PromptTemplate
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.repository.AttachmentStorage
import com.byokai.domain.repository.ConversationRepository
import com.byokai.domain.repository.PromptRepository
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialStore
import com.byokai.ui.common.userMessage
import java.io.File
import java.util.UUID
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
    /**
     * Image attachments sent with this turn (Phase 9B). Populated for
     * freshly sent user turns AND for history rows loaded from storage
     * (attachments persist in the `attachments` table since schema v3).
     */
    val attachments: List<ImageAttachment> = emptyList(),
)

/**
 * Voice-input state (Phase 9B). Driven by [ChatViewModel.startVoiceInput]
 * and the [VoiceInputController] callbacks.
 */
sealed interface VoiceState {
    /** Not listening. */
    data object Idle : VoiceState

    /** The recognizer is listening for speech. */
    data object Listening : VoiceState

    /** Recognition failed; [message] is user-facing and localized. */
    data class Error(val message: UiText) : VoiceState
}

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
    /**
     * Image attachments staged for the next send (Phase 9B). Shown in
     * the preview strip above the input row; claimed by [send] and
     * attached to the user turn.
     */
    val pendingAttachments: List<ImageAttachment> = emptyList(),
    /** Voice-input state (Phase 9B); see [VoiceState]. */
    val voiceState: VoiceState = VoiceState.Idle,
) {
    /**
     * Send is allowed with a keyed config, a model, and either
     * non-blank input or at least one staged image attachment.
     */
    val canSend: Boolean
        get() = !generating &&
            selectedConfigId != null &&
            keyReady[selectedConfigId] == true &&
            selectedModelId != null &&
            (input.isNotBlank() || pendingAttachments.isNotEmpty())
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
 * Multimodal (Phase 9B): image attachments are staged in
 * [ChatUiState.pendingAttachments] and claimed by [send] into the user
 * turn's [ChatUiMessage.attachments]. Image bytes never enter the
 * ViewModel — [ImageProcessor] and [AttachmentStorage] own all I/O, and
 * the screen resolves thumbnails through [thumbnailFileFor]. The
 * [android.net.Uri] parameters are opaque tokens from the
 * activity-result contracts; no Android framework methods are invoked
 * on them here, so the pure state transitions (voice, attachment list,
 * send claiming) stay JVM-unit-testable.
 *
 * No Android dependencies — JVM-unit-testable. Errors surface as
 * [UiText] in state (banner), never as raw throwables.
 *
 * @param attachmentStorage nullable until the coordinator wires it in
 * AppContainer (Phase 9B integration); image features degrade to no-ops
 * with a clear [IllegalStateException] only where a file is mandatory
 * ([prepareCameraFile], [thumbnailFileFor]).
 * @param imageProcessor nullable for the same reason; [onImagePicked]
 * is a no-op without it.
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val configRepository: ProviderConfigRepository,
    private val credentialStore: CredentialStore,
    private val conversationRepository: ConversationRepository? = null,
    private val promptRepository: PromptRepository? = null,
    private val attachmentStorage: AttachmentStorage? = null,
    private val imageProcessor: ImageProcessor? = null,
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
                    messages = state.messages.map {
                        // Named args: immune to Worker A's parameter order
                        // for the new attachments field (defaults to empty).
                        ChatMessage(role = it.role, content = it.text)
                    },
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
     * The [VoiceInputController] currently driving [ChatUiState.voiceState],
     * set by [startVoiceInput]. Owned by the screen (which creates it);
     * released in [onCleared] as a defensive fallback.
     */
    private var voiceController: VoiceInputController? = null

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
        clearPendingAttachments(deleteFiles = true)
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
                            attachments = message.attachments,
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
        clearPendingAttachments(deleteFiles = true)
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
     * Phase 9B: staged [ChatUiState.pendingAttachments] are claimed
     * together with the input — the strip is cleared at claim time and
     * the attachments travel on the user turn ([ChatUiMessage.attachments]
     * → [ChatMessage.attachments]). If the send fails before the user
     * turn is persisted, the attachments are restored to the strip
     * (their files are untouched); files are deleted only by explicit
     * [removePendingAttachment] or conversation teardown, never as a
     * side effect of a failed send.
     *
     * Returns the launched [Job]. Joining it waits until persistence is
     * done and the stream has STARTED — not until the stream completes;
     * the stream itself runs in a child job (tests use
     * `advanceUntilIdle()` for full completion).
     */
    fun send(): Job? {
        val userText = _uiState.value.input.trim()
        val attachments = _uiState.value.pendingAttachments
        if (userText.isBlank() && attachments.isEmpty()) return null
        if (!claimSend()) return null
        _uiState.update { it.copy(input = "", pendingAttachments = emptyList()) }
        return viewModelScope.launch { sendPipeline(userText, attachments) }
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
     *
     * [attachments] were claimed from the strip by the caller; they are
     * restored to the strip when the send fails before persistence.
     */
    private suspend fun sendPipeline(userText: String, attachments: List<ImageAttachment>) {
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
                restoreAttachmentsOnFailedSend(attachments)
                _uiState.update { it.copy(generating = false) }
                return
            }
        }
        val persisted = if (conversationId != null) {
            when (val result = persistUserMessage(conversationId, userText, attachments)) {
                is Result.Success -> result.data
                is Result.Error -> {
                    restoreAttachmentsOnFailedSend(attachments)
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
                        attachments = attachments,
                    ),
            )
        }
        startStream(_uiState.value.messages)
    }

    /**
     * Puts claimed attachments back on the strip after a send that
     * failed before the user turn was persisted. Their files are
     * untouched — deletion happens only on explicit remove.
     */
    private fun restoreAttachmentsOnFailedSend(attachments: List<ImageAttachment>) {
        if (attachments.isEmpty()) return
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + attachments) }
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
     * Phase 9B: attachments on the edited turn are dropped with it
     * (their files are deleted by the truncation); re-attach them if
     * they are still needed.
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
            sendPipeline(text, emptyList())
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

    // ------------------------------------------------------------------
    // Phase 9B: image attachments.
    // ------------------------------------------------------------------

    /**
     * Processes a gallery-picked image into a staged attachment.
     *
     * Runs [ImageProcessor.process] off the caller and appends the
     * result to [ChatUiState.pendingAttachments]; failures surface on
     * the existing error path ([R.string.image_process_error]). No-op
     * when [imageProcessor] is not wired yet.
     */
    fun onImagePicked(uri: Uri) {
        val processor = imageProcessor ?: return
        viewModelScope.launch {
            try {
                val attachment = processor.process(uri)
                _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiText.Res(R.string.image_process_error)) }
            }
        }
    }

    /**
     * Handles a successful camera capture.
     *
     * The full-res [tempFile] is the TakePicture destination; it is
     * processed through the same pipeline as a gallery pick, then
     * deleted best-effort — the downscaled copy in [AttachmentStorage]
     * is the attachment that survives. Failures surface on the existing
     * error path; the temp file cleanup still runs.
     */
    fun onCameraCaptured(uri: Uri, tempFile: File) {
        val processor = imageProcessor ?: return
        viewModelScope.launch {
            try {
                val attachment = processor.process(uri)
                _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + attachment) }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = UiText.Res(R.string.image_process_error)) }
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    /**
     * Reserves a destination file for the camera intent (TakePicture
     * contract needs the file BEFORE launch). The screen turns it into
     * a content URI via FileProvider.
     *
     * @throws IllegalStateException when [attachmentStorage] is not wired
     * (coordinator integration gap — fail fast, never a null file).
     */
    fun prepareCameraFile(): File {
        val storage = attachmentStorage
            ?: throw IllegalStateException("AttachmentStorage is not wired")
        return storage.fileFor("${UUID.randomUUID()}.jpg")
    }

    /**
     * Removes a staged attachment from the strip and deletes its file.
     * File deletion is the ONLY path that deletes attachment files
     * besides conversation teardown ([clearPendingAttachments]) — a
     * failed send never orphans files silently (see [send]).
     */
    fun removePendingAttachment(id: String) {
        val removed = _uiState.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments - removed) }
        val storage = attachmentStorage ?: return
        viewModelScope.launch {
            runCatching { storage.delete(removed.fileName) }
        }
    }

    /**
     * Resolves the on-disk file for an attachment's thumbnail. Used by
     * the screen only — never exposes bytes to the ViewModel.
     *
     * @throws IllegalStateException when [attachmentStorage] is not wired.
     */
    fun thumbnailFileFor(attachment: ImageAttachment): File {
        val storage = attachmentStorage
            ?: throw IllegalStateException("AttachmentStorage is not wired")
        return storage.fileFor(attachment.fileName)
    }

    /**
     * Drops all staged attachments from the strip. With [deleteFiles]
     * their files are deleted too (conversation teardown — the compose
     * context is abandoned, so keeping the files would orphan them).
     * A claimed send passes false and restores on failure instead.
     */
    private fun clearPendingAttachments(deleteFiles: Boolean) {
        val staged = _uiState.value.pendingAttachments
        if (staged.isEmpty()) return
        _uiState.update { it.copy(pendingAttachments = emptyList()) }
        if (!deleteFiles) return
        val storage = attachmentStorage ?: return
        viewModelScope.launch {
            staged.forEach { runCatching { storage.delete(it.fileName) } }
        }
    }

    // ------------------------------------------------------------------
    // Phase 9B: voice input.
    // ------------------------------------------------------------------

    /**
     * Starts voice input through [controller] (created and owned by the
     * screen; must be called on the main thread). The controller's
     * callbacks are wired to [onVoiceResult]/[onVoiceError]. No-op when
     * already listening.
     */
    fun startVoiceInput(controller: VoiceInputController) {
        if (_uiState.value.voiceState is VoiceState.Listening) return
        _uiState.update { it.copy(voiceState = VoiceState.Listening) }
        voiceController = controller
        controller.start(
            onResult = ::onVoiceResult,
            onError = ::onVoiceError,
        )
    }

    /** Stops listening; a final result may still arrive via [onVoiceResult]. */
    fun stopVoiceInput() {
        voiceController?.stop()
        voiceController = null
        _uiState.update { it.copy(voiceState = VoiceState.Idle) }
    }

    /**
     * Appends recognized text to the input, inserting a space when the
     * existing input does not already end with whitespace. Empty results
     * only reset the state. Called by [VoiceInputController].
     */
    fun onVoiceResult(text: String) {
        voiceController = null
        val trimmed = text.trim()
        _uiState.update { state ->
            val separator = when {
                trimmed.isEmpty() -> ""
                state.input.isEmpty() -> ""
                state.input.last().isWhitespace() -> ""
                else -> " "
            }
            state.copy(
                input = if (trimmed.isEmpty()) state.input else state.input + separator + trimmed,
                voiceState = VoiceState.Idle,
            )
        }
    }

    /** Surfaces a recognition failure; called by [VoiceInputController]. */
    fun onVoiceError(message: String) {
        voiceController = null
        _uiState.update { it.copy(voiceState = VoiceState.Error(UiText.Raw(message))) }
    }

    /** Dismisses the voice-error notice. */
    fun dismissVoiceError() {
        _uiState.update {
            if (it.voiceState is VoiceState.Error) it.copy(voiceState = VoiceState.Idle) else it
        }
    }

    override fun onCleared() {
        voiceController?.destroy()
        voiceController = null
        super.onCleared()
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
     * Persists the user turn together with its staged attachments.
     * Returns the stored row on success; a failed write sets the error
     * banner and returns [Result.Error] so the caller aborts the send —
     * the UI never shows a turn that is not in history.
     *
     * The attachment FILES already exist in [AttachmentStorage] (written
     * at pick time); persistence only adds their metadata rows, in the
     * same transaction as the message.
     */
    private suspend fun persistUserMessage(
        conversationId: String,
        userText: String,
        attachments: List<ImageAttachment>,
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
                attachments = attachments,
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
        // Phase 9B: attachments ride on ChatMessage.attachments so the
        // adapter layer (Worker B) can build the vision payload.
        val requestMessages = baseMessages.map {
            ChatMessage(role = it.role, content = it.text, attachments = it.attachments)
        }
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
