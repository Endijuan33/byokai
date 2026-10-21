package com.byokai.data.chat

import com.byokai.core.common.Result
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ModelInfo
import com.byokai.domain.model.ProviderCapabilities
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.AiProvider
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderRegistry
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialException
import com.byokai.domain.security.CredentialStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ChatRepositoryImpl] with faked config repository,
 * credential store, and provider adapter. Runs on the JVM — no device
 * needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositoryImplTest {

    private val config = ProviderConfig(
        id = "cfg-1",
        providerId = ProviderId.OPENAI,
        displayName = "Test",
        baseUrl = "https://api.openai.com/v1",
        modelIds = listOf("gpt-4o"),
        defaultModelId = "gpt-4o",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private class FakeProviderConfigRepository(
        private var configs: Map<String, ProviderConfig> = emptyMap(),
    ) : ProviderConfigRepository {
        override fun configs(): Flow<List<ProviderConfig>> =
            flowOf(configs.values.toList())

        override suspend fun get(configId: String): ProviderConfig? =
            configs[configId]

        override suspend fun save(config: ProviderConfig): Result<Unit> {
            configs = configs + (config.id to config)
            return Result.Success(Unit)
        }

        override suspend fun delete(configId: String): Result<Unit> {
            configs = configs - configId
            return Result.Success(Unit)
        }
    }

    private class FakeCredentialStore(
        private var result: Result<String?> = Result.Success(null),
    ) : CredentialStore {
        override suspend fun save(configId: String, apiKey: String): Result<Unit> =
            Result.Success(Unit)

        override suspend fun load(configId: String): Result<String?> = result

        override suspend fun delete(configId: String): Result<Unit> =
            Result.Success(Unit)

        override suspend fun clearAll(): Result<Unit> = Result.Success(Unit)
    }

    private class FakeAdapter(
        var events: List<ChatEvent> = emptyList(),
    ) : AiProvider {
        var lastApiKey: String? = null

        override val providerId: ProviderId = ProviderId.OPENAI
        override val displayName: String = "Fake"
        override val defaultBaseUrl: String = "https://fake.example/v1"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(streaming = true)

        override suspend fun listModels(
            config: ProviderConfig,
            apiKey: String,
        ): Result<List<ModelInfo>> = Result.Success(emptyList())

        override suspend fun testConnection(
            config: ProviderConfig,
            apiKey: String,
        ): Result<Unit> = Result.Success(Unit)

        override fun streamChat(
            config: ProviderConfig,
            apiKey: String,
            request: ChatRequest,
        ): Flow<ChatEvent> {
            lastApiKey = apiKey
            return events.asFlow()
        }
    }

    private fun repository(
        configs: Map<String, ProviderConfig> = mapOf(config.id to config),
        credentialResult: Result<String?> = Result.Success("sk-test"),
        adapter: FakeAdapter = FakeAdapter(),
    ): Pair<ChatRepositoryImpl, FakeAdapter> {
        val registry = ProviderRegistry().apply { register(adapter) }
        val impl = ChatRepositoryImpl(
            registry,
            FakeProviderConfigRepository(configs),
            FakeCredentialStore(credentialResult),
        )
        return impl to adapter
    }

    private fun request(
        modelId: String = "gpt-4o",
        configId: String = "cfg-1",
    ): ChatRequest = ChatRequest(
        providerConfigId = configId,
        modelId = modelId,
        messages = listOf(ChatMessage(ChatRole.USER, "Hello")),
    )

    @Test
    fun `happy path emits adapter events in order`() = runTest {
        val events = listOf(
            ChatEvent.Delta("Hel"),
            ChatEvent.Delta("lo"),
            ChatEvent.Done("Hello"),
        )
        val (impl, adapter) = repository(adapter = FakeAdapter(events))

        val collected = impl.streamChat(request()).toList()

        assertEquals(events, collected)
        assertEquals("sk-test", adapter.lastApiKey)
    }

    @Test
    fun `unknown config id emits Error with Config`() = runTest {
        val (impl, _) = repository()

        val collected = impl.streamChat(request(configId = "nope")).toList()

        assertEquals(1, collected.size)
        val error = collected.single() as ChatEvent.Error
        assertTrue(error.error is ProviderError.Config)
    }

    @Test
    fun `model id outside config modelIds emits Error with InvalidModel`() = runTest {
        val (impl, _) = repository()

        val collected = impl.streamChat(request(modelId = "gpt-9")).toList()

        assertEquals(1, collected.size)
        val error = collected.single() as ChatEvent.Error
        assertTrue(error.error is ProviderError.InvalidModel)
        assertEquals("gpt-9", (error.error as ProviderError.InvalidModel).modelId)
    }

    @Test
    fun `missing api key emits Error with Auth`() = runTest {
        val (impl, _) = repository(credentialResult = Result.Success(null))

        val collected = impl.streamChat(request()).toList()

        assertEquals(1, collected.size)
        val error = collected.single() as ChatEvent.Error
        assertTrue(error.error is ProviderError.Auth)
    }

    @Test
    fun `invalidated credential key emits Error with Auth`() = runTest {
        val (impl, _) = repository(
            credentialResult = Result.Error(CredentialException.KeyInvalidated()),
        )

        val collected = impl.streamChat(request()).toList()

        assertEquals(1, collected.size)
        val error = collected.single() as ChatEvent.Error
        assertTrue(error.error is ProviderError.Auth)
    }
}
