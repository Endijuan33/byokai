package com.byokai.di

import android.content.Context
import com.byokai.core.network.HttpClientFactory
import com.byokai.data.chat.ChatRepositoryImpl
import com.byokai.data.local.datastore.ProviderConfigHolder
import com.byokai.data.local.datastore.SettingsDataStoreHolder
import com.byokai.data.local.db.ByokaiDatabase
import com.byokai.data.provider.anthropic.AnthropicAdapter
import com.byokai.data.provider.anthropic.AnthropicCompatibleAdapter
import com.byokai.data.provider.gemini.GeminiAdapter
import com.byokai.data.provider.openai.OpenAiAdapter
import com.byokai.data.provider.openai.OpenAiCompatibleAdapter
import com.byokai.data.repository.DataStoreProviderConfigRepository
import com.byokai.data.repository.SettingsRepositoryImpl
import com.byokai.data.repository.SqliteConversationRepository
import com.byokai.data.security.AndroidKeysetAeadProvider
import com.byokai.data.security.CredentialStorageHolder
import com.byokai.data.export.ConversationExportManager
import com.byokai.data.repository.SqlitePromptRepository
import com.byokai.data.security.TinkCredentialStore
import com.byokai.domain.chat.ChatRepository
import com.byokai.domain.provider.ProviderRegistry
import com.byokai.domain.repository.ConversationRepository
import com.byokai.domain.repository.PromptRepository
import com.byokai.domain.repository.ProviderConfigRepository
import com.byokai.domain.repository.SettingsRepository
import com.byokai.domain.security.CredentialStore
import okhttp3.OkHttpClient

/**
 * Manual dependency graph (interim).
 *
 * Hilt is deferred until KSP supports Kotlin 2.4.x (see docs/ROADMAP.md),
 * so dependencies are wired here as lazy singletons. The Application owns
 * exactly one [AppContainer]; screens resolve dependencies from it via
 * `context.applicationContext as ByokaiApplication`.
 *
 * When Hilt returns, this class is replaced by Hilt modules — the
 * repository interfaces and their constructor signatures stay unchanged,
 * so call sites are unaffected.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(SettingsDataStoreHolder.get(appContext))
    }

    /**
     * Encrypted API-key storage (Milestone 3B).
     *
     * The AEAD key lives in a Tink keyset whose file is encrypted by an
     * Android Keystore master key; ciphertexts are persisted in a
     * dedicated DataStore file that is excluded from device backups.
     * Plaintext keys never leave this store except in memory at the
     * moment a request is signed.
     */
    val credentialStore: CredentialStore by lazy {
        TinkCredentialStore(
            AndroidKeysetAeadProvider(appContext),
            CredentialStorageHolder.get(appContext),
        )
    }

    /**
     * Shared HTTP client for provider adapters (Milestone 3C).
     */
    val httpClient: OkHttpClient by lazy { HttpClientFactory.create() }

    /**
     * Adapters for the provider families the app can talk to.
     *
     * OpenAI first (Milestone 3C); Anthropic and Gemini added in
     * Milestone 3D. The generic OpenAI-compatible and
     * Anthropic-compatible adapters (Milestone 8) reuse the first-party
     * wire implementations for third-party and self-hosted endpoints.
     * New adapters register here.
     */
    val providerRegistry: ProviderRegistry by lazy {
        ProviderRegistry().apply {
            register(OpenAiAdapter(httpClient))
            register(AnthropicAdapter(httpClient))
            register(GeminiAdapter(httpClient))
            register(OpenAiCompatibleAdapter(httpClient))
            register(AnthropicCompatibleAdapter(httpClient))
        }
    }

    /**
     * User-configured provider connections (Milestone 3C).
     *
     * Keys are stored separately in [credentialStore]; this repository
     * never sees key material.
     */
    val providerConfigRepository: ProviderConfigRepository by lazy {
        DataStoreProviderConfigRepository(ProviderConfigHolder.get(appContext))
    }

    /**
     * Chat entry point for the UI: resolves config + key + adapter per
     * request and streams adapter events.
     */
    val chatRepository: ChatRepository by lazy {
        ChatRepositoryImpl(providerRegistry, providerConfigRepository, credentialStore)
    }

    /**
     * Persisted conversation history (Milestone 3E).
     *
     * Hand-rolled SQLite via [ByokaiDatabase] (no Room — no KSP for
     * Kotlin 2.4.x). Holds conversation text and metadata only; API
     * keys are never stored here — they live exclusively in
     * [credentialStore].
     */
    val conversationRepository: ConversationRepository by lazy {
        SqliteConversationRepository(ByokaiDatabase.get(appContext))
    }

    /**
     * Reusable system-prompt templates (Milestone 4C).
     *
     * Backed by the `prompts` table (schema v2). Like
     * [conversationRepository], holds user text only — never API keys.
     */
    val promptRepository: PromptRepository by lazy {
        SqlitePromptRepository(ByokaiDatabase.get(appContext))
    }

    /**
     * Conversation export/import as versioned JSON (Milestone 4E).
     *
     * Carries conversation text and metadata only — the underlying
     * [conversationRepository] has no access to API keys or other
     * credentials, so they cannot leak into an export.
     */
    val exportManager: ConversationExportManager by lazy {
        ConversationExportManager(conversationRepository)
    }
}
