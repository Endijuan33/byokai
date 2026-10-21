package com.byokai.ui.common

import com.byokai.R
import com.byokai.core.common.UiText
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderException

/**
 * Maps a [ProviderError] to localizable UI text.
 *
 * Every branch resolves to a string resource — never to raw provider
 * output alone — so copy stays localizable (EN/ID) and consistent.
 * Provider-supplied detail ([ProviderError.message],
 * [ProviderError.cause]'s message) is passed as a format argument where
 * it helps the user act; key material never appears here because keys
 * are never part of a [ProviderError].
 */
fun ProviderError.userMessage(): UiText = when (this) {
    is ProviderError.Auth ->
        UiText.Res(R.string.error_provider_auth)

    is ProviderError.RateLimited ->
        if (retryAfterSeconds != null) {
            UiText.Res(R.string.error_provider_rate_limited_retry, listOf(retryAfterSeconds))
        } else {
            UiText.Res(R.string.error_provider_rate_limited)
        }

    is ProviderError.Timeout ->
        UiText.Res(R.string.error_provider_timeout)

    is ProviderError.Network ->
        UiText.Res(
            R.string.error_provider_network,
            listOf(cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName),
        )

    is ProviderError.InvalidModel ->
        UiText.Res(R.string.error_provider_invalid_model, listOf(modelId))

    is ProviderError.Server ->
        UiText.Res(R.string.error_provider_server, listOf(statusCode, message))

    is ProviderError.Parse ->
        UiText.Res(R.string.error_provider_parse, listOf(message))

    is ProviderError.Config ->
        UiText.Res(R.string.error_provider_config, listOf(message))
}

/**
 * Converts any failure thrown or returned by a provider adapter into
 * UI text.
 *
 * Adapter suspending calls return [ProviderError] wrapped in
 * [ProviderException] inside [com.byokai.core.common.Result.Error];
 * this unwraps that case and falls back to a generic message for
 * anything else (e.g. an unexpected [RuntimeException] from adapter
 * internals).
 */
fun Throwable.toProviderUiText(): UiText =
    (this as? ProviderException)?.error?.userMessage()
        ?: UiText.Res(R.string.error_unknown)
