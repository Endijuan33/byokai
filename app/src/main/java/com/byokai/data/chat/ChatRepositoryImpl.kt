package com.byokai.data.chat

import com.byokai.core.common.Result
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRepository
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderRegistry
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.security.CredentialException
import com.byokai.domain.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * [ChatRepository] that resolves the config, key, and adapter for each
 * request and delegates streaming to the matching adapter.
 *
 * Resolution order, each failure emitted as a single terminal
 * [ChatEvent.Error]:
 * 1. the [ChatRequest.providerConfigId] must exist;
 * 2. [ChatRequest.modelId] must be one of the config's
 *    [com.byokai.domain.model.ProviderConfig.modelIds];
 * 3. a key must be stored for the config id (a storage failure maps to
 *    [ProviderError.Auth] when the key was invalidated or undecryptable,
 *    [ProviderError.Network] otherwise);
 * 4. an adapter must be registered for the config's provider family.
 *
 * The returned [Flow] is cold: each collection performs one request, and
 * the flow never throws — adapter failures surface as
 * [ChatEvent.Error]. Preventing duplicate sends is the UI layer's job
 * (the send control stays disabled while a generation is in flight).
 *
 * A successful-but-empty completion ([ChatEvent.Done] with blank text)
 * is normalized to [ChatEvent.Error] with [ProviderError.EmptyResponse]
 * so callers never have to render or persist an empty bubble.
 *
 * No key material is included in errors or logs — only static messages
 * and non-secret identifiers.
 */
class ChatRepositoryImpl(
    private val registry: ProviderRegistry,
    private val configs: ProviderConfigRepository,
    private val credentials: CredentialStore,
) : ChatRepository {

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = flow {
        val config = configs.get(request.providerConfigId)
            ?: run {
                emit(ChatEvent.Error(ProviderError.Config("Provider configuration not found")))
                return@flow
            }
        if (request.modelId !in config.modelIds) {
            emit(ChatEvent.Error(ProviderError.InvalidModel(request.modelId)))
            return@flow
        }
        val apiKey = when (val keyResult = credentials.load(config.id)) {
            is Result.Success -> keyResult.data ?: run {
                emit(ChatEvent.Error(ProviderError.Auth("No API key saved for this provider")))
                return@flow
            }
            is Result.Error -> run {
                emit(ChatEvent.Error(mapCredentialError(keyResult.cause)))
                return@flow
            }
            is Result.Loading -> return@flow
        }
        val adapter = registry.get(config.providerId)
            ?: run {
                emit(ChatEvent.Error(ProviderError.Config("No adapter registered for provider")))
                return@flow
            }
        adapter.streamChat(config, apiKey, request).collect { event ->
            if (event is ChatEvent.Done && event.fullText.isBlank()) {
                emit(ChatEvent.Error(ProviderError.EmptyResponse))
            } else {
                emit(event)
            }
        }
    }

    private fun mapCredentialError(cause: Throwable): ProviderError = when (cause) {
        is CredentialException.KeyInvalidated ->
            ProviderError.Auth("Stored API key is no longer available; please re-enter it")
        is CredentialException.DecryptionFailed ->
            ProviderError.Auth("Stored API key could not be decrypted; please re-enter it")
        else -> ProviderError.Network(cause)
    }
}
