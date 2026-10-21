package com.byokai.data.provider.gemini

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Google Gemini API (`POST /v1beta/models/{id}:streamGenerateContent`,
 * `GET /v1beta/models`).
 *
 * Every field carries a default so a response that omits it still parses;
 * unknown fields are dropped by [com.byokai.core.network.ProviderJson].
 * Field names follow the API's camelCase exactly.
 */

/** Request body for `POST /v1beta/models/{id}:streamGenerateContent`. */
@Serializable
data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiSystemInstruction? = null,
)

/** System instruction wrapper; omitted entirely when null. */
@Serializable
data class GeminiSystemInstruction(
    val parts: List<GeminiPart> = emptyList(),
)

/** One conversation turn inside a request. Gemini roles are "user" and "model". */
@Serializable
data class GeminiContent(
    val role: String,
    val parts: List<GeminiPart> = emptyList(),
)

/** One text part of a request content or system instruction. */
@Serializable
data class GeminiPart(
    val text: String = "",
)

/** One SSE `data:` payload of a streamed Gemini response. */
@Serializable
data class GeminiStreamChunk(
    val candidates: List<GeminiCandidate> = emptyList(),
)

/** One candidate inside a streamed chunk. */
@Serializable
data class GeminiCandidate(
    val content: GeminiChunkContent? = null,
)

/**
 * The content of a streamed candidate. [parts] is nullable because the API
 * may omit it (or send an empty array) on non-text chunks — callers must
 * handle nulls instead of assuming text is present.
 */
@Serializable
data class GeminiChunkContent(
    val parts: List<GeminiPart>? = null,
)

/** Error envelope returned by the API on non-2xx responses. */
@Serializable
data class GeminiErrorResponse(
    val error: GeminiErrorDetail = GeminiErrorDetail(),
)

/** Error detail inside [GeminiErrorResponse]. */
@Serializable
data class GeminiErrorDetail(
    val message: String = "",
    val code: Int? = null,
    val status: String = "",
)

/** Response body of `GET /v1beta/models`. */
@Serializable
data class GeminiModelsResponse(
    val models: List<GeminiModelDto> = emptyList(),
)

/** One model entry of [GeminiModelsResponse]; [name] looks like "models/gemini-2.0-flash". */
@Serializable
data class GeminiModelDto(
    val name: String = "",
)
