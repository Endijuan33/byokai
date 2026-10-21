package com.byokai.ui.prompts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byokai.R
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.model.PromptTemplate
import com.byokai.domain.repository.PromptRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Editor state for the create/edit prompt dialog. `promptId == null`
 * means "create new"; otherwise the dialog edits that prompt.
 */
data class PromptEditorState(
    val promptId: String?,
    val title: String,
    val content: String,
)

/** UI state for the prompts screen. */
data class PromptsUiState(
    val prompts: List<PromptTemplate> = emptyList(),
    val isLoading: Boolean = true,
    /** Prompt id awaiting delete confirmation. */
    val pendingDeleteId: String? = null,
    /** Non-null while the create/edit dialog is open. */
    val editor: PromptEditorState? = null,
)

/**
 * Backs the prompts screen: the persisted template list plus the
 * create/edit/delete flows (Milestone 4C).
 *
 * The list is driven by [PromptRepository.prompts()], so it reflects
 * every write without manual refreshing. Seeding runs once at init:
 * builtins appear only when the table is empty, so user edits or
 * deletions of builtins are never resurrected.
 *
 * No Android dependencies — JVM-unit-testable.
 */
class PromptsViewModel(
    private val repository: PromptRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PromptsUiState())
    val uiState: StateFlow<PromptsUiState> = _uiState.asStateFlow()

    private val _errors = Channel<UiText>(Channel.BUFFERED)
    val errors: Flow<UiText> = _errors.receiveAsFlow()

    init {
        viewModelScope.launch {
            repository.prompts().collect { prompts ->
                _uiState.update { it.copy(prompts = prompts, isLoading = false) }
            }
        }
        viewModelScope.launch {
            if (repository.ensureSeeded() is Result.Error) {
                _errors.trySend(UiText.Res(R.string.prompts_seed_error))
            }
        }
    }

    /** Opens the editor for [prompt], or for a new prompt when null. */
    fun openEditor(prompt: PromptTemplate?) {
        _uiState.update {
            it.copy(
                editor = PromptEditorState(
                    promptId = prompt?.id,
                    title = prompt?.title.orEmpty(),
                    content = prompt?.content.orEmpty(),
                ),
            )
        }
    }

    fun closeEditor() {
        _uiState.update { it.copy(editor = null) }
    }

    fun onEditorTitleChange(title: String) {
        _uiState.update { it.copy(editor = it.editor?.copy(title = title)) }
    }

    fun onEditorContentChange(content: String) {
        _uiState.update { it.copy(editor = it.editor?.copy(content = content)) }
    }

    /**
     * Saves the editor: creates a new prompt or updates the edited one.
     * Blank title/content surfaces [R.string.prompts_blank_error] and
     * keeps the dialog open.
     */
    fun saveEditor() {
        val editor = _uiState.value.editor ?: return
        if (editor.title.isBlank() || editor.content.isBlank()) {
            viewModelScope.launch {
                _errors.trySend(UiText.Res(R.string.prompts_blank_error))
            }
            return
        }
        viewModelScope.launch {
            val result = if (editor.promptId == null) {
                repository.create(editor.title, editor.content)
            } else {
                repository.update(editor.promptId, editor.title, editor.content)
            }
            when (result) {
                is Result.Success -> _uiState.update { it.copy(editor = null) }
                is Result.Error ->
                    _errors.trySend(UiText.Res(R.string.prompts_save_error))
                is Result.Loading -> Unit
            }
        }
    }

    /** Requests delete confirmation for [id]. */
    fun requestDelete(id: String) {
        _uiState.update { it.copy(pendingDeleteId = id) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(pendingDeleteId = null) }
    }

    fun confirmDelete() {
        val id = _uiState.value.pendingDeleteId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingDeleteId = null) }
            if (repository.delete(id) is Result.Error) {
                _errors.trySend(UiText.Res(R.string.prompts_delete_error))
            }
        }
    }
}
