package com.byokai.core.network

import kotlinx.serialization.json.Json

/**
 * Shared lenient JSON configuration for provider APIs.
 *
 * - `ignoreUnknownKeys = true`: provider responses routinely grow new
 *   fields; unknown ones are dropped instead of failing the parse.
 * - `explicitNulls = false`: explicit `null`s are treated as absent so the
 *   DTO defaults apply; also keeps encoded request bodies free of `null`s.
 * - `encodeDefaults = true`: since kotlinx-serialization 1.8.0 the default
 *   is `false`, which would silently drop request fields that carry their
 *   default value — e.g. `ChatCompletionsRequest.stream = true`. The
 *   OpenAI API needs `stream:true` on the wire, so defaults must be
 *   encoded.
 *
 * All provider DTOs are (de)serialized with this instance.
 */
val ProviderJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}
