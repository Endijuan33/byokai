package com.byokai.data.security

import com.byokai.core.common.Result
import com.byokai.domain.security.CredentialException
import com.byokai.domain.security.CredentialStore
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * [CredentialStore] that encrypts each API key with Tink AES-256-GCM
 * before persisting it.
 *
 * Storage layout:
 * - DataStore key: `credential_<configId>`
 * - Value: Base64-encoded ciphertext (encryption + integrity tag).
 * - Associated data: the config id, binding each ciphertext to its
 *   config so a ciphertext copied under another id will not decrypt.
 *
 * [java.util.Base64] is used instead of `android.util.Base64` so this
 * class — and its tests — run on the plain JVM as well as on Android.
 *
 * No key material ever appears in returned errors: messages are static
 * strings, and the ciphertext storage layer ([CredentialStorage]) only
 * ever sees ciphertext.
 */
class TinkCredentialStore(
    private val aeadProvider: AeadProvider,
    private val storage: CredentialStorage,
) : CredentialStore {

    override suspend fun save(configId: String, apiKey: String): Result<Unit> {
        if (apiKey.isBlank()) {
            return Result.Error(CredentialException.StorageError("apiKey must not be blank"))
        }
        return try {
            val ciphertext = aeadProvider.get()
                .encrypt(apiKey.toByteArray(Charsets.UTF_8), associatedData(configId))
            storage.put(keyFor(configId), Base64.getEncoder().encodeToString(ciphertext))
            Result.Success(Unit)
        } catch (e: KeyInvalidatedException) {
            wipeOnInvalidation()
            Result.Error(CredentialException.KeyInvalidated(e))
        } catch (e: GeneralSecurityException) {
            Result.Error(CredentialException.StorageError("failed to encrypt credential", e))
        } catch (e: Exception) {
            Result.Error(CredentialException.StorageError("failed to store credential", e))
        }
    }

    override suspend fun load(configId: String): Result<String?> {
        return try {
            val stored = storage.get(keyFor(configId)) ?: return Result.Success(null)
            val ciphertext = try {
                Base64.getDecoder().decode(stored)
            } catch (e: IllegalArgumentException) {
                // Malformed Base64: the stored value is corrupted.
                return Result.Error(CredentialException.DecryptionFailed(configId))
            }
            val plaintext = aeadProvider.get().decrypt(ciphertext, associatedData(configId))
            Result.Success(String(plaintext, Charsets.UTF_8))
        } catch (e: KeyInvalidatedException) {
            wipeOnInvalidation()
            Result.Error(CredentialException.KeyInvalidated(e))
        } catch (e: GeneralSecurityException) {
            Result.Error(CredentialException.DecryptionFailed(configId))
        } catch (e: Exception) {
            Result.Error(CredentialException.StorageError("failed to load credential", e))
        }
    }

    override suspend fun delete(configId: String): Result<Unit> =
        try {
            storage.remove(keyFor(configId))
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(CredentialException.StorageError("failed to delete credential", e))
        }

    override suspend fun clearAll(): Result<Unit> =
        try {
            storage.clear()
            Result.Success(Unit)
        } catch (e: Exception) {
            Result.Error(CredentialException.StorageError("failed to clear credentials", e))
        }

    /**
     * After a Keystore invalidation the DEK is gone forever, so every
     * stored ciphertext is permanently undecryptable. Wiping them now is
     * hygiene (no stale blobs survive), never a data-loss decision.
     */
    private suspend fun wipeOnInvalidation() {
        runCatching { storage.clear() }
    }

    private fun keyFor(configId: String) = "credential_$configId"

    private fun associatedData(configId: String): ByteArray =
        configId.toByteArray(Charsets.UTF_8)
}
