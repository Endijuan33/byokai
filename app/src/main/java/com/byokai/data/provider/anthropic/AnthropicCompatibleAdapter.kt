package com.byokai.data.provider.anthropic

import com.byokai.domain.model.ProviderCapabilities
import com.byokai.domain.model.ProviderId
import com.byokai.domain.repository.AttachmentStorage
import okhttp3.OkHttpClient

/**
 * [com.byokai.domain.provider.AiProvider] for any third-party endpoint
 * that speaks the Anthropic Messages API wire protocol
 * (`POST {baseUrl}/messages` with SSE streaming, `x-api-key` auth,
 * `anthropic-version` header).
 *
 * Reuses the entire [AnthropicAdapter] wire implementation; only the
 * identity differs. There is no fixed base URL — the user types a custom
 * endpoint, pasted exactly as documented including the `/v1` prefix when
 * the endpoint's docs include one. Like the first-party adapter there is no model-listing
 * endpoint, so models are always entered manually; the connection test
 * performs a minimal real completion (micro-quota by design).
 *
 * Security: identical guarantees to [AnthropicAdapter] — the key travels
 * only in the `x-api-key` header, the base URL is re-validated with
 * `EndpointValidator` on every call (HTTPS-only except loopback,
 * userinfo rejected, cloud metadata address blocked), and redirects stay
 * disabled.
 *
 * Vision-capable by protocol inheritance: image attachments are sent as
 * Anthropic content blocks by [AnthropicAdapter]; whether a given
 * endpoint's model accepts them is up to that endpoint. DI note: the
 * constructor takes [AttachmentStorage], passed straight through to
 * the parent.
 */
class AnthropicCompatibleAdapter(
    client: OkHttpClient,
    attachmentStorage: AttachmentStorage,
) : AnthropicAdapter(client, attachmentStorage) {

    override val providerId: ProviderId = ProviderId.ANTHROPIC_COMPATIBLE
    override val displayName: String = "Anthropic-compatible"
    override val defaultBaseUrl: String = ""
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        systemPrompt = true,
        modelListing = false,
        vision = true,
    )
}
