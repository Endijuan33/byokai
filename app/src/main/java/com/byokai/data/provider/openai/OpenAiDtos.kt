package com.byokai.data.provider.openai

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the OpenAI chat completions API
 * (`POST /v1/chat/completions`, `GET /v1/models`).
 *
 * Every field carries a default so a response that omits it still parses;
 * unknown fields are dropped by [com.byokai.core.network.ProviderJson].
 * Field names follow the API's snake_case exactly.
 */

/** Request body for `POST /v1/chat/completions`. */
@Serializable
data class ChatCompletionsRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val stream: Boolean = true,
)

/** One message inside a chat completions request. */
@Serializable
data class ChatMessageDto(
    val role: String,
    val content: String,
)

/** One SSE `data:` payload of a streamed chat completion. */
@Serializable
data class ChatCompletionsChunk(
    val choices: List<ChoiceDto> = emptyList(),
)

/** One choice inside a streamed chunk. */
@Serializable
data class ChoiceDto(
    val delta: DeltaDto = DeltaDto(),
    val finish_reason: String? = null,
)

/** The incremental content of a streamed choice. */
@Serializable
data class DeltaDto(
    val content: String? = null,
    val role: String? = null,
)

/** Error envelope returned by the API on non-2xx responses. */
@Serializable
data class ErrorResponse(
    val error: ErrorDetailDto = ErrorDetailDto(),
)

/** Error detail inside [ErrorResponse]. */
@Serializable
data class ErrorDetailDto(
    val message: String = "",
    val type: String = "",
    val code: String? = null,
)

/** Response body of `GET /v1/models`. */
@Serializable
data class ModelsResponse(
    val data: List<ModelDto> = emptyList(),
)

/** One model entry of [ModelsResponse]. */
@Serializable
data class ModelDto(
    val id: String = "",
)
