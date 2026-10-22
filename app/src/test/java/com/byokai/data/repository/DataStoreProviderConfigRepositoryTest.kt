package com.byokai.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import com.byokai.core.common.Result
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for [DataStoreProviderConfigRepository] against a
 * real DataStore backed by a temporary file. Runs on the JVM — no
 * device needed. Mirrors [com.byokai.data.local.datastore.SettingsDataStoreTest].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreProviderConfigRepositoryTest {

    private lateinit var testFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreProviderConfigRepository

    @Before
    fun setUp() {
        testFile = File.createTempFile("byokai-provider-configs-test", ".preferences_pb")
        testFile.delete()
        dataStore = DataStoreFactory.create(
            serializer = PreferencesFileSerializer,
            produceFile = { testFile },
        )
        repository = DataStoreProviderConfigRepository(dataStore)
    }

    @After
    fun tearDown() {
        testFile.delete()
    }

    @Test
    fun `save then get round-trips the config`() = runTest {
        val config = validConfig()

        assertTrue(repository.save(config) is Result.Success)
        assertEquals(config, repository.get(config.id))
    }

    @Test
    fun `configs flow emits saved configs in insertion order`() = runTest {
        val first = validConfig("cfg-1")
        val second = validConfig("cfg-2", displayName = "Second")

        assertTrue(repository.save(first) is Result.Success)
        assertTrue(repository.save(second) is Result.Success)

        assertEquals(listOf(first, second), repository.configs().first())
    }

    @Test
    fun `save upserts a config with an existing id`() = runTest {
        val config = validConfig()
        repository.save(config)

        val renamed = config.copy(displayName = "Renamed")
        assertTrue(repository.save(renamed) is Result.Success)

        assertEquals(renamed, repository.get(config.id))
        assertEquals(listOf(renamed), repository.configs().first())
    }

    @Test
    fun `delete removes the config`() = runTest {
        val config = validConfig()
        repository.save(config)

        assertTrue(repository.delete(config.id) is Result.Success)

        assertNull(repository.get(config.id))
        assertEquals(emptyList<ProviderConfig>(), repository.configs().first())
    }

    @Test
    fun `save rejects an invalid config and persists nothing`() = runTest {
        // Cleartext HTTP on a non-loopback host fails EndpointValidator.
        val invalid = validConfig().copy(baseUrl = "http://example.com")

        val result = repository.save(invalid)

        assertTrue(result is Result.Error)
        assertNull(repository.get(invalid.id))
        assertEquals(emptyList<ProviderConfig>(), repository.configs().first())
    }

    @Test
    fun `corrupt json degrades to an empty list instead of crashing`() = runTest {
        dataStore.edit { prefs ->
            prefs[DataStoreProviderConfigRepository.KEY_CONFIGS_JSON] = "{not valid json"
        }

        assertEquals(emptyList<ProviderConfig>(), repository.configs().first())
        assertNull(repository.get("cfg-1"))
    }

    @Test
    fun `legacy base urls migrate to the v1 convention on read`() {
        val cases = mapOf(
            "https://api.openai.com" to "https://api.openai.com/v1",
            "https://api.anthropic.com" to "https://api.anthropic.com/v1",
            "https://api.deepseek.com" to "https://api.deepseek.com/v1",
            "https://api.x.ai" to "https://api.x.ai/v1",
            "http://localhost:11434" to "http://localhost:11434/v1",
            // Trailing-slash variant of a legacy default still migrates.
            "https://api.openai.com/" to "https://api.openai.com/v1",
        )
        for ((old, expected) in cases) {
            val migrated = DataStoreProviderConfigRepository.migrateLegacyBaseUrl(
                validConfig().copy(baseUrl = old),
            )
            assertEquals(expected, migrated.baseUrl)
        }
    }

    @Test
    fun `custom and already-migrated base urls are never touched`() {
        val untouched = listOf(
            "https://api.openai.com/v1",
            "https://api.groq.com/openai/v1",
            "https://api.perplexity.ai",
            "https://my-proxy.example.com/v1",
            "https://api.openai.com.evil.example.com",
            "http://localhost:11434/v1",
        )
        for (url in untouched) {
            val result = DataStoreProviderConfigRepository.migrateLegacyBaseUrl(
                validConfig().copy(baseUrl = url),
            )
            assertEquals(url, result.baseUrl)
        }
    }

    @Test
    fun `stored legacy config reads back migrated and persists on save`() = runTest {
        // Simulate a pre-v0.8.1 payload written directly to DataStore.
        val legacyJson =
            """[{"id":"cfg-1","providerId":"OPENAI","displayName":"Test OpenAI","baseUrl":"https://api.openai.com","modelIds":["gpt-4o"],"defaultModelId":"gpt-4o","createdAt":1700000000000,"updatedAt":1700000000000}]"""
        dataStore.edit { prefs ->
            prefs[DataStoreProviderConfigRepository.KEY_CONFIGS_JSON] = legacyJson
        }

        val read = repository.get("cfg-1")!!
        assertEquals("https://api.openai.com/v1", read.baseUrl)

        // Saving an unrelated config persists the migrated URL too.
        assertTrue(repository.save(validConfig("cfg-2")) is Result.Success)
        assertEquals(
            "https://api.openai.com/v1",
            repository.get("cfg-1")!!.baseUrl,
        )
    }

    private fun validConfig(
        id: String = "cfg-1",
        displayName: String = "Test OpenAI",
    ): ProviderConfig = ProviderConfig(
        id = id,
        providerId = ProviderId.OPENAI,
        displayName = displayName,
        baseUrl = "https://api.openai.com/v1",
        modelIds = listOf("gpt-4o", "gpt-4o-mini"),
        defaultModelId = "gpt-4o",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
    )
}
