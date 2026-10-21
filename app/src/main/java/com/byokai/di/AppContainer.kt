package com.byokai.di

import android.content.Context
import com.byokai.data.local.datastore.SettingsDataStoreHolder
import com.byokai.data.repository.SettingsRepositoryImpl
import com.byokai.data.security.AndroidKeysetAeadProvider
import com.byokai.data.security.CredentialStorageHolder
import com.byokai.data.security.TinkCredentialStore
import com.byokai.domain.repository.SettingsRepository
import com.byokai.domain.security.CredentialStore

/**
 * Manual dependency graph (interim).
 *
 * Hilt is deferred until KSP supports Kotlin 2.4.x (see docs/ROADMAP.md),
 * so dependencies are wired here as lazy singletons. The Application owns
 * exactly one [AppContainer]; screens resolve dependencies from it via
 * `context.applicationContext as ByokaiApplication`.
 *
 * When Hilt returns, this class is replaced by Hilt modules — the
 * repository interfaces and their constructor signatures stay unchanged,
 * so call sites are unaffected.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(SettingsDataStoreHolder.get(appContext))
    }

    /**
     * Encrypted API-key storage (Milestone 3B).
     *
     * The AEAD key lives in a Tink keyset whose file is encrypted by an
     * Android Keystore master key; ciphertexts are persisted in a
     * dedicated DataStore file that is excluded from device backups.
     * Plaintext keys never leave this store except in memory at the
     * moment a request is signed.
     */
    val credentialStore: CredentialStore by lazy {
        TinkCredentialStore(
            AndroidKeysetAeadProvider(appContext),
            CredentialStorageHolder.get(appContext),
        )
    }
}
