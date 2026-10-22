package com.byokai.data.provider.anthropic

import com.byokai.core.common.Result
import com.byokai.core.network.HttpClientFactory
import com.byokai.core.network.ProviderJson
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for [AnthropicAdapter] against a local [MockWebServer].
 * Uses the fake key "test-key-123" — never a real credential.
 */
class AnthropicAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: AnthropicAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = AnthropicAdapter(HttpClientFactory.create())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config(baseUrl: String = server.url("/v1").toString().trimEnd('/')) =
        ProviderConfig(
            id = "cfg-test",
            providerId = ProviderId.ANTHROPIC,
            displayName = "Test Anthropic",
            baseUrl = baseUrl,
            modelIds = listOf("claude-sonnet-4-5"),
            defaultModelId = "claude-sonnet-4-5",
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun request(systemPrompt: String? = null) = ChatRequest(
        providerConfigId = "cfg-test",
        modelId = "claude-sonnet-4-5",
        messages = listOf(ChatMessage(ChatRole.USER, "Hello")),
        systemPrompt = systemPrompt,
    )

    private fun event(eventName: String, data: String) = "event: $eventName\ndata: $data\n\n"

    private fun deltaJson(text: String) =
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

    private fun streamBody(vararg events: String) = events.joinToString("")

    private fun sseResponse(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()

    private fun errorJson(message: String, type: String = "invalid_request_error") =
        """{"type":"error","error":{"type":"$type","message":"$message"}}"""

    private fun collectAll(systemPrompt: String? = null): List<ChatEvent> = runBlocking {
        adapter.streamChat(config(), "test-key-123", request(systemPrompt)).toList()
    }

    // ---- streaming -------------------------------------------------------

    @Test
    fun `streams deltas then message_stop`() {
        server.enqueue(
            sseResponse(
                streamBody(
                    event("message_start", """{"type":"message_start","message":{"id":"m1"}}"""),
                    event("content_block_start", """{"type":"content_block_start","index":0}"""),
                    event("content_block_delta", deltaJson("Hello")),
                    event("content_block_delta", deltaJson(" world")),
                    event("content_block_stop", """{"type":"content_block_stop","index":0}"""),
                    event("message_stop", """{"type":"message_stop"}"""),
                ),
            ),
        )

        val events = collectAll()

        assertEquals(
            listOf(
                ChatEvent.Delta("Hello"),
                ChatEvent.Delta(" world"),
                ChatEvent.Done("Hello world"),
            ),
            events,
        )
    }

    @Test
    fun `accumulates deltas across content blocks`() {
        server.enqueue(
            sseResponse(
                streamBody(
                    event("content_block_start", """{"type":"content_block_start","index":0}"""),
                    event("content_block_delta", deltaJson("Hello")),
                    event("content_block_stop", """{"type":"content_block_stop","index":0}"""),
                    event("content_block_start", """{"type":"content_block_start","index":1}"""),
                    event("content_block_delta", deltaJson(" world")),
                    event("content_block_stop", """{"type":"content_block_stop","index":1}"""),
                    event("message_delta", """{"type":"message_delta","usage":{"output_tokens":3}}"""),
                    event("message_stop", """{"type":"message_stop"}"""),
                ),
            ),
        )

        val events = collectAll()

        // Deltas are per text chunk; Done carries the accumulation across
        // both content blocks.
        assertEquals(
            listOf(
                ChatEvent.Delta("Hello"),
                ChatEvent.Delta(" world"),
                ChatEvent.Done("Hello world"),
            ),
            events,
        )
    }

    @Test
    fun `EOF without message_stop yields Done`() {
        server.enqueue(
            sseResponse(
                streamBody(
                    event("content_block_delta", deltaJson("partial")),
                ),
            ),
        )

        val events = collectAll()

        // Anthropic closes the stream cleanly: EOF is a normal end, not a
        // parse failure.
        assertEquals(
            listOf(ChatEvent.Delta("partial"), ChatEvent.Done("partial")),
            events,
        )
    }

    @Test
    fun `error event normalizes to Auth`() {
        server.enqueue(
            sseResponse(
                event(
                    "error",
                    errorJson("invalid x-api-key: wrong", "authentication_error"),
                ),
            ),
        )

        val events = collectAll()

        assertEquals(1, events.size)
        val error = (events[0] as ChatEvent.Error).error as ProviderError.Auth
        assertTrue(error.message.contains("invalid x-api-key"))
        // Stream errors are terminal: exactly one request.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `malformed chunk yields Parse error`() {
        server.enqueue(
            sseResponse(event("content_block_delta", "{not json}")),
        )

        val events = collectAll()

        assertEquals(1, events.size)
        val error = events[0] as ChatEvent.Error
        assertTrue(error.error is ProviderError.Parse)
    }

    @Test
    fun `invalid endpoint yields Error without any HTTP call`() {
        val events = runBlocking {
            adapter.streamChat(config("not a url"), "test-key-123", request()).toList()
        }

        assertEquals(1, events.size)
        val error = events[0] as ChatEvent.Error
        assertTrue(error.error is ProviderError.Server)
        assertEquals(0, server.requestCount)
    }

    // ---- HTTP error normalization ----------------------------------------

    @Test
    fun `401 normalizes to Auth`() {
        server.enqueue(
            MockResponse.Builder().code(401).body(errorJson("invalid x-api-key", "authentication_error")).build(),
        )

        val events = collectAll()

        assertEquals(1, events.size)
        val error = (events[0] as ChatEvent.Error).error as ProviderError.Auth
        assertTrue(error.message.contains("invalid x-api-key"))
        // 401 is not retryable: exactly one request.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `429 normalizes to RateLimited with Retry-After`() {
        // 429 is retryable pre-stream, so three 429s exhaust the retries and
        // the final failure surfaces as the terminal error.
        repeat(3) {
            server.enqueue(
                MockResponse.Builder()
                    .code(429)
                    .addHeader("Retry-After", "1")
                    .body(errorJson("slow down", "rate_limit_error"))
                    .build(),
            )
        }

        val events = collectAll()

        assertEquals(1, events.size)
        val error = (events[0] as ChatEvent.Error).error as ProviderError.RateLimited
        assertEquals(1L, error.retryAfterSeconds)
        assertTrue(error.message.contains("slow down"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `normalizeHttpError parses Retry-After header`() {
        val error = adapter.normalizeHttpError(429, errorJson("slow down"), "5")
            as ProviderError.RateLimited
        assertEquals(5L, error.retryAfterSeconds)

        val noHeader = adapter.normalizeHttpError(429, errorJson("slow down"), null)
            as ProviderError.RateLimited
        assertEquals(null, noHeader.retryAfterSeconds)
    }

    @Test
    fun `404 normalizes to InvalidModel`() {
        server.enqueue(MockResponse.Builder().code(404).body(errorJson("no such model", "not_found_error")).build())

        val events = collectAll()

        assertEquals(1, events.size)
        val error = (events[0] as ChatEvent.Error).error
        assertTrue(error is ProviderError.InvalidModel)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `500 normalizes to Server`() {
        val error = adapter.normalizeHttpError(500, errorJson("boom", "overloaded_error"), null)
        assertTrue(error is ProviderError.Server)
        assertEquals(500, (error as ProviderError.Server).statusCode)
        assertTrue(error.message.contains("boom"))
    }

    @Test
    fun `unknown error body falls back to HTTP code`() {
        val error = adapter.normalizeHttpError(418, "", null) as ProviderError.Server
        assertEquals(418, error.statusCode)
        assertEquals("HTTP 418", error.message)
    }

    // ---- request shape ----------------------------------------------------

    @Test
    fun `request body and headers are correct`() {
        server.enqueue(
            sseResponse(
                streamBody(
                    event("content_block_delta", deltaJson("Hi")),
                    event("message_stop", """{"type":"message_stop"}"""),
                ),
            ),
        )

        collectAll(systemPrompt = "You are helpful.")

        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/messages", recorded.target)
        assertEquals("test-key-123", recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        val body = ProviderJson.parseToJsonElement(recorded.body?.utf8().orEmpty()).jsonObject
        assertEquals("claude-sonnet-4-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(1024, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue("request body was: $body", body["stream"]?.jsonPrimitive?.boolean == true)
        assertEquals("You are helpful.", body["system"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hello", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        // The system prompt travels ONLY via the `system` field — it
        // must never leak into user message content.
        assertTrue(
            "request body was: $body",
            messages.none { message ->
                message.jsonObject["content"]!!.jsonPrimitive.content
                    .contains("You are helpful.")
            },
        )
    }

    @Test
    fun `no system field when systemPrompt is blank`() {
        server.enqueue(
            sseResponse(
                streamBody(
                    event("content_block_delta", deltaJson("Hi")),
                    event("message_stop", """{"type":"message_stop"}"""),
                ),
            ),
        )

        collectAll(systemPrompt = "   ")

        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        val body = ProviderJson.parseToJsonElement(recorded.body?.utf8().orEmpty()).jsonObject
        // explicitNulls=false omits the null system field entirely.
        assertTrue("request body was: $body", !body.containsKey("system"))
    }

    // ---- retry behavior ---------------------------------------------------

    @Test
    fun `retries once on 500 then succeeds`() {
        server.enqueue(
            MockResponse.Builder().code(500).body(errorJson("boom", "overloaded_error")).build(),
        )
        server.enqueue(
            sseResponse(
                streamBody(
                    event("content_block_delta", deltaJson("recovered")),
                    event("message_stop", """{"type":"message_stop"}"""),
                ),
            ),
        )

        val events = collectAll()

        assertEquals(
            listOf(ChatEvent.Delta("recovered"), ChatEvent.Done("recovered")),
            events,
        )
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `does not retry on 400`() {
        server.enqueue(MockResponse.Builder().code(400).body(errorJson("bad request")).build())

        val events = collectAll()

        assertEquals(1, events.size)
        val error = (events[0] as ChatEvent.Error).error as ProviderError.Server
        assertEquals(400, error.statusCode)
        assertEquals(1, server.requestCount)
    }

    // ---- cancellation -----------------------------------------------------

    @Test
    fun `cancellation stops the stream`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .onRequestStart(SocketEffect.Stall)
                .build(),
        )

        // The stalled server never responds; cancelling must unblock the
        // stream promptly via the adapter's invokeOnClose -> call.cancel().
        val emitted = mutableListOf<ChatEvent>()
        runBlocking {
            val job = launch {
                adapter.streamChat(config(), "test-key-123", request())
                    .collect { emitted += it }
            }
            delay(300)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        }

        assertTrue("no terminal event may be emitted after cancel", emitted.none { it is ChatEvent.Done })
    }

    // ---- listModels / testConnection --------------------------------------

    @Test
    fun `listModels returns config ids without network`() {
        val result = runBlocking { adapter.listModels(config(), "test-key-123") }

        assertTrue(result is Result.Success)
        assertEquals(
            listOf("claude-sonnet-4-5"),
            (result as Result.Success).data.map { it.id },
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `testConnection succeeds on 200`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"id":"msg_1","type":"message","content":[{"type":"text","text":"Hi"}]}""")
                .build(),
        )

        val result = runBlocking { adapter.testConnection(config(), "test-key-123") }

        assertTrue(result is Result.Success)
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/messages", recorded.target)
        assertEquals("test-key-123", recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        val body = ProviderJson.parseToJsonElement(recorded.body?.utf8().orEmpty()).jsonObject
        assertEquals("claude-sonnet-4-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(1, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue("request body was: $body", body["stream"]?.jsonPrimitive?.boolean == false)
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `testConnection errors on 401`() {
        server.enqueue(
            MockResponse.Builder().code(401).body(errorJson("invalid x-api-key", "authentication_error")).build(),
        )

        val result = runBlocking { adapter.testConnection(config(), "test-key-123") }

        assertTrue(result is Result.Error)
        val error = (result as Result.Error).cause as ProviderException
        assertTrue(error.error is ProviderError.Auth)
    }

    // ---- adapter identity -------------------------------------------------

    @Test
    fun `adapter advertises Anthropic identity and capabilities`() {
        assertEquals(ProviderId.ANTHROPIC, adapter.providerId)
        assertEquals("Anthropic", adapter.displayName)
        assertEquals("https://api.anthropic.com/v1", adapter.defaultBaseUrl)
        assertTrue(adapter.capabilities.streaming)
        assertTrue(adapter.capabilities.systemPrompt)
        assertTrue(!adapter.capabilities.modelListing)
    }
}
