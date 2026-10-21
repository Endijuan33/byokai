package com.byokai.domain.security

/**
 * Failures of the encrypted credential store.
 *
 * None of these subclasses ever carry key material in their message —
 * messages are static strings (plus the non-secret config id where
 * needed), so they are safe to log or surface in crash reports.
 */
sealed class CredentialException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /**
     * The Android Keystore master key was invalidated (e.g. the user
     * removed their lock screen). The encrypted store has already been
     * wiped; the user must re-enter their API keys.
     */
    class KeyInvalidated(cause: Throwable? = null) :
        CredentialException(
            "The device key protecting stored credentials was invalidated. " +
                "Stored credentials were wiped; please re-enter your API keys.",
            cause,
        )

    /**
     * A stored credential could not be decrypted (wrong key, corrupted or
     * tampered ciphertext). [configId] is the non-secret provider config
     * identifier the credential belonged to.
     */
    class DecryptionFailed(val configId: String) :
        CredentialException(
            "Failed to decrypt the stored credential for this provider " +
                "configuration. It may have been corrupted or stored " +
                "with a different key; re-entering the API key resolves this.",
        )

    /**
     * Any other storage-level failure (IO error, unexpected encryption
     * error, malformed input). [reason] is a static description that
     * never contains key material.
     */
    class StorageError(reason: String, cause: Throwable? = null) :
        CredentialException("Credential storage failed: $reason", cause)
}
