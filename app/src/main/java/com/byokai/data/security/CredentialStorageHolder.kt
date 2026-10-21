package com.byokai.data.security

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/**
 * Process-wide access to the dedicated credential DataStore.
 *
 * The credential file (`byokai_credentials`) is SEPARATE from the settings
 * DataStore file: it holds only encrypted key material and is excluded
 * from device backups (`res/xml/backup_rules.xml`), while settings are
 * backed up. Sharing a file would couple their backup and lifecycle
 * semantics.
 *
 * Same sharing rationale as [com.byokai.data.local.datastore.SettingsDataStoreHolder]:
 * the [preferencesDataStore] delegate keeps one singleton per application
 * context, and DataStore forbids two active instances on the same file.
 */
private const val CREDENTIALS_DATASTORE_NAME = "byokai_credentials"

private val Context.credentialPreferences by preferencesDataStore(
    name = CREDENTIALS_DATASTORE_NAME,
)

object CredentialStorageHolder {

    fun get(context: Context): DataStoreCredentialStorage =
        DataStoreCredentialStorage(context.applicationContext.credentialPreferences)
}
