package com.byokai.core.network

import kotlinx.serialization.json.Json

/**
 * Shared lenient JSON configuration for provider APIs.
 *
 * - `ignoreUnknownKeys = true`: provider responses routinely grow new
 *   fields; unknown ones are dropped instead of failing the parse.
 * - `explicitNulls = false`: explicit `null`s are treated as absent so the
 *   DTO defaults apply; also keeps encoded request bodies free of `null`s.
 *
 * All provider DTOs are (de)serialized with this instance.
 */
val ProviderJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}
