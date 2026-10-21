package com.byokai.domain.provider

/**
 * Bridges [ProviderError] into [com.byokai.core.common.Result.Error], whose
 * cause must be a [Throwable].
 *
 * The suspending adapter calls ([AiProvider.listModels],
 * [AiProvider.testConnection]) return `Result.Error` carrying this wrapper
 * so callers recover the typed [error] instead of parsing message text.
 * The API key is never included in any message.
 */
class ProviderException(
    /** The typed provider failure. */
    val error: ProviderError,
    message: String = defaultMessage(error),
) : Exception(message) {

    companion object {
        private fun defaultMessage(error: ProviderError): String = when (error) {
            is ProviderError.Auth -> "Authentication failed: ${error.message}"
            is ProviderError.RateLimited -> "Rate limited: ${error.message}"
            ProviderError.Timeout -> "The request timed out"
            is ProviderError.Network ->
                "Network failure: ${error.cause.message ?: error.cause::class.simpleName}"
            is ProviderError.InvalidModel ->
                "Unknown or inaccessible model '${error.modelId}'"
            is ProviderError.Server -> "HTTP ${error.statusCode}: ${error.message}"
            is ProviderError.Parse -> "Malformed response: ${error.message}"
            is ProviderError.Config -> "Configuration problem: ${error.message}"
        }
    }
}
