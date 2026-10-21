package com.byokai.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Process-wide access to the provider-config DataStore.
 *
 * Mirrors [SettingsDataStoreHolder]: DataStore forbids two active
 * instances on the same file, so every consumer shares the instance
 * returned here, which the [preferencesDataStore] delegate keeps as a
 * singleton per application context.
 */
private const val PROVIDER_CONFIG_DATASTORE_NAME = "byokai_provider_configs"

private val Context.providerConfigPreferences by preferencesDataStore(
    name = PROVIDER_CONFIG_DATASTORE_NAME,
)

object ProviderConfigHolder {

    fun get(context: Context): DataStore<Preferences> =
        context.applicationContext.providerConfigPreferences
}
