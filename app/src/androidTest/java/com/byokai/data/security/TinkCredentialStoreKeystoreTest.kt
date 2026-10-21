package com.byokai.data.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device test of the real [AndroidKeysetAeadProvider] (Android
 * Keystore master key + Tink AES-256-GCM keyset) wired into
 * [TinkCredentialStore].
 *
 * Runs on-device ONLY — the Phase 5 device matrix. It is NOT run in CI:
 * CI has no Android Keystore and the sandbox/emulator keystore behavior
 * is not representative. The Keystore-independent logic is covered by the
 * JVM unit tests ([TinkCredentialStoreTest]).
 */
@RunWith(AndroidJUnit4::class)
class TinkCredentialStoreKeystoreTest {

    /** In-memory storage; the encrypted-at-rest persistence layer is
     * covered separately by [DataStoreCredentialStorageTest]. */
    private class InMemoryCredentialStorage : CredentialStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun put(key: String, base64Ciphertext: String) {
            map[key] = base64Ciphertext
        }
        override suspend fun get(key: String): String? = map[key]
        override suspend fun remove(key: String) {
            map.remove(key)
        }
        override suspend fun clear() {
            map.clear()
        }
    }

    @Test
    fun `roundtrip through the real keystore-backed provider`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = TinkCredentialStore(
            AndroidKeysetAeadProvider(context),
            InMemoryCredentialStorage(),
        )

        val key = "sk-device-roundtrip-test-key"
        store.save("openai-work", key)

        val loaded = store.load("openai-work")
        assertEquals(key, successData(loaded))
    }

    private fun successData(result: com.byokai.core.common.Result<String?>): String? =
        (result as com.byokai.core.common.Result.Success).data
}
