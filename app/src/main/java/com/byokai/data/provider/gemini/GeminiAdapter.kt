package com.byokai.data.provider.gemini

import com.byokai.core.common.Result
import com.byokai.core.network.EndpointValidation
import com.byokai.core.network.EndpointValidator
import com.byokai.core.network.ProviderJson
import com.byokai.core.network.RetryPolicy
import com.byokai.core.network.SseReader
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.model.ModelInfo
import com.byokai.domain.model.ProviderCapabilities
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.AiProvider
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderException
import com.byokai.domain.repository.AttachmentStorage
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Base64
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
 * [AiProvider] for the Google Gemini API (`https://generativelanguage.googleapis.com`).
 *
 * Chat completions stream as Server-Sent Events from
 * `POST {baseUrl}/v1beta/models/{modelId}:streamGenerateContent?alt=sse`;
 * model listing and connection tests hit `GET {baseUrl}/v1beta/models`.
 * The API key travels as an `x-goog-api-key` header (Gemini does not use
 * `Authorization: Bearer`).
 *
 * Vision (image attachments): a user turn carrying attachments is sent
 * with its text part (when non-blank) followed by one
 * `{"inlineData":{"mimeType":<mime>,"data":<base64>}}` part per
 * attachment. Text-only turns are wire-identical to before. Attachments
 * are read via [attachmentStorage] and base64-encoded with
 * [java.util.Base64] (API 28+, JVM-testable); images above
 * [MAX_IMAGE_BYTES] are rejected with [ProviderError.Config] — the
 * attachment pipeline downscales first, so this is defensive only.
 *
 * Honest scope: `capabilities.vision = true` is PROTOCOL-level. Whether
 * a given model actually accepts images is model-level and varies; a
 * text-only model answers a vision request with HTTP 400, which
 * surfaces as [ProviderError.Server].
 *
 * Security notes:
 * - The [apiKey] is sent only as the `x-goog-api-key` header and is never
 *   logged, never stored, and never embedded in error messages.
 * - `config.baseUrl` is re-validated with [EndpointValidator] on every
 *   call; redirects are disabled at the HTTP client level so the key
 *   header cannot leak to a redirect target.
 *
 * DI note: the constructor now takes [AttachmentStorage] as a second
 * parameter — the coordinator's DI wiring must supply it.
 */
class GeminiAdapter(
    private val client: OkHttpClient,
    private val attachmentStorage: AttachmentStorage,
) : AiProvider {

    override val providerId: ProviderId = ProviderId.GEMINI
    override val displayName: String = "Google Gemini"
    override val defaultBaseUrl: String = "https://generativelanguage.googleapis.com"
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        streaming = true,
        systemPrompt = true,
        modelListing = true,
        vision = true,
    )

    /**
     * Streams a chat completion as SSE events from
     * `POST {baseUrl}/v1beta/models/{modelId}:streamGenerateContent?alt=sse`.
     *
     * Emits [ChatEvent.Delta] per text chunk, then exactly one terminal
     * event. Unlike the OpenAI API, Gemini sends **no `[DONE]` sentinel**,
     * so a clean end of stream terminates with [ChatEvent.Done]; an EOF
     * is only an error when it arrives mid-parse. Malformed chunks surface
     * as [ChatEvent.Error] with [ProviderError.Parse].
     *
     * Transient failures ([ProviderError.Network], [ProviderError.Timeout],
     * 5xx, 429) are retried per [RetryPolicy] — but only while no Delta
     * has been emitted yet; once the stream has started, every failure is
     * terminal.
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
                if (request.modelId.isBlank()) {
                    trySend(
                        ChatEvent.Error(ProviderError.Config("modelId must not be blank")),
                    )
                } else {
                    val url = baseUrl + STREAM_PATH_PREFIX +
                        encodeModelId(request.modelId) + STREAM_PATH_SUFFIX + "?alt=sse"
                    val bodyJson = try {
                        ProviderJson.encodeToString(toDtoRequest(request))
                    } catch (e: ProviderException) {
                        // Attachment read failure (e.g. oversize image):
                        // emit as a typed error instead of letting it
                        // escape the flow.
                        trySend(ChatEvent.Error(e.error))
                        close()
                        return@callbackFlow
                    }
                    var attempt = 0
                    var streamStarted = false
                    val fullText = StringBuilder()
                    streamLoop@ while (true) {
                        ensureActive()
                        val httpRequest = Request.Builder()
                            .url(url)
                            .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                            .header(API_KEY_HEADER, apiKey)
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
                                        val chunk = try {
                                            ProviderJson.decodeFromString<GeminiStreamChunk>(
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
                                        val text = extractText(chunk)
                                        if (text.isNotEmpty()) {
                                            fullText.append(text)
                                            streamStarted = true
                                            trySend(ChatEvent.Delta(text))
                                        }
                                    }
                                    // Gemini sends no [DONE] sentinel: a
                                    // clean end of stream completes the
                                    // response.
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
     * Lists chat models via `GET {baseUrl}/v1beta/models`.
     *
     * The API returns resource names like "models/gemini-2.0-flash"; the
     * "models/" prefix is stripped so the id is what the
     * `:streamGenerateContent` path expects.
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
            .header(API_KEY_HEADER, apiKey)
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
                    ProviderJson.decodeFromString<GeminiModelsResponse>(
                        readBodyCapped(response, MAX_MODEL_LIST_BYTES),
                    )
                } catch (_: SerializationException) {
                    return@withContext Result.Error(
                        ProviderException(ProviderError.Parse("Malformed /v1beta/models response")),
                    )
                }
                Result.Success(
                    payload.models
                        .mapNotNull { it.name.removePrefix(MODEL_NAME_PREFIX).takeIf(String::isNotBlank) }
                        .map { ModelInfo(id = it, displayName = it) },
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
     * {baseUrl}/v1beta/models` — no model call, so no quota burn beyond
     * the listing call itself.
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
            .header(API_KEY_HEADER, apiKey)
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
     * Builds the API request body: the request turns as `contents` with
     * domain roles mapped to their API strings (USER → "user",
     * ASSISTANT → "model"), and the system prompt — together with any
     * [ChatRole.SYSTEM] turns in the message list — folded into
     * `systemInstruction`, since the Gemini contents API only accepts
     * "user" and "model" roles.
     *
     * Suspends because reading attachment bytes via [attachmentStorage]
     * is suspending. A user turn with attachments gets the text part
     * (when non-blank) followed by one `inlineData` part per attachment;
     * text-only turns are wire-identical to before. Attachments on
     * assistant turns are ignored — providers do not accept image input
     * from the model's own turns, and re-sending model-generated images
     * as input is out of scope.
     */
    private suspend fun toDtoRequest(request: ChatRequest): GeminiGenerateContentRequest {
        val systemTexts = buildList {
            request.systemPrompt?.takeIf { it.isNotBlank() }?.let(::add)
            request.messages
                .filter { it.role == ChatRole.SYSTEM }
                .map { it.content }
                .filter { it.isNotBlank() }
                .forEach(::add)
        }
        val contents = buildList {
            for (message in request.messages) {
                when (message.role) {
                    ChatRole.USER -> {
                        val parts = if (message.attachments.isEmpty()) {
                            listOf(GeminiPart(message.content))
                        } else {
                            buildList {
                                if (message.content.isNotBlank()) {
                                    add(GeminiPart(message.content))
                                }
                                for (attachment in message.attachments) {
                                    add(
                                        GeminiPart(
                                            inlineData = GeminiInlineData(
                                                mimeType = attachment.mimeType,
                                                data = encodeAttachment(attachment),
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                        add(GeminiContent("user", parts))
                    }
                    ChatRole.ASSISTANT ->
                        add(GeminiContent("model", listOf(GeminiPart(message.content))))
                    ChatRole.SYSTEM -> { /* Folded into systemInstruction above. */ }
                }
            }
        }
        return GeminiGenerateContentRequest(
            contents = contents,
            systemInstruction = systemTexts.takeIf { it.isNotEmpty() }
                ?.let { texts -> GeminiSystemInstruction(texts.map(::GeminiPart)) },
        )
    }

    /**
     * Reads an attachment's bytes and base64-encodes them for the wire.
     *
     * Defensive size cap: images above [MAX_IMAGE_BYTES] are rejected
     * with [ProviderError.Config] — the attachment pipeline downscales
     * first, so a hit here means something went wrong upstream.
     */
    private suspend fun encodeAttachment(attachment: ImageAttachment): String {
        val bytes = attachmentStorage.readBytes(attachment)
        if (bytes.size > MAX_IMAGE_BYTES) {
            throw ProviderException(ProviderError.Config("image too large"))
        }
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Concatenates all text parts of the first candidate of [chunk].
     * Candidates with absent or empty parts (e.g. `promptFeedback`-only
     * chunks) contribute nothing.
     */
    private fun extractText(chunk: GeminiStreamChunk): String =
        chunk.candidates.firstOrNull()?.content?.parts.orEmpty()
            .map { it.text }
            .filter { it.isNotEmpty() }
            .joinToString("")

    /**
     * Percent-encodes [modelId] for use as a URL path segment. Real
     * model ids are unreserved characters (`[A-Za-z0-9-_.]`, plus `~`)
     * and pass through untouched; anything else is UTF-8 percent-encoded
     * defensively so a weird id cannot break the request URL.
     */
    private fun encodeModelId(modelId: String): String {
        val out = StringBuilder(modelId.length)
        for (ch in modelId) {
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                out.append(ch)
            } else {
                for (byte in ch.toString().toByteArray(Charsets.UTF_8)) {
                    val value = byte.toInt() and 0xFF
                    out.append('%')
                    out.append(Character.forDigit(value shr 4, 16).uppercaseChar())
                    out.append(Character.forDigit(value and 0xF, 16).uppercaseChar())
                }
            }
        }
        return out.toString()
    }

    /**
     * Best-effort extraction of the API's error message from an error
     * response body; falls back to `"HTTP <code>"` when the body is empty
     * or not the expected envelope.
     */
    private fun extractErrorMessage(code: Int, body: String): String {
        if (body.isNotBlank()) {
            try {
                val message = ProviderJson.decodeFromString<GeminiErrorResponse>(body).error.message
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
        const val STREAM_PATH_PREFIX = "/v1beta/models/"
        const val STREAM_PATH_SUFFIX = ":streamGenerateContent"
        const val MODELS_PATH = "/v1beta/models"
        const val API_KEY_HEADER = "x-goog-api-key"
        const val MODEL_NAME_PREFIX = "models/"
        const val MAX_ERROR_BODY_BYTES = 64L * 1024L
        const val MAX_MODEL_LIST_BYTES = 1024L * 1024L
        const val READ_CHUNK_BYTES = 8192L
        /** Defensive post-processing size cap for one attached image. */
        const val MAX_IMAGE_BYTES = 15L * 1024L * 1024L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/**
 * Control-flow signal: a terminal [ChatEvent] (Done or Error) was already
 * sent to the flow, so the SSE read loop must stop. Caught immediately
 * around [SseReader.readAll]; never escapes the adapter.
 */
private class TerminalEventSent : RuntimeException()
