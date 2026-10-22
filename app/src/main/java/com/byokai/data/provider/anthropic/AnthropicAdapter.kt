package com.byokai.data.provider.anthropic

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
 * [AiProvider] for the Anthropic Messages API (`https://api.anthropic.com/v1`).
 *
 * Base URL convention (same as OpenAI SDK style): the base URL includes
 * the version prefix exactly as documented
 * (`https://api.anthropic.com/v1`); the adapter appends only `/messages`.
 *
 * Completions are streamed as Server-Sent Events from
 * `POST {baseUrl}/messages` with `stream: true`. Unlike OpenAI there is
 * no `[DONE]` sentinel: the stream ends with an `event: message_stop`
 * message, and a clean EOF is also accepted as a normal end of stream.
 * Anthropic exposes no model-listing endpoint, so [listModels] returns the
 * config's known model ids and [testConnection] performs a minimal real
 * completion instead.
 *
 * Security notes:
 * - The [apiKey] is sent only as an `x-api-key` header and is never
 *   logged, never stored, and never embedded in error messages.
 * - `config.baseUrl` is re-validated with [EndpointValidator] on every
 *   call; redirects are disabled at the HTTP client level so the key
 *   cannot leak to a redirect target.
 *
 * Subclassed by [AnthropicCompatibleAdapter] for third-party
 * Anthropic-compatible endpoints: the wire protocol is identical, only
 * the identity (provider id, display name, default base URL) differs.
 */
open class AnthropicAdapter(private val client: OkHttpClient) : AiProvider {

    open override val providerId: ProviderId = ProviderId.ANTHROPIC
    open override val displayName: String = "Anthropic"
    open override val defaultBaseUrl: String = "https://api.anthropic.com/v1"
    open override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        systemPrompt = true,
        modelListing = false,
    )

    /**
     * Streams a chat completion as SSE events from
     * `POST {baseUrl}/messages`.
     *
     * Request mapping:
     * - `model` = [ChatRequest.modelId].
     * - `max_tokens` = 1024. Anthropic requires `max_tokens` — the API
     *   has no default. 1024 is a deliberately modest generation ceiling:
     *   long enough for typical chat answers (which still stream
     *   incrementally), small enough to bound accidental quota burn per
     *   call.
     * - `system` = [ChatRequest.systemPrompt] plus the text of any
     *   [ChatRole.SYSTEM] messages, joined with blank lines. Anthropic has
     *   no "system" message role, so system-role turns are folded into
     *   this field instead of the `messages` array.
     * - `messages` = user/assistant turns only.
     *
     * SSE handling (tracked by `event:` name):
     * - `content_block_delta` → accumulate `data.delta.text`, emit
     *   [ChatEvent.Delta] per non-empty chunk.
     * - `message_stop` → [ChatEvent.Done] with the accumulated text.
     * - `error` → parse the error envelope, emit [ChatEvent.Error] with
     *   the normalized [ProviderError].
     * - anything else (`message_start`, `content_block_start`,
     *   `content_block_stop`, `message_delta`, `ping`, ...) → ignored.
     * - EOF without `message_stop` → [ChatEvent.Done] with whatever was
     *   accumulated; Anthropic closes the stream cleanly, so this is not
     *   a parse failure.
     * - a malformed `content_block_delta` payload → [ChatEvent.Error]
     *   with [ProviderError.Parse].
     *
     * Emits exactly one terminal event per collection. Transient failures
     * ([ProviderError.Network], [ProviderError.Timeout], 5xx, 429) are
     * retried per [RetryPolicy] — but only while no Delta has been
     * emitted yet; once the stream has started, every failure is terminal.
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
                val url = baseUrl + MESSAGES_PATH
                val bodyJson = ProviderJson.encodeToString(toMessagesRequest(request))
                var attempt = 0
                var streamStarted = false
                val fullText = StringBuilder()
                streamLoop@ while (true) {
                    ensureActive()
                    val httpRequest = Request.Builder()
                        .url(url)
                        .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", ANTHROPIC_VERSION)
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
                                    when (message.event) {
                                        "content_block_delta" -> {
                                            val event = try {
                                                ProviderJson.decodeFromString<StreamEventData>(
                                                    message.data,
                                                )
                                            } catch (_: SerializationException) {
                                                trySend(
                                                    ChatEvent.Error(
                                                        ProviderError.Parse(
                                                            "Malformed SSE data chunk",
                                                        ),
                                                    ),
                                                )
                                                throw TerminalEventSent()
                                            }
                                            val text = event.delta.text
                                            if (!text.isNullOrEmpty()) {
                                                fullText.append(text)
                                                streamStarted = true
                                                trySend(ChatEvent.Delta(text))
                                            }
                                        }
                                        "message_stop" -> {
                                            trySend(ChatEvent.Done(fullText.toString()))
                                            throw TerminalEventSent()
                                        }
                                        "error" -> {
                                            val detail = try {
                                                ProviderJson.decodeFromString<ErrorEnvelope>(
                                                    message.data,
                                                ).error
                                            } catch (_: SerializationException) {
                                                trySend(
                                                    ChatEvent.Error(
                                                        ProviderError.Parse(
                                                            "Malformed SSE error event",
                                                        ),
                                                    ),
                                                )
                                                throw TerminalEventSent()
                                            }
                                            trySend(ChatEvent.Error(normalizeStreamError(detail, request.modelId)))
                                            throw TerminalEventSent()
                                        }
                                        else -> Unit // message_start, content_block_start/stop,
                                        // message_delta, ping, ... carry no text.
                                    }
                                }
                                // Clean EOF without message_stop: Anthropic closes the
                                // stream cleanly, so whatever accumulated is the
                                // complete response — not a parse failure.
                                trySend(ChatEvent.Done(fullText.toString()))
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
     * - 404 → [ProviderError.InvalidModel] carrying [modelId] when known;
     *   a blank [modelId] renders a nameless message in the UI.
     * - 5xx and anything else → [ProviderError.Server]
     *
     * The message is taken from the API's
     * `{"type":"error","error":{"message":...}}` envelope on a best-effort
     * basis, falling back to `"HTTP <code>"`. Never includes the API key.
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
     * Anthropic exposes no model-listing endpoint ([ProviderCapabilities.modelListing]
     * is false), so per the [AiProvider] contract this returns the config's
     * known [ProviderConfig.modelIds] as [ModelInfo] entries — no network
     * call, no quota burn.
     */
    override suspend fun listModels(
        config: ProviderConfig,
        apiKey: String,
    ): Result<List<ModelInfo>> =
        Result.Success(config.modelIds.map { ModelInfo(id = it, displayName = it) })

    /**
     * Verifies reachability and credentials with a minimal non-streaming
     * `POST {baseUrl}/messages` (`max_tokens: 1`, one one-word user
     * message). A 2xx response means the key works.
     *
     * **Quota note:** unlike the OpenAI/Gemini listing-based tests, this
     * performs a real model call — a tiny one (one token of output), but
     * it does consume a small amount of quota on every test.
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
        val modelId = config.defaultModelId?.takeIf { it.isNotBlank() }
            ?: config.modelIds.firstOrNull { it.isNotBlank() }
            ?: return@withContext Result.Error(
                ProviderException(ProviderError.Config("no model id configured")),
            )
        val bodyJson = ProviderJson.encodeToString(
            MessagesRequest(
                model = modelId,
                max_tokens = TEST_CONNECTION_MAX_TOKENS,
                stream = false,
                messages = listOf(MessageDto(role = "user", content = "Hi")),
            ),
        )
        val httpRequest = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + MESSAGES_PATH)
            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Content-Type", "application/json")
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
     * Builds the Messages API request: the system prompt (when non-blank)
     * plus any [ChatRole.SYSTEM] message texts folded into the top-level
     * `system` field, and the user/assistant turns as `messages`.
     */
    private fun toMessagesRequest(request: ChatRequest): MessagesRequest {
        val systemParts = buildList {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let { add(it) }
            for (message in request.messages) {
                if (message.role == ChatRole.SYSTEM) add(message.content)
            }
        }
        val system = systemParts.joinToString("\n\n").takeIf { it.isNotBlank() }
        val messages = request.messages.mapNotNull { message ->
            val role = when (message.role) {
                ChatRole.SYSTEM -> return@mapNotNull null
                ChatRole.USER -> "user"
                ChatRole.ASSISTANT -> "assistant"
            }
            MessageDto(role = role, content = message.content)
        }
        return MessagesRequest(
            model = request.modelId,
            max_tokens = DEFAULT_MAX_TOKENS,
            system = system,
            messages = messages,
        )
    }

    /**
     * Maps an SSE `error` event's detail to a [ProviderError] by the
     * Anthropic error type: `authentication_error`/`permission_error` →
     * [ProviderError.Auth], `rate_limit_error` → [ProviderError.RateLimited],
     * `not_found_error` → [ProviderError.InvalidModel] (carrying [modelId]
     *   when known), everything else →
     * [ProviderError.Server]. Never includes the API key.
     */
    private fun normalizeStreamError(detail: StreamErrorDetailDto, modelId: String = ""): ProviderError {
        val message = detail.message.ifBlank {
            detail.type.ifBlank { "stream error" }
        }
        return when (detail.type) {
            "authentication_error", "permission_error" -> ProviderError.Auth(message)
            "rate_limit_error" -> ProviderError.RateLimited(message, null)
            "not_found_error" -> ProviderError.InvalidModel(modelId)
            else -> ProviderError.Server(0, message)
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
                val message = ProviderJson.decodeFromString<ErrorEnvelope>(body).error.message
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
        const val MESSAGES_PATH = "/messages"
        const val ANTHROPIC_VERSION = "2023-06-01"
        /** Generation ceiling for chat completions; see [streamChat] KDoc. */
        const val DEFAULT_MAX_TOKENS = 1024
        /** Tiny output budget for [testConnection]; burns minimal quota. */
        const val TEST_CONNECTION_MAX_TOKENS = 1
        const val MAX_ERROR_BODY_BYTES = 64L * 1024L
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
