package com.byokai.domain.chat

import com.byokai.domain.model.ChatMessage

/**
 * A chat completion request bound to one provider config and model.
 *
 * @property providerConfigId the [com.byokai.domain.model.ProviderConfig.id]
 * whose endpoint and (stored) key serve this request.
 * @property modelId the provider-side model identifier to generate with.
 * @property messages the conversation turns sent to the model.
 * @property systemPrompt optional system instruction, honored only when
 * the adapter's
 * [com.byokai.domain.model.ProviderCapabilities.systemPrompt] is true.
 */
data class ChatRequest(
    val providerConfigId: String,
    val modelId: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String? = null,
)
