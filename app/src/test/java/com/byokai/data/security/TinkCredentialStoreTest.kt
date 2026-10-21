package com.byokai.data.security

import com.byokai.core.common.Result
import com.byokai.domain.security.CredentialException
import com.byokai.domain.security.CredentialStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadKeyTemplates
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests for [TinkCredentialStore] using a real Tink AES-256-GCM
 * primitive backed by an ephemeral in-memory keyset — pure JVM, no
 * Android APIs, no Keystore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TinkCredentialStoreTest {

    /** In-memory [CredentialStorage] that also exposes the raw stored bytes. */
    private class FakeCredentialStorage : CredentialStorage {
        val raw: MutableMap<String, String> = mutableMapOf()
        override suspend fun put(key: String, base64Ciphertext: String) {
            raw[key] = base64Ciphertext
        }
        override suspend fun get(key: String): String? = raw[key]
        override suspend fun remove(key: String) {
            raw.remove(key)
        }
        override suspend fun clear() {
            raw.clear()
        }
    }

    private fun ephemeralAead(): Aead =
        KeysetHandle.generateNew(AeadKeyTemplates.AES256_GCM)
            .getPrimitive(Aead::class.java)

    private fun ephemeralProvider(aead: Aead = ephemeralAead()) =
        object : AeadProvider {
            override fun get(): Aead = aead
        }

    private lateinit var storage: FakeCredentialStorage
    private lateinit var store: CredentialStore

    @Before
    fun setUp() {
        storage = FakeCredentialStorage()
        store = TinkCredentialStore(ephemeralProvider(), storage)
    }

    @Test
    fun `save then load returns the original key`() = runTest {
        val key = "sk-test-4f8a2c1d9e"
        assertTrue(store.save("openai-work", key) is Result.Success)
        val result = store.load("openai-work")
        assertTrue(result is Result.Success)
        assertEquals(key, (result as Result.Success).data)
    }

    @Test
    fun `saving again overwrites the previous key`() = runTest {
        store.save("openai-work", "first-key")
        store.save("openai-work", "second-key")
        val result = store.load("openai-work")
        assertEquals("second-key", (result as Result.Success).data)
    }

    @Test
    fun `delete removes the key so load returns null`() = runTest {
        store.save("openai-work", "sk-test-123")
        assertTrue(store.delete("openai-work") is Result.Success)
        val result = store.load("openai-work")
        assertTrue(result is Result.Success)
        assertNull((result as Result.Success).data)
    }

    @Test
    fun `load of an absent config returns Success with null`() = runTest {
        val result = store.load("never-saved")
        assertTrue(result is Result.Success)
        assertNull((result as Result.Success).data)
    }

    @Test
    fun `decrypting with the wrong key fails with DecryptionFailed and leaks nothing`() =
        runTest {
            val key = "sk-super-secret-key-9z"
            // Encrypt under one keyset, attempt to decrypt under another.
            TinkCredentialStore(ephemeralProvider(), storage).save("openai-work", key)
            val wrongStore = TinkCredentialStore(ephemeralProvider(), storage)

            val result = wrongStore.load("openai-work")
            assertTrue(result is Result.Error)
            val cause = (result as Result.Error).cause
            assertTrue(cause is CredentialException.DecryptionFailed)
            assertEquals("openai-work", (cause as CredentialException.DecryptionFailed).configId)
            assertFalse("error message must never contain key material",
                cause.message.orEmpty().contains(key))
        }

    @Test
    fun `KeyInvalidated from the provider wipes storage and returns KeyInvalidated`() =
        runTest {
            storage.raw["credential_openai-work"] = "undecryptable-stale-ciphertext"
            val invalidatedProvider = object : AeadProvider {
                override fun get(): Aead = throw KeyInvalidatedException()
            }
            val invalidatedStore = TinkCredentialStore(invalidatedProvider, storage)

            val result = invalidatedStore.load("openai-work")
            assertTrue(result is Result.Error)
            assertTrue((result as Result.Error).cause is CredentialException.KeyInvalidated)
            assertTrue("storage must be wiped after key invalidation", storage.raw.isEmpty())
        }

    @Test
    fun `saving a blank key returns Error`() = runTest {
        val blank = store.save("openai-work", "   ")
        assertTrue(blank is Result.Error)
        assertTrue((blank as Result.Error).cause is CredentialException.StorageError)
        val empty = store.save("openai-work", "")
        assertTrue(empty is Result.Error)
    }

    @Test
    fun `stored value is ciphertext not plaintext`() = runTest {
        val key = "sk-plaintext-must-never-appear"
        store.save("openai-work", key)

        val stored = storage.raw["credential_openai-work"]
        assertTrue(stored != null)
        assertNotEquals("stored value must not be the plaintext key", key, stored)
        val decoded = Base64.getDecoder().decode(stored)
        assertFalse(
            "stored bytes must not equal the plaintext key bytes",
            decoded.contentEquals(key.toByteArray(Charsets.UTF_8)),
        )
        assertFalse("stored bytes must not even contain the plaintext key",
            String(decoded, Charsets.UTF_8).contains(key))
    }
}
