package com.byokai.data.security

import android.content.Context
import android.security.keystore.KeyPermanentlyInvalidatedException
import com.google.crypto.tink.Aead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.AeadConfig2026
import com.google.crypto.tink.aead.AeadKeyTemplates
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Production [AeadProvider]: a Tink AES-256-GCM keyset whose keyset file
 * is itself encrypted by a master key held in the Android Keystore.
 *
 * - The Tink keyset is generated on first use and persisted in shared
 *   preferences ([KEYSET_PREFS_FILE]); afterwards it is loaded from disk.
 * - The master key (`android-keystore://byokai_master_key`) lives in the
 *   Android Keystore, so the keyset can only be decrypted on this device
 *   with the user's lock-screen credentials intact.
 *
 * If the Keystore master key is permanently invalidated (lock screen
 * removed/reset, device restored from a backup that excluded the keyset —
 * see `backup_rules.xml`), [get] throws [KeyInvalidatedException] and the
 * caller wipes the credential store; the user must re-enter their keys.
 *
 * Do NOT construct this from an Activity context: pass the application
 * context (the AppContainer does this).
 */
class AndroidKeysetAeadProvider(context: Context) : AeadProvider {

    private val appContext = context.applicationContext

    override fun get(): Aead {
        // Populates the global registries consulted by AndroidKeysetManager's
        // internal KeysetHandle.generateNew(keyTemplate). Idempotent; without
        // it every encrypt/decrypt throws "No key manager found".
        AeadConfig.register()
        // Explicit Configuration for the primitive lookup (Tink's documented
        // API) instead of relying on the global RegistryConfiguration.
        val config = AeadConfig2026.get()
        val aead = try {
            AndroidKeysetManager.Builder()
                .withSharedPref(appContext, KEYSET_NAME, KEYSET_PREFS_FILE)
                .withKeyTemplate(AeadKeyTemplates.AES256_GCM)
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(config, Aead::class.java)
        } catch (e: Exception) {
            throw findInvalidation(e)?.let { KeyInvalidatedException(it) } ?: e
        }
        return aead
    }

    /**
     * Tink usually surfaces the platform invalidation wrapped inside its
     * own exceptions, so the whole cause chain is searched — not just the
     * top-level throwable.
     */
    private fun findInvalidation(throwable: Throwable): Throwable? {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is KeyPermanentlyInvalidatedException) return current
            current = current.cause
        }
        return null
    }

    companion object {
        private const val KEYSET_NAME = "byokai_credential_keyset"
        private const val KEYSET_PREFS_FILE = "byokai_tink_keyset"
        private const val MASTER_KEY_URI = "android-keystore://byokai_master_key"
    }
}
