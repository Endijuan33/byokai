package com.byokai.domain.model

/**
 * A single chat model offered by a provider.
 *
 * @property id the provider-side model identifier sent in chat requests.
 * @property displayName the human-readable label shown in the UI.
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
)
