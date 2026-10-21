package com.byokai.data.security

import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.PreferencesFileSerializer
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for [DataStoreCredentialStorage] against a real
 * DataStore backed by a temporary file. Runs on the JVM — no device
 * needed. The values used here are opaque ciphertext stand-ins; this
 * layer never sees plaintext keys by design.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreCredentialStorageTest {

    private lateinit var testFile: File
    private lateinit var storage: DataStoreCredentialStorage

    @Before
    fun setUp() {
        testFile = File.createTempFile("byokai-credentials-test", ".preferences_pb")
        testFile.delete()
        // PreferencesFileSerializer is the classic file-based Serializer
        // explicitly documented for manual DataStore creation in tests.
        storage = DataStoreCredentialStorage(
            DataStoreFactory.create(
                serializer = PreferencesFileSerializer,
                produceFile = { testFile },
            ),
        )
    }

    @After
    fun tearDown() {
        testFile.delete()
    }

    @Test
    fun `put then get round-trips the stored value`() = runTest {
        storage.put("credential_openai-work", "YmFzZTY0LWNpcGhlcnRleHQ=")
        assertEquals(
            "YmFzZTY0LWNpcGhlcnRleHQ=",
            storage.get("credential_openai-work"),
        )
    }

    @Test
    fun `get of an absent key returns null`() = runTest {
        assertNull(storage.get("credential_never-saved"))
    }

    @Test
    fun `remove deletes only the targeted key`() = runTest {
        storage.put("credential_a", "AAA=")
        storage.put("credential_b", "BBB=")
        storage.remove("credential_a")
        assertNull(storage.get("credential_a"))
        assertEquals("BBB=", storage.get("credential_b"))
    }

    @Test
    fun `clear wipes every stored key`() = runTest {
        storage.put("credential_a", "AAA=")
        storage.put("credential_b", "BBB=")
        storage.clear()
        assertNull(storage.get("credential_a"))
        assertNull(storage.get("credential_b"))
    }
}
