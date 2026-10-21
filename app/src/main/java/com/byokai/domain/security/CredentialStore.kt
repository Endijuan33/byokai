package com.byokai.domain.security

import com.byokai.core.common.Result

/**
 * Encrypted API-key storage, one key per provider configuration.
 *
 * The credential store is deliberately separate from
 * [com.byokai.domain.model.ProviderConfig]: configs may be exported,
 * logged, or displayed, while keys only ever leave this store
 * (decrypted in memory) at the moment an HTTP request is signed.
 *
 * Keys are encrypted at rest (see `data.security` for the Keystore +
 * Tink implementation) and are never logged — not at save, not at
 * load, and not inside exception messages or causes visible to callers.
 */
interface CredentialStore {

    /**
     * Encrypts and stores [apiKey] for the provider config [configId],
     * overwriting any existing key for that id.
     *
     * @return [Result.Error] when [apiKey] is blank or the operation
     * fails.
     */
    suspend fun save(configId: String, apiKey: String): Result<Unit>

    /**
     * Loads and decrypts the key for [configId].
     *
     * @return [Result.Success] with `null` when no key was ever saved
     * (or was deleted); [Result.Error] on decryption or storage failure.
     */
    suspend fun load(configId: String): Result<String?>

    /**
     * Deletes the key for [configId]; deleting an absent key succeeds.
     */
    suspend fun delete(configId: String): Result<Unit>

    /**
     * Deletes every stored key.
     */
    suspend fun clearAll(): Result<Unit>
}
