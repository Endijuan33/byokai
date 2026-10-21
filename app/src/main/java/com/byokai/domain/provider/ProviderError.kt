package com.byokai.domain.provider

/**
 * Typed failures a provider adapter can report.
 *
 * Carried by [com.byokai.domain.chat.ChatEvent.Error] and returned inside
 * [com.byokai.core.common.Result.Error] from suspending adapter calls, so
 * callers can react to the failure class (e.g. prompt for a new key on
 * [Auth]) instead of parsing message text.
 */
sealed interface ProviderError {
    /** The API key was rejected or is missing (HTTP 401/403). */
    data class Auth(val message: String) : ProviderError

    /**
     * The provider throttled the request (HTTP 429).
     *
     * @property retryAfterSeconds the provider's Retry-After hint, or null
     * when none was given.
     */
    data class RateLimited(val message: String, val retryAfterSeconds: Long?) : ProviderError

    /** The request timed out before a response arrived. */
    data object Timeout : ProviderError

    /** Transport-level failure (DNS, TLS, connection refused, ...). */
    data class Network(val cause: Throwable) : ProviderError

    /** The requested model id is unknown or not accessible with this key. */
    data class InvalidModel(val modelId: String) : ProviderError

    /** Any other HTTP error status. */
    data class Server(val statusCode: Int, val message: String) : ProviderError

    /** The response could not be parsed (unexpected or malformed payload). */
    data class Parse(val message: String) : ProviderError

    /**
     * The request could not be routed: the provider configuration is
     * missing, or no adapter is registered for its provider family.
     */
    data class Config(val message: String) : ProviderError
}
