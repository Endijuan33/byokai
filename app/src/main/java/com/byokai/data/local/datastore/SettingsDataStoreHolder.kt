package com.byokai.data.local.datastore

import android.content.Context
import androidx.datastore.preferences.PreferenceDataStoreFactory
import java.io.File

/**
 * Process-wide holder for the settings DataStore.
 *
 * DataStore forbids two active instances on the same file, but
 * [android.app.Activity.attachBaseContext] runs before
 * [android.app.Application.onCreate], so the startup locale read cannot go
 * through the Application's [com.byokai.di.AppContainer]. Both the locale
 * path and the DI graph share this single instance.
 */
object SettingsDataStoreHolder {

    private const val FILE_NAME = "byokai_settings.preferences_pb"

    @Volatile
    private var instance: SettingsDataStore? = null

    fun get(context: Context): SettingsDataStore =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(appContext: Context): SettingsDataStore {
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { File(appContext.filesDir, "datastore/$FILE_NAME") },
        )
        return SettingsDataStore(dataStore)
    }
}
