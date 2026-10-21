package com.byokai.core.network

import java.net.URI

/**
 * Outcome of validating a provider base URL.
 */
sealed interface EndpointValidation {
    /**
     * The URL is acceptable.
     *
     * @property warnings non-blocking notes, e.g. that cleartext HTTP
     * was allowed for a loopback host.
     */
    data class Valid(val warnings: List<String> = emptyList()) : EndpointValidation

    /**
     * The URL must not be used as a provider endpoint.
     *
     * @property reason human-readable explanation of the rejection.
     */
    data class Invalid(val reason: String) : EndpointValidation
}

/**
 * Validates provider base URLs before they are stored or called.
 *
 * Guards against the classic config mistakes and SSRF footguns:
 * blank input, unparseable URIs, missing hosts, embedded credentials
 * (`https://user@host`), non-HTTPS schemes, and the cloud metadata
 * address `169.254.169.254`. Cleartext HTTP is accepted ONLY for
 * loopback hosts (`127.0.0.1`, `::1`, `localhost`) — for local dev
 * servers — and is flagged with a warning.
 *
 * Pure JVM: uses [java.net.URI] only, no Android APIs.
 */
object EndpointValidator {

    /** Warning attached when cleartext HTTP is allowed for loopback. */
    const val CLEARTEXT_LOOPBACK_WARNING = "cleartext loopback"

    private val loopbackHosts = setOf("127.0.0.1", "::1", "localhost")
    private const val METADATA_IP = "169.254.169.254"

    /**
     * Validates [rawUrl] as a provider base URL.
     *
     * @return [EndpointValidation.Valid] when the URL may be used,
     * [EndpointValidation.Invalid] with a reason otherwise.
     */
    fun validate(rawUrl: String): EndpointValidation {
        if (rawUrl.isBlank()) {
            return EndpointValidation.Invalid("URL is blank")
        }

        val uri = try {
            URI(rawUrl.trim())
        } catch (e: Exception) {
            return EndpointValidation.Invalid("Not a valid URI: ${e.message}")
        }

        val host = uri.host?.trim()?.lowercase()
            ?.removePrefix("[")?.removeSuffix("]")
            ?.takeIf { it.isNotEmpty() }
            ?: return EndpointValidation.Invalid("URL has no host")

        if (host == METADATA_IP) {
            return EndpointValidation.Invalid(
                "Host $METADATA_IP is reserved (cloud metadata service)",
            )
        }

        if (uri.userInfo != null) {
            return EndpointValidation.Invalid("URL must not contain credentials (userinfo)")
        }

        return when (uri.scheme?.lowercase()) {
            "https" -> EndpointValidation.Valid()
            "http" ->
                if (host in loopbackHosts) {
                    EndpointValidation.Valid(listOf(CLEARTEXT_LOOPBACK_WARNING))
                } else {
                    EndpointValidation.Invalid("HTTP is only allowed for loopback hosts")
                }
            else -> EndpointValidation.Invalid(
                "Scheme must be https, got '${uri.scheme}'",
            )
        }
    }
}
