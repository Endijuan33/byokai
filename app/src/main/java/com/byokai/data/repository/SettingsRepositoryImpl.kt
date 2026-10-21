package com.byokai.data.repository

import com.byokai.core.common.Result
import com.byokai.data.local.datastore.SettingsDataStore
import com.byokai.domain.model.AppLanguage
import com.byokai.domain.model.ThemeMode
import com.byokai.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * [SettingsRepository] backed by [SettingsDataStore].
 *
 * No mapping is needed: the DataStore layer already speaks domain types,
 * so this class is a pure delegation boundary (useful seam for fakes in
 * tests and for Hilt modules later).
 */
class SettingsRepositoryImpl(
    private val settingsDataStore: SettingsDataStore,
) : SettingsRepository {

    override val themeMode: Flow<ThemeMode> = settingsDataStore.themeMode
    override val dynamicColor: Flow<Boolean> = settingsDataStore.dynamicColor
    override val language: Flow<AppLanguage> = settingsDataStore.language

    override suspend fun setThemeMode(mode: ThemeMode): Result<Unit> =
        settingsDataStore.setThemeMode(mode)

    override suspend fun setDynamicColor(enabled: Boolean): Result<Unit> =
        settingsDataStore.setDynamicColor(enabled)

    override suspend fun setLanguage(language: AppLanguage): Result<Unit> =
        settingsDataStore.setLanguage(language)
}
