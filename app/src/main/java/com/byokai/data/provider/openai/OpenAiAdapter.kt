package com.byokai.data.provider.openai

import com.byokai.core.common.Result
import com.byokai.core.network.EndpointValidation
import com.byokai.core.network.EndpointValidator
import com.byokai.core.network.ProviderJson
import com.byokai.core.network.RetryPolicy
import com.byokai.core.network.SseReader
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ModelInfo
import com.byokai.domain.model.ProviderCapabilities
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.AiProvider
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * [AiProvider] for the OpenAI chat API (`https://api.openai.com`) and any
 * OpenAI-compatible endpoint ([ProviderId.OPENAI_COMPATIBLE]).
 *
 * Chat completions are streamed as Server-Sent Events; model listing and
 * connection tests hit `GET /v1/models`.
 *
 * Security notes:
 * - The [apiKey] is sent only as an `Authorization: Bearer` header and is
 *   never logged, never stored, and never embedded in error messages.
 * - `config.baseUrl` is re-validated with [EndpointValidator] on every
 *   call; redirects are disabled at the HTTP client level so the
 *   Authorization header cannot leak to a redirect target.
 */
class OpenAiAdapter(private val client: OkHttpClient) : AiProvider {

    override val providerId: ProviderId = ProviderId.OPENAI
    override val displayName: String = "OpenAI"
    override val defaultBaseUrl: String = "https://api.openai.com"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        systemPrompt = true,
        modelListing = true,
    )

    /**
     * Streams a chat completion as SSE events from
     * `POST {baseUrl}/v1/chat/completions`.
     *
     * Emits [ChatEvent.Delta] per text chunk, then exactly one terminal
     * event: [ChatEvent.Done] after `data: [DONE]`, or [ChatEvent.Error] on
     * any failure. Transient failures ([ProviderError.Network],
     * [ProviderError.Timeout], 5xx, 429) are retried per [RetryPolicy] —
     * but only while no Delta has been emitted yet; once the stream has
     * started, every failure is terminal.
     *
     * Collecting the flow on `Dispatchers.IO`; cancelling the collector
     * cancels the in-flight HTTP call via Job.invokeOnClose.
     */
    override fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        request: ChatRequest,
    ): Flow<ChatEvent> = flow {
        when (val validation = EndpointValidator.validate(config.baseUrl)) {
            is EndpointValidation.Invalid -> {
                emit(
                    ChatEvent.Error(
                        ProviderError.Server(0, "Invalid endpoint: ${validation.reason}"),
                    ),
                )
                return@flow
            }
            is EndpointValidation.Valid -> Unit
        }

        val url = config.baseUrl.trimEnd('/') + CHAT_COMPLETIONS_PATH
        val bodyJson = ProviderJson.encodeToString(
            ChatCompletionsRequest(
                model = request.modelId,
                messages = toDtoMessages(request),
            ),
        )

        // Cancelling the collecting coroutine cancels the in-flight HTTP
        // call; OkHttp's cancel() unblocks the reader's blocking socket
        // read, so cancellation is prompt. A plain cold flow is used here
        // (no callback channel) since collection and reading run in the
        // same coroutine.
        var activeCall: Call? = null
        currentCoroutineContext().job.invokeOnClose { activeCall?.cancel() }

        try {
            var attempt = 0
            var streamStarted = false
            val fullText = StringBuilder()
            while (true) {
                ensureActive()
                val httpRequest = Request.Builder()
                    .url(url)
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .build()
                val call = client.newCall(httpRequest)
                activeCall = call
                try {
                    val response = call.execute()
                    try {
                        if (!response.isSuccessful) {
                            val errorBody = readBodyCapped(response, MAX_ERROR_BODY_BYTES)
                            val error = normalizeHttpError(
                                response.code,
                                errorBody,
                                response.header("Retry-After"),
                            )
                            val decision = RetryPolicy.decide(attempt, error, streamStarted)
                            if (decision.shouldRetry) {
                                attempt++
                                delay(decision.delayMillis)
                                continue
                            }
                            emit(ChatEvent.Error(error))
                            return@flow
                        }
                        try {
                            SseReader.readAll(response.body.source()) { message ->
                                ensureActive()
                                if (message.data.isBlank()) return@readAll
                                if (message.data == DONE_SENTINEL) {
                                    emit(ChatEvent.Done(fullText.toString()))
                                    throw TerminalEventSent()
                                }
                                val chunk = try {
                                    ProviderJson.decodeFromString<ChatCompletionsChunk>(
                                        message.data,
                                    )
                                } catch (_: SerializationException) {
                                    emit(
                                        ChatEvent.Error(
                                            ProviderError.Parse("Malformed SSE data chunk"),
                                        ),
                                    )
                                    throw TerminalEventSent()
                                }
                                val text = chunk.choices.firstOrNull()?.delta?.content
                                if (!text.isNullOrEmpty()) {
                                    fullText.append(text)
                                    streamStarted = true
                                    emit(ChatEvent.Delta(text))
                                }
                            }
                            emit(
                                ChatEvent.Error(
                                    ProviderError.Parse("stream ended without [DONE]"),
                                ),
                            )
                        } catch (_: TerminalEventSent) {
                            // Terminal event already emitted; fall through to close.
                        }
                        return@flow
                    } finally {
                        response.close()
                    }
                } catch (_: SocketTimeoutException) {
                    ensureActive()
                    val decision =
                        RetryPolicy.decide(attempt, ProviderError.Timeout, streamStarted)
                    if (decision.shouldRetry) {
                        attempt++
                        delay(decision.delayMillis)
                        continue
                    }
                    emit(ChatEvent.Error(ProviderError.Timeout))
                    return@flow
                } catch (e: IOException) {
                    // InterruptedIOException from call.cancel() arrives here on
                    // collector cancellation; ensureActive rethrows
                    // CancellationException so cancellation is never swallowed.
                    ensureActive()
                    val error = ProviderError.Network(e)
                    val decision = RetryPolicy.decide(attempt, error, streamStarted)
                    if (decision.shouldRetry) {
                        attempt++
                        delay(decision.delayMillis)
                        continue
                    }
                    emit(ChatEvent.Error(error))
                    return@flow
                }
            }
        } finally {
            activeCall?.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Maps an HTTP failure to a [ProviderError].
     *
     * - 401/403 → [ProviderError.Auth]
     * - 429 → [ProviderError.RateLimited], with the `Retry-After` hint when
     *   [retryAfterHeader] parses as seconds.
     * - 404 → [ProviderError.InvalidModel]
     * - 5xx and anything else → [ProviderError.Server]
     *
     * The message is taken from the API's `{"error":{"message":...}}`
     * envelope on a best-effort basis, falling back to `"HTTP <code>"`.
     * Never includes the API key.
     */
    fun normalizeHttpError(
        code: Int,
        body: String,
        retryAfterHeader: String?,
    ): ProviderError {
        val message = extractErrorMessage(code, body)
        return when (code) {
            401, 403 -> ProviderError.Auth(message)
            429 -> ProviderError.RateLimited(
                message,
                retryAfterHeader?.trim()?.toLongOrNull(),
            )
            404 -> ProviderError.InvalidModel("")
            else -> ProviderError.Server(code, message)
        }
    }

    /**
     * Lists chat models via `GET {baseUrl}/v1/models`.
     */
    override suspend fun listModels(
        config: ProviderConfig,
        apiKey: String,
    ): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        when (val validation = EndpointValidator.validate(config.baseUrl)) {
            is EndpointValidation.Invalid ->
                return@withContext Result.Error(
                    IllegalArgumentException("Invalid endpoint: ${validation.reason}"),
                )
            is EndpointValidation.Valid -> Unit
        }
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + MODELS_PATH)
            .get()
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = normalizeHttpError(
                        response.code,
                        readBodyCapped(response, MAX_ERROR_BODY_BYTES),
                        response.header("Retry-After"),
                    )
                    return@withContext Result.Error(ProviderException(error))
                }
                val payload = try {
                    ProviderJson.decodeFromString<ModelsResponse>(
                        readBodyCapped(response, MAX_MODEL_LIST_BYTES),
                    )
                } catch (_: SerializationException) {
                    return@withContext Result.Error(
                        ProviderException(ProviderError.Parse("Malformed /v1/models response")),
                    )
                }
                Result.Success(
                    payload.data
                        .filter { it.id.isNotBlank() }
                        .map { ModelInfo(id = it.id, displayName = it.id) },
                )
            }
        } catch (_: SocketTimeoutException) {
            Result.Error(ProviderException(ProviderError.Timeout))
        } catch (e: IOException) {
            Result.Error(ProviderException(ProviderError.Network(e)))
        }
    }

    /**
     * Verifies reachability and credentials with a cheap `GET
     * {baseUrl}/v1/models` — no model call, so no quota burn beyond the
     * listing call itself.
     */
    override suspend fun testConnection(
        config: ProviderConfig,
        apiKey: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        when (val validation = EndpointValidator.validate(config.baseUrl)) {
            is EndpointValidation.Invalid ->
                return@withContext Result.Error(
                    IllegalArgumentException("Invalid endpoint: ${validation.reason}"),
                )
            is EndpointValidation.Valid -> Unit
        }
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + MODELS_PATH)
            .get()
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = normalizeHttpError(
                        response.code,
                        readBodyCapped(response, MAX_ERROR_BODY_BYTES),
                        response.header("Retry-After"),
                    )
                    return@withContext Result.Error(ProviderException(error))
                }
                Result.Success(Unit)
            }
        } catch (_: SocketTimeoutException) {
            Result.Error(ProviderException(ProviderError.Timeout))
        } catch (e: IOException) {
            Result.Error(ProviderException(ProviderError.Network(e)))
        }
    }

    /**
     * Builds the API message list: the system prompt (when non-blank)
     * first as role "system", then the request turns with domain roles
     * mapped to their API strings.
     */
    private fun toDtoMessages(request: ChatRequest): List<ChatMessageDto> = buildList {
        request.systemPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            add(ChatMessageDto(role = "system", content = prompt))
        }
        for (message in request.messages) {
            val role = when (message.role) {
                ChatRole.SYSTEM -> "system"
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "assistant"
            }
            add(ChatMessageDto(role = role, content = message.content))
        }
    }

    /**
     * Best-effort extraction of the API's error message from an error
     * response body; falls back to `"HTTP <code>"` when the body is empty
     * or not the expected envelope.
     */
    private fun extractErrorMessage(code: Int, body: String): String {
        if (body.isNotBlank()) {
            try {
                val message = ProviderJson.decodeFromString<ErrorResponse>(body).error.message
                if (message.isNotBlank()) return message
            } catch (_: SerializationException) {
                // Not the error envelope; fall through to the generic message.
            }
        }
        return "HTTP $code"
    }

    /**
     * Reads at most [maxBytes] of the response body as text. Error bodies
     * are capped so a misbehaving server cannot force a huge allocation.
     */
    private fun readBodyCapped(response: Response, maxBytes: Long): String {
        return try {
            val source = response.body.source()
            val sink = Buffer()
            var remaining = maxBytes
            while (remaining > 0) {
                val read = source.read(sink, minOf(remaining, READ_CHUNK_BYTES))
                if (read == -1L) break
                remaining -= read
            }
            sink.readUtf8()
        } catch (_: IOException) {
            ""
        }
    }

    private companion object {
        const val CHAT_COMPLETIONS_PATH = "/v1/chat/completions"
        const val MODELS_PATH = "/v1/models"
        const val DONE_SENTINEL = "[DONE]"
        const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        const val MAX_MODEL_LIST_BYTES = 1024L * 1024L
        const val READ_CHUNK_BYTES = 8192L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * Control-flow signal: a terminal [ChatEvent] (Done or Error) was already
 * sent to the flow, so the SSE read loop must stop. Caught immediately
 * around [SseReader.readAll]; never escapes the adapter.
 */
private class TerminalEventSent : RuntimeException()
