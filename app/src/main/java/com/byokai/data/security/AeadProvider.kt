package com.byokai.data.security

import com.google.crypto.tink.Aead

/**
 * Supplies the Tink [Aead] primitive used to encrypt credentials.
 *
 * Kept behind an interface so JVM unit tests can inject an ephemeral
 * in-memory keyset, while production code uses the Android Keystore
 * via [AndroidKeysetAeadProvider].
 *
 * @throws KeyInvalidatedException when the Keystore master key was
 * invalidated — callers treat the whole store as compromised and wipe it.
 */
interface AeadProvider {
    fun get(): Aead
}

/**
 * Thrown by [AeadProvider] implementations when the Android Keystore key
 * backing the Tink keyset was permanently invalidated (e.g. the device
 * lock screen was removed or reset).
 *
 * The cause is the platform [android.security.keystore.KeyPermanentlyInvalidatedException]
 * (possibly wrapped by Tink); it never contains key material.
 */
class KeyInvalidatedException(cause: Throwable? = null) :
    Exception("Android Keystore key invalidated", cause)
