package com.byokai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.byokai.R
import com.byokai.core.common.Result
import com.byokai.core.common.UiText
import com.byokai.domain.model.AppLanguage
import com.byokai.domain.model.ThemeMode
import com.byokai.domain.repository.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * UI state for the settings screen.
 */
data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val isLoading: Boolean = true,
)

/**
 * Backs the settings screen.
 *
 * Combines the repository's settings [Flow]s into [uiState]. Write
 * failures are forwarded as [UiText] on [errors] — never swallowed — so
 * the screen can surface them (e.g. via Snackbar).
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _errors = Channel<UiText>(Channel.BUFFERED)
    val errors: Flow<UiText> = _errors.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                settingsRepository.themeMode,
                settingsRepository.dynamicColor,
                settingsRepository.language,
            ) { themeMode, dynamicColor, language ->
                SettingsUiState(
                    themeMode = themeMode,
                    dynamicColor = dynamicColor,
                    language = language,
                    isLoading = false,
                )
            }.collect { _uiState.value = it }
        }
    }

    /**
     * Persists the theme mode. Returns the launched [Job] so callers can
     * await completion (the language row uses this before recreating the
     * activity).
     */
    fun setThemeMode(mode: ThemeMode): Job = viewModelScope.launch {
        if (settingsRepository.setThemeMode(mode) is Result.Error) {
            _errors.trySend(UiText.Res(R.string.settings_save_error))
        }
    }

    fun setDynamicColor(enabled: Boolean): Job = viewModelScope.launch {
        if (settingsRepository.setDynamicColor(enabled) is Result.Error) {
            _errors.trySend(UiText.Res(R.string.settings_save_error))
        }
    }

    fun setLanguage(language: AppLanguage): Job = viewModelScope.launch {
        if (settingsRepository.setLanguage(language) is Result.Error) {
            _errors.trySend(UiText.Res(R.string.settings_save_error))
        }
    }
}
