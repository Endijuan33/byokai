package com.byokai.domain.repository

import com.byokai.core.common.Result
import com.byokai.domain.model.AppLanguage
import com.byokai.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/**
 * Settings contract for the app.
 *
 * Reads are cold [Flow]s that always emit (documented defaults on IO
 * failure). Writes are suspending and return [Result] so persistence
 * failures reach the UI instead of being swallowed.
 */
interface SettingsRepository {
    val themeMode: Flow<ThemeMode>
    val dynamicColor: Flow<Boolean>
    val language: Flow<AppLanguage>

    suspend fun setThemeMode(mode: ThemeMode): Result<Unit>
    suspend fun setDynamicColor(enabled: Boolean): Result<Unit>
    suspend fun setLanguage(language: AppLanguage): Result<Unit>
}
