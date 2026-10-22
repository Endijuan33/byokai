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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
 * [AiProvider] for the OpenAI chat API (`https://api.openai.com/v1`) and any
 * OpenAI-compatible endpoint ([ProviderId.OPENAI_COMPATIBLE]).
 *
 * Base URL convention (OpenAI SDK style): the base URL includes the
 * version prefix exactly as the provider documents it (e.g.
 * `https://api.openai.com/v1`); the adapter appends only
 * `/chat/completions` and `/models`. Never bake `/v1` into the request
 * paths — third-party endpoints document their own prefixes (Groq uses
 * `/openai/v1`, Perplexity uses none).
 *
 * Chat completions are streamed as Server-Sent Events; model listing and
 * connection tests hit `GET /models`.
 *
 * Security notes:
 * - The [apiKey] is sent only as an `Authorization: Bearer` header and is
 *   never logged, never stored, and never embedded in error messages.
 * - `config.baseUrl` is re-validated with [EndpointValidator] on every
 *   call; redirects are disabled at the HTTP client level so the
 *   Authorization header cannot leak to a redirect target.
 *
 * Subclassed by [OpenAiCompatibleAdapter] for third-party
 * OpenAI-compatible endpoints: the wire protocol is identical, only the
 * identity (provider id, display name, default base URL) differs.
 */
open class OpenAiAdapter(private val client: OkHttpClient) : AiProvider {

    open override val providerId: ProviderId = ProviderId.OPENAI
    open override val displayName: String = "OpenAI"
    open override val defaultBaseUrl: String = "https://api.openai.com/v1"
    open override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        systemPrompt = true,
        modelListing = true,
    )

    /**
     * Streams a chat completion as SSE events from
     * `POST {baseUrl}/chat/completions`.
     *
     * Emits [ChatEvent.Delta] per text chunk, then exactly one terminal
     * event: [ChatEvent.Done] after `data: [DONE]`, or [ChatEvent.Error] on
     * any failure. Transient failures ([ProviderError.Network],
     * [ProviderError.Timeout], 5xx, 429) are retried per [RetryPolicy] —
     * but only while no Delta has been emitted yet; once the stream has
     * started, every failure is terminal.
     *
     * Collecting the flow on `Dispatchers.IO`; cancelling the collector
     * cancels the in-flight HTTP call.
     */
    override fun streamChat(
        config: ProviderConfig,
        apiKey: String,
        request: ChatRequest,
    ): Flow<ChatEvent> = callbackFlow {
        var call: Call? = null
        // Prompt cancellation: when the collector is cancelled, the channel's
        // close handler cancels the in-flight HTTP call, unblocking the
        // socket read immediately. Note: kotlinx.coroutines 1.11.0 has no
        // Job.invokeOnClose, and its awaitClose lives in
        // kotlinx.coroutines.channels and must not be combined with another
        // invokeOnClose handler — so a single SendChannel.invokeOnClose
        // handler is used instead.
        channel.invokeOnClose { call?.cancel() }
        try {
            val baseUrl = when (val validation = EndpointValidator.validate(config.baseUrl)) {
                is EndpointValidation.Invalid -> {
                    trySend(
                        ChatEvent.Error(
                            ProviderError.Server(0, "Invalid endpoint: ${validation.reason}"),
                        ),
                    )
                    null
                }
                is EndpointValidation.Valid -> config.baseUrl.trimEnd('/')
            }
            if (baseUrl != null) {
                val url = baseUrl + CHAT_COMPLETIONS_PATH
                val bodyJson = ProviderJson.encodeToString(
                    ChatCompletionsRequest(
                        model = request.modelId,
                        messages = toDtoMessages(request),
                    ),
                )
                var attempt = 0
                var streamStarted = false
                val fullText = StringBuilder()
                streamLoop@ while (true) {
                    ensureActive()
                    val httpRequest = Request.Builder()
                        .url(url)
                        .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                        .header("Authorization", "Bearer $apiKey")
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .build()
                    call = client.newCall(httpRequest)
                    try {
                        val response = call.execute()
                        try {
                            if (!response.isSuccessful) {
                                val errorBody = readBodyCapped(response, MAX_ERROR_BODY_BYTES)
                                val error = normalizeHttpError(
                                    response.code,
                                    errorBody,
                                    response.header("Retry-After"),
                                    request.modelId,
                                )
                                val decision = RetryPolicy.decide(attempt, error, streamStarted)
                                if (decision.shouldRetry) {
                                    attempt++
                                    delay(decision.delayMillis)
                                    continue@streamLoop
                                }
                                trySend(ChatEvent.Error(error))
                                break@streamLoop
                            }
                            try {
                                SseReader.readAll(response.body.source()) { message ->
                                    currentCoroutineContext().ensureActive()
                                    if (message.data.isBlank()) return@readAll
                                    if (message.data == DONE_SENTINEL) {
                                        trySend(ChatEvent.Done(fullText.toString()))
                                        throw TerminalEventSent()
                                    }
                                    val chunk = try {
                                        ProviderJson.decodeFromString<ChatCompletionsChunk>(
                                            message.data,
                                        )
                                    } catch (_: SerializationException) {
                                        trySend(
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
                                        trySend(ChatEvent.Delta(text))
                                    }
                                }
                                trySend(
                                    ChatEvent.Error(
                                        ProviderError.Parse("stream ended without [DONE]"),
                                    ),
                                )
                            } catch (_: TerminalEventSent) {
                                // Terminal event already sent.
                            }
                            break@streamLoop
                        } finally {
                            response.close()
                        }
                    } catch (_: SocketTimeoutException) {
                        currentCoroutineContext().ensureActive()
                        val decision =
                            RetryPolicy.decide(attempt, ProviderError.Timeout, streamStarted)
                        if (decision.shouldRetry) {
                            attempt++
                            delay(decision.delayMillis)
                            continue@streamLoop
                        }
                        trySend(ChatEvent.Error(ProviderError.Timeout))
                        break@streamLoop
                    } catch (e: IOException) {
                        currentCoroutineContext().ensureActive()
                        val error = ProviderError.Network(e)
                        val decision = RetryPolicy.decide(attempt, error, streamStarted)
                        if (decision.shouldRetry) {
                            attempt++
                            delay(decision.delayMillis)
                            continue@streamLoop
                        }
                        trySend(ChatEvent.Error(error))
                        break@streamLoop
                    }
                }
            }
        } finally {
            call?.cancel()
        }
        // Close the channel on normal completion: callbackFlow requires the
        // channel to be closed for send when the block returns, otherwise
        // collection throws IllegalStateException.
        close()
    }.flowOn(Dispatchers.IO)

    /**
     * Maps an HTTP failure to a [ProviderError].
     *
     * - 401/403 → [ProviderError.Auth]
     * - 429 → [ProviderError.RateLimited], with the `Retry-After` hint when
     *   [retryAfterHeader] parses as seconds.
     * - 404 → [ProviderError.InvalidModel] carrying [modelId] when known
     *   (streaming path); a blank [modelId] renders a nameless message in
     *   the UI rather than "Model  is not...".
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
        modelId: String = "",
    ): ProviderError {
        val message = extractErrorMessage(code, body)
        return when (code) {
            401, 403 -> ProviderError.Auth(message)
            429 -> ProviderError.RateLimited(
                message,
                retryAfterHeader?.trim()?.toLongOrNull(),
            )
            404 -> ProviderError.InvalidModel(modelId)
            else -> ProviderError.Server(code, message)
        }
    }

    /**
     * Lists chat models via `GET {baseUrl}/models`.
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
     * {baseUrl}/models` — no model call, so no quota burn beyond the
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
        const val CHAT_COMPLETIONS_PATH = "/chat/completions"
        const val MODELS_PATH = "/models"
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
