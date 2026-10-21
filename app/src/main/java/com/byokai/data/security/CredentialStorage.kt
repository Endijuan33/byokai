package com.byokai.data.security

/**
 * Raw persistence for encrypted credentials.
 *
 * This layer stores and returns ciphertext only — it never sees plaintext
 * keys or the encryption key. [TinkCredentialStore] encrypts before
 * calling [put] and decrypts after calling [get].
 *
 * @property base64Ciphertext Base64-encoded AES-GCM ciphertext.
 */
interface CredentialStorage {

    suspend fun put(key: String, base64Ciphertext: String)

    suspend fun get(key: String): String?

    suspend fun remove(key: String)

    suspend fun clear()
}
