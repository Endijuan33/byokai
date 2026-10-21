package com.byokai.domain.model

/**
 * Feature flags advertised by a provider adapter.
 *
 * Every capability defaults to `false`; adapters opt in to exactly what
 * their API supports so the UI never offers an unsupported feature
 * (e.g. model listing for a provider with no such endpoint).
 *
 * @property streaming true when the provider can stream chat responses.
 * @property systemPrompt true when a system prompt is accepted.
 * @property modelListing true when the provider exposes a model-list endpoint.
 * @property vision true when image input is supported.
 * @property toolCalling true when function/tool calls are supported.
 */
data class ProviderCapabilities(
    val streaming: Boolean = false,
    val systemPrompt: Boolean = false,
    val modelListing: Boolean = false,
    val vision: Boolean = false,
    val toolCalling: Boolean = false,
)
