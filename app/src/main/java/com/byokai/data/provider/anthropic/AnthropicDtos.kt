package com.byokai.data.provider.anthropic

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Anthropic Messages API (`POST /v1/messages`).
 *
 * Every field carries a default so a response that omits it still parses;
 * unknown fields are dropped by [com.byokai.core.network.ProviderJson].
 * Field names follow the API's snake_case exactly. Text-only scope:
 * request message `content` is always a plain string, never a
 * content-block array.
 */

/** Request body for `POST /v1/messages`. */
@Serializable
data class MessagesRequest(
    val model: String,
    val max_tokens: Int,
    val stream: Boolean = true,
    val system: String? = null,
    val messages: List<MessageDto>,
)

/** One message inside a messages request. */
@Serializable
data class MessageDto(
    val role: String,
    val content: String,
)

/**
 * One SSE `data:` payload of a streamed message. Only the fields we need
 * are modeled: the [delta] of `content_block_delta` events, and the
 * [error] detail of `error` events. Everything else (`message_start`,
 * `message_delta`, usage blocks, ...) is ignored.
 */
@Serializable
data class StreamEventData(
    val type: String = "",
    val delta: TextDeltaDto = TextDeltaDto(),
    val error: StreamErrorDetailDto = StreamErrorDetailDto(),
)

/** The incremental text of a `content_block_delta` event. */
@Serializable
data class TextDeltaDto(
    val type: String = "",
    val text: String? = null,
)

/** Error detail of an `error` event or a non-2xx response body. */
@Serializable
data class StreamErrorDetailDto(
    val type: String = "",
    val message: String = "",
)

/**
 * Error envelope shared by SSE `error` events and non-2xx HTTP
 * responses: `{"type":"error","error":{"type":...,"message":...}}`.
 */
@Serializable
data class ErrorEnvelope(
    val type: String = "",
    val error: StreamErrorDetailDto = StreamErrorDetailDto(),
)
