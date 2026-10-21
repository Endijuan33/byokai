package com.byokai.domain.model

/**
 * A single chat model offered by a provider.
 *
 * @property id the provider-side model identifier sent in chat requests.
 * @property displayName the human-readable label shown in the UI.
 * @property contextWindow the model's context window in tokens, or null
 * when unknown. Adapters that cannot determine it (currently all of
 * them — model listings carry no window data) leave it null, and
 * [com.byokai.core.chat.ContextPolicy] falls back to conservative
 * caps. Never guess a value: an overstated window silently drops
 * history mid-request when the provider truncates server-side.
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextWindow: Int? = null,
)
