package com.byokai.data.provider.openai

import com.byokai.core.common.Result
import com.byokai.domain.model.ModelInfo
import com.byokai.domain.model.ProviderCapabilities
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderException
import okhttp3.OkHttpClient

/**
 * [com.byokai.domain.provider.AiProvider] for any third-party endpoint
 * that speaks the OpenAI chat-completions wire protocol
 * (`POST {baseUrl}/v1/chat/completions` with SSE streaming).
 *
 * Reuses the entire [OpenAiAdapter] wire implementation; only the
 * identity differs. There is no fixed base URL — the user picks one from
 * the provider catalog or types a custom endpoint.
 *
 * Model listing is best-effort: endpoints that do not implement
 * `GET /v1/models` (HTTP 404, or a body that is not the expected
 * envelope) fall back to the config's manually entered model ids instead
 * of failing. Authentication, rate-limit, network, and timeout failures
 * are propagated unchanged so a bad key or a dead endpoint is never
 * silently masked as "no models".
 *
 * Security: identical guarantees to [OpenAiAdapter] — the key travels
 * only in the `Authorization: Bearer` header, the base URL is
 * re-validated with `EndpointValidator` on every call (HTTPS-only except
 * loopback, userinfo rejected, cloud metadata address blocked), and
 * redirects stay disabled.
 */
class OpenAiCompatibleAdapter(client: OkHttpClient) : OpenAiAdapter(client) {

    override val providerId: ProviderId = ProviderId.OPENAI_COMPATIBLE
    override val displayName: String = "OpenAI-compatible"
    override val defaultBaseUrl: String = ""
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        systemPrompt = true,
        modelListing = true,
    )

    override suspend fun listModels(
        config: ProviderConfig,
        apiKey: String,
    ): Result<List<ModelInfo>> {
        return when (val result = super.listModels(config, apiKey)) {
            is Result.Success -> result
            is Result.Error -> {
                // HTTP 404 normalizes to InvalidModel; a non-envelope body
                // normalizes to Parse. Both mean "this endpoint does not
                // implement /v1/models" — fall back to manual model ids.
                val error = (result.cause as? ProviderException)?.error
                if (error is ProviderError.InvalidModel || error is ProviderError.Parse) {
                    Result.Success(
                        config.modelIds
                            .filter { it.isNotBlank() }
                            .map { ModelInfo(id = it, displayName = it) },
                    )
                } else {
                    result
                }
            }
            is Result.Loading -> result
        }
    }
}
