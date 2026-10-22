package com.byokai.data.provider.openai

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
import com.byokai.domain.repository.FakeAttachmentStorage
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
 * Integration tests for [OpenAiAdapter] against a local [MockWebServer].
 * Uses the fake key "test-key-123" — never a real credential.
 */
class OpenAiAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: OpenAiAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = OpenAiAdapter(HttpClientFactory.create(), FakeAttachmentStorage())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config(baseUrl: String = server.url("/v1").toString().trimEnd('/')) =
        ProviderConfig(
            id = "cfg-test",
            providerId = ProviderId.OPENAI,
            displayName = "Test OpenAI",
            baseUrl = baseUrl,
            modelIds = listOf("gpt-4o"),
            defaultModelId = "gpt-4o",
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun request(systemPrompt: String? = null) = ChatRequest(
        providerConfigId = "cfg-test",
        modelId = "gpt-4o",
        messages = listOf(ChatMessage(ChatRole.USER, "Hello")),
        systemPrompt = systemPrompt,
    )

    private fun chunkJson(content: String) =
        """{"choices":[{"delta":{"content":"$content"}}]}"""

    private fun sse(vararg chunks: String, done: Boolean = true): String = buildString {
        for (chunk in chunks) append("data: ").append(chunk).append("\n\n")
        if (done) append("data: [DONE]\n\n")
    }

    private fun sseResponse(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()

    private fun errorJson(message: String, type: String = "invalid_request_error") =
        """{"error":{"message":"$message","type":"$type"}}"""

    private fun collectAll(systemPrompt: String? = null): List<ChatEvent> = runBlocking {
        adapter.streamChat(config(), "test-key-123", request(systemPrompt)).toList()
    }

    // ---- streaming -------------------------------------------------------

    @Test
    fun `streams deltas then DONE`() {
        server.enqueue(
            sseResponse(sse(chunkJson("Hello"), chunkJson(" world"))),
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
    fun `malformed chunk yields Parse error`() {
        server.enqueue(sseResponse("data: {not json}\n\n"))

        val events = collectAll()

        assertEquals(1, events.size)
        val error = events[0] as ChatEvent.Error
        assertTrue(error.error is ProviderError.Parse)
    }

    @Test
    fun `stream without DONE yields Parse error`() {
        server.enqueue(sseResponse(sse(chunkJson("partial"), done = false)))

        val events = collectAll()

        // The partial chunk is still delivered as a Delta; the missing
        // [DONE] sentinel then surfaces as a terminal Parse error.
        assertEquals(2, events.size)
        assertTrue(events[0] is ChatEvent.Delta)
        val error = events[1] as ChatEvent.Error
        assertTrue(error.error is ProviderError.Parse)
        assertTrue((error.error as ProviderError.Parse).message.contains("[DONE]"))
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
            MockResponse.Builder().code(401).body(errorJson("Incorrect API key")).build(),
        )

        val events = collectAll()

        assertEquals(1, events.size)
        val error = (events[0] as ChatEvent.Error).error as ProviderError.Auth
        assertTrue(error.message.contains("Incorrect API key"))
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
        server.enqueue(MockResponse.Builder().code(404).body(errorJson("no such model")).build())

        val events = collectAll()

        assertEquals(1, events.size)
        val error = (events[0] as ChatEvent.Error).error
        assertTrue(error is ProviderError.InvalidModel)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `500 normalizes to Server`() {
        val error = adapter.normalizeHttpError(500, errorJson("boom", "server_error"), null)
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
    fun `request body is correct`() {
        server.enqueue(sseResponse(sse(chunkJson("Hi"))))

        collectAll(systemPrompt = "You are helpful.")

        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer test-key-123", recorded.headers["Authorization"])
        val body = ProviderJson.parseToJsonElement(recorded.body?.utf8().orEmpty()).jsonObject
        assertTrue("request body was: $body", body["stream"]?.jsonPrimitive?.boolean == true)
        assertEquals("gpt-4o", body["model"]!!.jsonPrimitive.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "You are helpful.",
            messages[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("Hello", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
        // The system prompt travels ONLY via the system message — it
        // must never leak into user message content.
        assertTrue(
            "request body was: $body",
            messages
                .filter { it.jsonObject["role"]!!.jsonPrimitive.content != "system" }
                .none { message ->
                    message.jsonObject["content"]!!.jsonPrimitive.content
                        .contains("You are helpful.")
                },
        )
    }

    @Test
    fun `no system message when systemPrompt is blank`() {
        server.enqueue(sseResponse(sse(chunkJson("Hi"))))

        collectAll(systemPrompt = "   ")

        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        val messages = ProviderJson.parseToJsonElement(recorded.body?.utf8().orEmpty())
            .jsonObject["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    // ---- retry behavior ---------------------------------------------------

    @Test
    fun `retries once on 500 then succeeds`() {
        server.enqueue(
            MockResponse.Builder().code(500).body(errorJson("boom", "server_error")).build(),
        )
        server.enqueue(sseResponse(sse(chunkJson("recovered"))))

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
        // stream promptly via the adapter's awaitClose -> call.cancel().
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
    fun `listModels parses model ids`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"object":"list","data":[{"id":"gpt-4o","object":"model"},{"id":"gpt-4o-mini","object":"model"}]}""")
                .build(),
        )

        val result = runBlocking { adapter.listModels(config(), "test-key-123") }

        assertTrue(result is Result.Success)
        assertEquals(
            listOf("gpt-4o", "gpt-4o-mini"),
            (result as Result.Success).data.map { it.id },
        )
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/models", recorded.target)
        assertEquals("Bearer test-key-123", recorded.headers["Authorization"])
    }

    @Test
    fun `listModels error on 401`() {
        server.enqueue(
            MockResponse.Builder().code(401).body(errorJson("Incorrect API key")).build(),
        )

        val result = runBlocking { adapter.listModels(config(), "test-key-123") }

        assertTrue(result is Result.Error)
        val error = (result as Result.Error).cause as ProviderException
        assertTrue(error.error is ProviderError.Auth)
        assertTrue(error.error.let { (it as ProviderError.Auth).message.contains("Incorrect API key") })
    }

    @Test
    fun `testConnection succeeds on 200`() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"object":"list","data":[]}""").build())

        val result = runBlocking { adapter.testConnection(config(), "test-key-123") }

        assertTrue(result is Result.Success)
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/models", recorded.target)
    }

    @Test
    fun `testConnection errors on 401`() {
        server.enqueue(
            MockResponse.Builder().code(401).body(errorJson("Incorrect API key")).build(),
        )

        val result = runBlocking { adapter.testConnection(config(), "test-key-123") }

        assertTrue(result is Result.Error)
        val error = (result as Result.Error).cause as ProviderException
        assertTrue(error.error is ProviderError.Auth)
    }

    // ---- adapter identity -------------------------------------------------

    @Test
    fun `adapter advertises OpenAI identity and capabilities`() {
        assertEquals(ProviderId.OPENAI, adapter.providerId)
        assertEquals("OpenAI", adapter.displayName)
        assertEquals("https://api.openai.com/v1", adapter.defaultBaseUrl)
        assertTrue(adapter.capabilities.streaming)
        assertTrue(adapter.capabilities.systemPrompt)
        assertTrue(adapter.capabilities.modelListing)
    }
}
