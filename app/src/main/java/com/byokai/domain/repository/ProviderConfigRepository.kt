package com.byokai.domain.repository

import com.byokai.core.common.Result
import com.byokai.domain.model.ProviderConfig
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for user-configured provider connections.
 *
 * Holds everything needed to reach a provider endpoint EXCEPT the API
 * key: keys live in a separate [com.byokai.domain.security.CredentialStore]
 * keyed by [ProviderConfig.id], so configs can be persisted, exported, or
 * displayed without ever touching a secret.
 */
interface ProviderConfigRepository {

    /** Cold flow of all stored configs, in insertion order. */
    fun configs(): Flow<List<ProviderConfig>>

    /** Returns the config with [configId], or null when none exists. */
    suspend fun get(configId: String): ProviderConfig?

    /**
     * Inserts or replaces [config], keyed by [ProviderConfig.id].
     *
     * Runs [ProviderConfig.validate] first — an invalid config returns
     * [Result.Error] and is never persisted.
     */
    suspend fun save(config: ProviderConfig): Result<Unit>

    /**
     * Deletes the config with [configId]; deleting an absent id succeeds.
     *
     * Note: the matching credential is NOT deleted here — key cleanup is
     * the credential store's own operation, kept separate so config and
     * key lifetimes stay explicit.
     */
    suspend fun delete(configId: String): Result<Unit>
}
