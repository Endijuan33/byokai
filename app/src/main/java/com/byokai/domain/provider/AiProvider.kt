package com.byokai.domain.provider

import com.byokai.core.common.Result
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ModelInfo
import com.byokai.domain.model.ProviderCapabilities
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import kotlinx.coroutines.flow.Flow

/**
 * Adapter contract for one provider family.
 *
 * Implementations translate between the domain chat protocol and the
 * provider's HTTP API. They are pure adapters: no credential storage
 * (the key arrives per call from the credential store), no Android
 * dependencies. Concrete adapters arrive in later milestones; this
 * milestone defines the contract only.
 */
interface AiProvider {
    /** The provider family this adapter serves. */
    val providerId: ProviderId

    /** Human-readable provider name shown in the UI. */
    val displayName: String

    /** Fallback base URL prefilled when the user adds a config. */
    val defaultBaseUrl: String

    /** Feature flags for what this adapter supports. */
    val capabilities: ProviderCapabilities

    /**
     * Lists the chat models available for [config] using [apiKey].
     *
     * Used by model-listing UI and by connection tests that enumerate
     * models; adapters whose [ProviderCapabilities.modelListing] is false
     * return the config's known [ProviderConfig.modelIds] as
     * [ModelInfo] entries instead.
     */
    suspend fun listModels(config: ProviderConfig, apiKey: String): Result<List<ModelInfo>>

    /**
     * Verifies that [config] plus [apiKey] can reach the provider
     * (cheap endpoint, no model call). Returns [Result.Success] when the
     * endpoint answers with valid credentials.
     */
    suspend fun testConnection(config: ProviderConfig, apiKey: String): Result<Unit>

    /**
     * Streams a chat completion as a cold [Flow] of [ChatEvent]s:
     * one or more [ChatEvent.Delta]s, then exactly one terminal event —
     * [ChatEvent.Done] on success or [ChatEvent.Error] on failure.
     */
    fun streamChat(config: ProviderConfig, apiKey: String, request: ChatRequest): Flow<ChatEvent>
}
