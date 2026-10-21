package com.byokai.ui.settings

import com.byokai.core.common.Result
import com.byokai.domain.model.AppLanguage
import com.byokai.domain.model.ThemeMode
import com.byokai.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory fake of [SettingsRepository] for ViewModel tests.
 * Set [failWrites] to true to simulate a persistence IO failure.
 */
class FakeSettingsRepository(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    language: AppLanguage = AppLanguage.ENGLISH,
) : SettingsRepository {

    private val _themeMode = MutableStateFlow(themeMode)
    private val _dynamicColor = MutableStateFlow(dynamicColor)
    private val _language = MutableStateFlow(language)

    var failWrites: Boolean = false

    override val themeMode: Flow<ThemeMode> = _themeMode.asStateFlow()
    override val dynamicColor: Flow<Boolean> = _dynamicColor.asStateFlow()
    override val language: Flow<AppLanguage> = _language.asStateFlow()

    override suspend fun setThemeMode(mode: ThemeMode): Result<Unit> =
        write { _themeMode.value = mode }

    override suspend fun setDynamicColor(enabled: Boolean): Result<Unit> =
        write { _dynamicColor.value = enabled }

    override suspend fun setLanguage(language: AppLanguage): Result<Unit> =
        write { _language.value = language }

    private fun write(block: () -> Unit): Result<Unit> =
        if (failWrites) {
            Result.Error(RuntimeException("simulated IO failure"))
        } else {
            block()
            Result.Success(Unit)
        }
}
