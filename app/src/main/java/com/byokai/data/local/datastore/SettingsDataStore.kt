package com.byokai.data.local.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.byokai.core.common.Result
import com.byokai.domain.model.AppLanguage
import com.byokai.domain.model.ThemeMode
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Thin DataStore (Preferences) wrapper for app settings.
 *
 * Read [Flow]s recover from [IOException] with documented defaults so a
 * damaged preferences file degrades to defaults instead of crashing the
 * app. Writes surface failures as [Result.Error] so the UI can report
 * them (e.g. via Snackbar) rather than silently dropping the change.
 */
class SettingsDataStore(private val dataStore: DataStore<Preferences>) {

    val themeMode: Flow<ThemeMode> = dataStore.data
        .recoverFromIoFailure()
        .map { prefs -> ThemeMode.fromStorageKey(prefs[KEY_THEME_MODE]) }

    val dynamicColor: Flow<Boolean> = dataStore.data
        .recoverFromIoFailure()
        .map { prefs -> prefs[KEY_DYNAMIC_COLOR] ?: DEFAULT_DYNAMIC_COLOR }

    val language: Flow<AppLanguage> = dataStore.data
        .recoverFromIoFailure()
        .map { prefs -> AppLanguage.fromStorageKey(prefs[KEY_LANGUAGE]) }

    suspend fun setThemeMode(mode: ThemeMode): Result<Unit> =
        write { prefs -> prefs[KEY_THEME_MODE] = mode.storageKey }

    suspend fun setDynamicColor(enabled: Boolean): Result<Unit> =
        write { prefs -> prefs[KEY_DYNAMIC_COLOR] = enabled }

    suspend fun setLanguage(language: AppLanguage): Result<Unit> =
        write { prefs -> prefs[KEY_LANGUAGE] = language.storageKey }

    private suspend fun write(block: (MutablePreferences) -> Unit): Result<Unit> =
        try {
            dataStore.edit(block)
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(e)
        }

    private fun Flow<Preferences>.recoverFromIoFailure(): Flow<Preferences> =
        catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }

    companion object {
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_LANGUAGE = stringPreferencesKey("language")

        private const val DEFAULT_DYNAMIC_COLOR = true
    }
}
