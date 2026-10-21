package com.byokai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRepository
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialStore
import com.byokai.ui.common.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Message history is in-memory only for this milestone; persistence
 * lands with conversation storage in milestone 3E.
 *
 * No Android dependencies — JVM-unit-testable. Errors surface as
 * [UiText] in state (banner), never as raw throwables.
 */
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val configRepository: ProviderConfigRepository,
    private val credentialStore: CredentialStore,
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
     * Sends the current input.
     *
     * Guarded by [ChatUiState.canSend]; a second call while [generating]
     * is ignored (returns null). Returns the launched [Job] so callers
     * (and tests) can await stream completion.
     */
    fun send(): Job? {
        val state = _uiState.value
        if (!state.canSend) return null
        val userText = state.input.trim()
        if (userText.isBlank()) return null
        _uiState.update { it.copy(input = "") }
        val withUser = state.messages + ChatUiMessage(ChatRole.USER, userText, isPending = false)
        return startStream(withUser)
    }

    /**
     * Resends the last user message, dropping any trailing pending
     * (failed or stopped) assistant turn first. No-op while generating
     * or when there is no user message to resend.
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
     * real stream output and is not discarded.
     */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        _uiState.update { it.copy(generating = false) }
    }

    /** Dismisses the error banner. */
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
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

    /** [ChatEvent.Done.fullText] is authoritative for the finished turn. */
    private fun finalizeAssistant(fullText: String) {
        _uiState.update { state ->
            state.copy(
                messages = updatePendingAssistant(state.messages) {
                    it.copy(text = fullText, isPending = false)
                },
                generating = false,
            )
        }
    }

    /** Keeps the partial text marked pending; the banner carries the cause. */
    private fun failStream(event: ChatEvent.Error) {
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
}
