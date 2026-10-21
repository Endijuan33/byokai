package com.byokai.domain.provider

import com.byokai.domain.model.ProviderId

/**
 * Registry of the [AiProvider] adapters available at runtime.
 *
 * Each [ProviderId] may be registered at most once; registering the same
 * id twice is a wiring bug and fails fast with
 * [IllegalArgumentException]. Registration order is preserved by [all].
 */
class ProviderRegistry {
    private val providers = LinkedHashMap<ProviderId, AiProvider>()

    /**
     * Registers [provider].
     *
     * @throws IllegalArgumentException when an adapter for
     * [AiProvider.providerId] is already registered.
     */
    fun register(provider: AiProvider) {
        val id = provider.providerId
        require(!providers.containsKey(id)) {
            "Provider already registered: $id"
        }
        providers[id] = provider
    }

    /** Returns the adapter for [id], or null when none is registered. */
    fun get(id: ProviderId): AiProvider? = providers[id]

    /** Returns all registered adapters in registration order. */
    fun all(): List<AiProvider> = providers.values.toList()
}
