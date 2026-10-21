package com.byokai.ui.providers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byokai.R
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.AiProvider
import com.byokai.domain.provider.ProviderRegistry
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialStore
import com.byokai.ui.common.toProviderUiText
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mutable form of a [ProviderConfig] being created or edited.
 *
 * The API key is deliberately NOT part of the draft: keystrokes stay in
 * the editor composable's local state and are handed to [saveDraft] or
 * [testDraft] only at the moment of the action, so a key never lingers
 * in ViewModel state. [hasSavedKey] only records WHETHER a key exists.
 */
data class ProviderConfigDraft(
    val id: String,
    val providerId: ProviderId,
    val displayName: String,
    val baseUrl: String,
    val modelIds: List<String>,
    val defaultModelId: String?,
    val createdAt: Long,
    val isNew: Boolean,
    val hasSavedKey: Boolean,
) {
    /**
     * Builds the domain config, normalizing whitespace and dropping
     * blank model ids. Validation still runs on the result — a blank
     * name, bad URL, or empty model list fails there, not here.
     */
    fun toConfig(now: Long): ProviderConfig = ProviderConfig(
        id = id,
        providerId = providerId,
        displayName = displayName.trim(),
        baseUrl = baseUrl.trim(),
        modelIds = modelIds.map { it.trim() }.filter { it.isNotEmpty() },
        defaultModelId = defaultModelId?.trim()?.takeIf { it.isNotEmpty() },
        createdAt = createdAt,
        updatedAt = now,
    )
}

/** Per-config connection-test outcome shown in the providers UI. */
sealed interface TestState {
    /** Never tested (or result cleared). */
    data object Idle : TestState

    /** Test in flight. */
    data object Running : TestState

    /** The endpoint answered with valid credentials. */
    data object Ok : TestState

    /** The test failed; [message] is user-facing. */
    data class Failed(val message: UiText) : TestState
}

/**
 * UI state for the providers screen.
 */
data class ProvidersUiState(
    val configs: List<ProviderConfig> = emptyList(),
    /** Whether a key is stored per config id (value only, never the key). */
    val hasKey: Map<String, Boolean> = emptyMap(),
    val isLoading: Boolean = true,
    /** Non-null while the editor sheet is open. */
    val editing: ProviderConfigDraft? = null,
    /** Config id awaiting delete confirmation. */
    val pendingDeleteId: String? = null,
    /** Connection-test outcome per config id. */
    val testResult: Map<String, TestState> = emptyMap(),
    /** Registered adapters, for the provider-family dropdown. */
    val providers: List<AiProvider> = emptyList(),
)

/**
 * Backs the providers screen: CRUD for [ProviderConfig] plus key-status,
 * explicit connection tests, and model-list refresh.
 *
 * Conventions:
 * - Configs and keys have separate lifetimes: deleting a config also
 *   deletes its key explicitly ([CredentialStore.delete]), because the
 *   repository contract leaves key cleanup to the caller.
 * - Validation runs BEFORE any key-store write: an invalid draft never
 *   touches stored credentials.
 * - Connection tests and model refreshes are explicit user actions only —
 *   nothing here fires on screen open or on config-list emission.
 * - Failures that are not per-action test results go to [errors] as
 *   [UiText], mirroring SettingsViewModel.
 */
class ProvidersViewModel(
    private val configRepository: ProviderConfigRepository,
    private val credentialStore: CredentialStore,
    private val providerRegistry: ProviderRegistry,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()

    private val _errors = Channel<UiText>(Channel.BUFFERED)
    val errors: Flow<UiText> = _errors.receiveAsFlow()

    init {
        _uiState.update { it.copy(providers = providerRegistry.all()) }
        viewModelScope.launch {
            configRepository.configs().collect { configs ->
                val keyMap = configs.associate { config ->
                    config.id to isKeyStored(config.id)
                }
                _uiState.update {
                    it.copy(configs = configs, hasKey = keyMap, isLoading = false)
                }
            }
        }
    }

    /** True when a non-null key decrypts for [configId]; false on any failure. */
    private suspend fun isKeyStored(configId: String): Boolean =
        (credentialStore.load(configId) as? Result.Success)?.data != null

    /** Opens the editor with a fresh draft (OpenAI family by default). */
    fun addNew() {
        val adapter =
            providerRegistry.get(ProviderId.OPENAI) ?: providerRegistry.all().firstOrNull()
        val now = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                editing = ProviderConfigDraft(
                    id = UUID.randomUUID().toString(),
                    providerId = adapter?.providerId ?: ProviderId.OPENAI,
                    displayName = "",
                    baseUrl = adapter?.defaultBaseUrl.orEmpty(),
                    modelIds = listOf(DEFAULT_MODEL_ID),
                    defaultModelId = DEFAULT_MODEL_ID,
                    createdAt = now,
                    isNew = true,
                    hasSavedKey = false,
                ),
            )
        }
    }

    /** Opens the editor for an existing [config]. */
    fun edit(config: ProviderConfig) {
        _uiState.update {
            it.copy(
                editing = ProviderConfigDraft(
                    id = config.id,
                    providerId = config.providerId,
                    displayName = config.displayName,
                    baseUrl = config.baseUrl,
                    modelIds = config.modelIds,
                    defaultModelId = config.defaultModelId,
                    createdAt = config.createdAt,
                    isNew = false,
                    hasSavedKey = it.hasKey[config.id] == true,
                ),
            )
        }
    }

    /** Applies editor field changes to the open draft. */
    fun updateDraft(draft: ProviderConfigDraft) {
        _uiState.update { it.copy(editing = draft) }
    }

    /** Closes the editor, discarding unsaved changes. */
    fun cancelEdit() {
        _uiState.update { it.copy(editing = null) }
    }

    /**
     * Validates [draft] and persists it, then stores [apiKey] when
     * non-blank (trimmed; blank keeps any previously saved key).
     *
     * The key store is written only after the config validates AND
     * persists. The editor closes on full success and stays open —
     * with the failure on [errors] — otherwise.
     */
    fun saveDraft(draft: ProviderConfigDraft, apiKey: String): Job =
        viewModelScope.launch {
            val config = draft.toConfig(System.currentTimeMillis())
            val validation = config.validate()
            if (validation is Result.Error) {
                _errors.trySend(
                    UiText.Res(
                        R.string.providers_validation_failed,
                        listOf(validation.cause.message.orEmpty()),
                    ),
                )
                return@launch
            }
            if (configRepository.save(config) is Result.Error) {
                _errors.trySend(UiText.Res(R.string.providers_save_error))
                return@launch
            }
            val key = apiKey.trim()
            if (key.isNotBlank() && credentialStore.save(config.id, key) is Result.Error) {
                _errors.trySend(UiText.Res(R.string.providers_key_save_error))
                return@launch
            }
            _uiState.update { it.copy(editing = null) }
        }

    /** Opens the delete-confirmation dialog for [configId]. */
    fun requestDelete(configId: String) {
        _uiState.update { it.copy(pendingDeleteId = configId) }
    }

    /** Dismisses the delete-confirmation dialog. */
    fun cancelDelete() {
        _uiState.update { it.copy(pendingDeleteId = null) }
    }

    /**
     * Deletes the config AND its stored key (two explicit calls, per the
     * repository contract which leaves key cleanup to the caller).
     * Either failure surfaces on [errors]; the dialog closes regardless.
     */
    fun deleteConfig(configId: String): Job = viewModelScope.launch {
        _uiState.update { it.copy(pendingDeleteId = null) }
        if (configRepository.delete(configId) is Result.Error) {
            _errors.trySend(UiText.Res(R.string.providers_delete_error))
            return@launch
        }
        if (credentialStore.delete(configId) is Result.Error) {
            _errors.trySend(UiText.Res(R.string.providers_delete_error))
        }
    }

    /**
     * Tests the SAVED config [configId] against its adapter.
     * Explicit only — never called automatically.
     */
    fun testConnection(configId: String): Job = viewModelScope.launch {
        val config = configRepository.get(configId)
        if (config == null) {
            setTestResult(configId, TestState.Failed(UiText.Res(R.string.providers_test_missing)))
            return@launch
        }
        val key = (credentialStore.load(configId) as? Result.Success)?.data
        runProviderTest(targetId = configId, config = config, key = key)
    }

    /**
     * Tests the in-editor [draft] without persisting it.
     *
     * [apiKey] (trimmed) wins when non-blank; otherwise the already-saved
     * key is used, so editing other fields re-tests against the stored
     * key without retyping it.
     */
    fun testDraft(draft: ProviderConfigDraft, apiKey: String): Job =
        viewModelScope.launch {
            val config = draft.toConfig(System.currentTimeMillis())
            val validation = config.validate()
            if (validation is Result.Error) {
                setTestResult(
                    draft.id,
                    TestState.Failed(
                        UiText.Res(
                            R.string.providers_validation_failed,
                            listOf(validation.cause.message.orEmpty()),
                        ),
                    ),
                )
                return@launch
            }
            val typed = apiKey.trim()
            val key = typed.ifBlank {
                (credentialStore.load(draft.id) as? Result.Success)?.data
            }
            runProviderTest(targetId = draft.id, config = config, key = key)
        }

    /**
     * Replaces the saved config's [ProviderConfig.modelIds] with the
     * adapter's live model list (explicit only).
     *
     * A no-op when the adapter has no model-listing capability — the UI
     * disables the button in that case; this is the defensive second
     * check. An orphaned [ProviderConfig.defaultModelId] is reset to the
     * first listed model. The open editor draft (if for this config) is
     * updated in place so the user sees the new list.
     */
    fun refreshModels(configId: String): Job = viewModelScope.launch {
        val config = configRepository.get(configId)
        if (config == null) {
            _errors.trySend(UiText.Res(R.string.providers_test_missing))
            return@launch
        }
        val adapter = providerRegistry.get(config.providerId)
        if (adapter == null) {
            _errors.trySend(
                UiText.Res(R.string.error_provider_config, listOf(config.providerId.name)),
            )
            return@launch
        }
        if (!adapter.capabilities.modelListing) return@launch
        val key = (credentialStore.load(configId) as? Result.Success)?.data
        if (key.isNullOrBlank()) {
            _errors.trySend(UiText.Res(R.string.providers_test_no_key))
            return@launch
        }
        when (val result = adapter.listModels(config, key)) {
            is Result.Success -> {
                val ids = result.data.map { it.id }.distinct().filter { it.isNotBlank() }
                if (ids.isEmpty()) {
                    _errors.trySend(UiText.Res(R.string.providers_refresh_empty))
                    return@launch
                }
                val updated = config.copy(
                    modelIds = ids,
                    defaultModelId = config.defaultModelId?.takeIf { it in ids } ?: ids.first(),
                    updatedAt = System.currentTimeMillis(),
                )
                if (configRepository.save(updated) is Result.Error) {
                    _errors.trySend(UiText.Res(R.string.providers_save_error))
                    return@launch
                }
                val editing = _uiState.value.editing
                if (editing != null && editing.id == configId) {
                    _uiState.update {
                        it.copy(
                            editing = editing.copy(
                                modelIds = ids,
                                defaultModelId = updated.defaultModelId,
                            ),
                        )
                    }
                }
            }
            is Result.Error -> _errors.trySend(result.cause.toProviderUiText())
            is Result.Loading -> Unit // Adapters never return this; nothing to do.
        }
    }

    private suspend fun runProviderTest(targetId: String, config: ProviderConfig, key: String?) {
        if (key.isNullOrBlank()) {
            setTestResult(targetId, TestState.Failed(UiText.Res(R.string.providers_test_no_key)))
            return
        }
        val adapter = providerRegistry.get(config.providerId)
        if (adapter == null) {
            setTestResult(
                targetId,
                TestState.Failed(
                    UiText.Res(R.string.error_provider_config, listOf(config.providerId.name)),
                ),
            )
            return
        }
        setTestResult(targetId, TestState.Running)
        when (val result = adapter.testConnection(config, key)) {
            is Result.Success -> setTestResult(targetId, TestState.Ok)
            is Result.Error -> setTestResult(targetId, TestState.Failed(result.cause.toProviderUiText()))
            is Result.Loading -> Unit // Adapters never return this; stays Running.
        }
    }

    private fun setTestResult(targetId: String, state: TestState) {
        _uiState.update { it.copy(testResult = it.testResult + (targetId to state)) }
    }

    companion object {
        private const val DEFAULT_MODEL_ID = "gpt-4o"
    }
}
