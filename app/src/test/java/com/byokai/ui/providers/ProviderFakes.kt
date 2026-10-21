package com.byokai.ui.providers

import com.byokai.core.common.Result
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ModelInfo
import com.byokai.domain.model.ProviderCapabilities
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.AiProvider
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

/**
 * In-memory fake of [ProviderConfigRepository] for ViewModel tests.
 * Runs [ProviderConfig.validate] on save, mirroring the real contract.
 */
class InMemoryConfigRepo(
    initial: List<ProviderConfig> = emptyList(),
) : ProviderConfigRepository {

    private val _configs = MutableStateFlow(initial)
    var failSaves: Boolean = false

    override fun configs(): Flow<List<ProviderConfig>> = _configs.asStateFlow()

    override suspend fun get(configId: String): ProviderConfig? =
        _configs.value.firstOrNull { it.id == configId }

    override suspend fun save(config: ProviderConfig): Result<Unit> {
        if (failSaves) return Result.Error(RuntimeException("simulated IO failure"))
        val validation = config.validate()
        if (validation is Result.Error) return validation
        _configs.value = _configs.value.filterNot { it.id == config.id } + config
        return Result.Success(Unit)
    }

    override suspend fun delete(configId: String): Result<Unit> {
        _configs.value = _configs.value.filterNot { it.id == configId }
        return Result.Success(Unit)
    }
}

/**
 * In-memory fake of [CredentialStore] for ViewModel tests.
 * Stores keys in plain memory — test-only, never used in production.
 */
class FakeCredentialStore : CredentialStore {

    private val keys = mutableMapOf<String, String>()

    override suspend fun save(configId: String, apiKey: String): Result<Unit> =
        if (apiKey.isBlank()) {
            Result.Error(IllegalArgumentException("apiKey must not be blank"))
        } else {
            keys[configId] = apiKey
            Result.Success(Unit)
        }

    override suspend fun load(configId: String): Result<String?> =
        Result.Success(keys[configId])

    override suspend fun delete(configId: String): Result<Unit> {
        keys.remove(configId)
        return Result.Success(Unit)
    }

    override suspend fun clearAll(): Result<Unit> {
        keys.clear()
        return Result.Success(Unit)
    }
}

/**
 * Scriptable fake of [AiProvider] for ViewModel tests.
 * No network; [testResult] and [modelList] drive the suspending calls.
 */
class FakeAdapter(
    override val providerId: ProviderId = ProviderId.OPENAI,
    override val displayName: String = "FakeOpenAI",
    override val defaultBaseUrl: String = "https://fake.example.com",
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        systemPrompt = true,
        modelListing = true,
    ),
) : AiProvider {

    var testResult: Result<Unit> = Result.Success(Unit)
    var modelList: List<ModelInfo> = listOf(
        ModelInfo("m-1", "m-1"),
        ModelInfo("m-2", "m-2"),
    )
    var listModelsResult: Result<List<ModelInfo>>? = null

    override suspend fun listModels(
        config: ProviderConfig,
        apiKey: String,
    ): Result<List<ModelInfo>> = listModelsResult ?: Result.Success(modelList)

    override suspend fun testConnection(
        config: ProviderConfig,
        apiKey: String,
    ): Result<Unit> = testResult

    override fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        request: ChatRequest,
    ): Flow<ChatEvent> = flow { }
}
