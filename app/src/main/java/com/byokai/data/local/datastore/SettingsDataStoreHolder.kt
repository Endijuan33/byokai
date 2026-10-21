package com.byokai.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * Process-wide access to the settings DataStore.
 *
 * DataStore forbids two active instances on the same file, but
 * [android.app.Activity.attachBaseContext] runs before
 * [android.app.Application.onCreate], so the startup locale read cannot go
 * through the Application's [com.byokai.di.AppContainer]. Both the locale
 * path and the DI graph share the DataStore returned here, which the
 * [preferencesDataStore] delegate keeps as a singleton per application
 * context.
 */
private const val SETTINGS_DATASTORE_NAME = "byokai_settings"

private val Context.settingsPreferences by preferencesDataStore(
    name = SETTINGS_DATASTORE_NAME,
)

object SettingsDataStoreHolder {

    fun get(context: Context): SettingsDataStore =
        SettingsDataStore(context.applicationContext.settingsPreferences)
}
