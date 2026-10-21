package com.byokai.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/**
 * [CredentialStorage] backed by a dedicated DataStore preferences file.
 *
 * Stores one entry per provider config: the DataStore key is
 * `credential_<configId>`, the value the Base64-encoded ciphertext
 * produced by [TinkCredentialStore]. Plaintext keys never reach this
 * layer — it only sees ciphertext strings.
 */
class DataStoreCredentialStorage(
    private val dataStore: DataStore<Preferences>,
) : CredentialStorage {

    override suspend fun put(key: String, base64Ciphertext: String) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey(key)] = base64Ciphertext }
    }

    override suspend fun get(key: String): String? =
        dataStore.data.first()[stringPreferencesKey(key)]

    override suspend fun remove(key: String) {
        dataStore.edit { prefs -> prefs.remove(stringPreferencesKey(key)) }
    }

    override suspend fun clear() {
        dataStore.edit { prefs -> prefs.clear() }
    }
}
