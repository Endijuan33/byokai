package com.byokai.data.provider.gemini

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
 * Integration tests for [GeminiAdapter] against a local [MockWebServer].
 * Uses the fake key "test-key-123" — never a real credential.
 */
class GeminiAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: GeminiAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = GeminiAdapter(HttpClientFactory.create())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config(baseUrl: String = server.url("/").toString().trimEnd('/')) =
        ProviderConfig(
            id = "cfg-test",
            providerId = ProviderId.GEMINI,
            displayName = "Test Gemini",
            baseUrl = baseUrl,
            modelIds = listOf("gemini-2.0-flash"),
            defaultModelId = "gemini-2.0-flash",
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun request(
        modelId: String = "gemini-2.0-flash",
        systemPrompt: String? = null,
    ) = ChatRequest(
        providerConfigId = "cfg-test",
        modelId = modelId,
        messages = listOf(ChatMessage(ChatRole.USER, "Hello")),
        systemPrompt = systemPrompt,
    )

    private fun chunkJson(text: String) =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}}]}"""

    private fun multiPartChunk(vararg texts: String): String {
        val parts = texts.joinToString(",") { """{"text":"$it"}""" }
        return """{"candidates":[{"content":{"role":"model","parts":[$parts]}}]}"""
    }

    private fun emptyPartsChunk() =
        """{"candidates":[{"content":{"role":"model","parts":[]}}]}"""

    private fun noCandidatesChunk() = """{"promptFeedback":{"blockReason":"OTHER"}}"""

    private fun sse(vararg chunks: String): String = buildString {
        // Gemini sends no [DONE] sentinel; the stream just ends.
        for (chunk in chunks) append("data: ").append(chunk).append("\n\n")
    }

    private fun sseResponse(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()

    private fun errorJson(message: String, status: String = "INVALID_ARGUMENT") =
        """{"error":{"message":"$message","code":400,"status":"$status"}}"""

    private fun collectAll(
        modelId: String = "gemini-2.0-flash",
        systemPrompt: String? = null,
    ): List<ChatEvent> = runBlocking {
        adapter.streamChat(config(), "test-key-123", request(modelId, systemPrompt)).toList()
    }

    // ---- streaming -------------------------------------------------------

    @Test
    fun `streams two chunks then EOF terminates with Done`() {
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
    fun `concatenates all parts of a multi-part chunk`() {
        server.enqueue(sseResponse(sse(multiPartChunk("Hello", " ", "world"))))

        val events = collectAll()

        assertEquals(
            listOf(ChatEvent.Delta("Hello world"), ChatEvent.Done("Hello world")),
            events,
        )
    }

    @Test
    fun `empty parts emit no Delta`() {
        // A chunk with an empty parts array and a chunk with no candidates
        // contribute no text; only the real text chunk surfaces as a Delta.
        server.enqueue(
            sseResponse(sse(emptyPartsChunk(), noCandidatesChunk(), chunkJson("hi"))),
        )

        val events = collectAll()

        assertEquals(
            listOf(ChatEvent.Delta("hi"), ChatEvent.Done("hi")),
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
    fun `invalid endpoint yields Error without any HTTP call`() {
        val events = runBlocking {
            adapter.streamChat(config("not a url"), "test-key-123", request()).toList()
        }

        assertEquals(1, events.size)
        val error = events[0] as ChatEvent.Error
        assertTrue(error.error is ProviderError.Server)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `blank modelId yields Error without any HTTP call`() {
        val events = runBlocking {
            adapter.streamChat(config(), "test-key-123", request("  ")).toList()
        }

        assertEquals(1, events.size)
        val error = events[0] as ChatEvent.Error
        assertTrue(error.error is ProviderError.Config)
        assertEquals(0, server.requestCount)
    }

    // ---- HTTP error normalization ----------------------------------------

    @Test
    fun `401 normalizes to Auth`() {
        server.enqueue(
            MockResponse.Builder().code(401).body(errorJson("API key not valid")).build(),
        )

        val events = collectAll()

        assertEquals(1, events.size)
        val error = (events[0] as ChatEvent.Error).error as ProviderError.Auth
        assertTrue(error.message.contains("API key not valid"))
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
                    .body(errorJson("slow down", "RESOURCE_EXHAUSTED"))
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
        server.enqueue(
            MockResponse.Builder().code(404).body(errorJson("no such model", "NOT_FOUND")).build(),
        )

        val events = collectAll()

        assertEquals(1, events.size)
        val error = (events[0] as ChatEvent.Error).error
        assertTrue(error is ProviderError.InvalidModel)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `500 normalizes to Server`() {
        val error = adapter.normalizeHttpError(500, errorJson("boom", "INTERNAL"), null)
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
    fun `request shape is correct`() {
        server.enqueue(sseResponse(sse(chunkJson("Hi"))))

        runBlocking {
            val shapedRequest = ChatRequest(
                providerConfigId = "cfg-test",
                modelId = "gpt-xyz",
                messages = listOf(
                    ChatMessage(ChatRole.SYSTEM, "Be nice."),
                    ChatMessage(ChatRole.USER, "Hello"),
                    ChatMessage(ChatRole.ASSISTANT, "Hi there"),
                ),
                systemPrompt = "You are helpful.",
            )
            adapter.streamChat(config(), "test-key-123", shapedRequest).toList()
        }

        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertTrue(
            "request target was: ${recorded.target}",
            recorded.target.startsWith("/v1beta/models/gpt-xyz:streamGenerateContent"),
        )
        assertTrue("query was: ${recorded.target}", recorded.target.contains("alt=sse"))
        assertEquals("test-key-123", recorded.headers["x-goog-api-key"])
        val body = ProviderJson.parseToJsonElement(recorded.body?.utf8().orEmpty()).jsonObject

        // Contents carry only user/model roles, in order.
        val contents = body["contents"]!!.jsonArray
        assertEquals(2, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "Hello",
            contents[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("model", contents[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(
            "Hi there",
            contents[1].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content,
        )

        // The system prompt and the SYSTEM turn fold into systemInstruction.
        val systemParts = body["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray
        assertEquals(2, systemParts.size)
        assertEquals(
            "You are helpful.",
            systemParts[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "Be nice.",
            systemParts[1].jsonObject["text"]!!.jsonPrimitive.content,
        )
        // The system prompt travels ONLY via `systemInstruction` — it
        // must never leak into the user/model content parts.
        val contentTexts = contents.flatMap { content ->
            content.jsonObject["parts"]!!.jsonArray.map { part ->
                part.jsonObject["text"]!!.jsonPrimitive.content
            }
        }
        assertTrue(
            "request body was: $body",
            contentTexts.none { it.contains("You are helpful.") },
        )
    }

    @Test
    fun `no systemInstruction when systemPrompt is blank and no system turns`() {
        server.enqueue(sseResponse(sse(chunkJson("Hi"))))

        collectAll(systemPrompt = "   ")

        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        val body = ProviderJson.parseToJsonElement(recorded.body?.utf8().orEmpty()).jsonObject
        assertTrue("body was: $body", body["systemInstruction"] == null)
    }

    // ---- retry behavior ---------------------------------------------------

    @Test
    fun `retries once on 500 then succeeds`() {
        server.enqueue(
            MockResponse.Builder().code(500).body(errorJson("boom", "INTERNAL")).build(),
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
        server.enqueue(
            MockResponse.Builder().code(400).body(errorJson("bad request")).build(),
        )

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
    fun `listModels strips the models prefix`() {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """{"models":[{"name":"models/gemini-2.0-flash"},{"name":"models/gemini-2.5-pro"},{"name":""}]}""",
                )
                .build(),
        )

        val result = runBlocking { adapter.listModels(config(), "test-key-123") }

        assertTrue(result is Result.Success)
        // The "models/" prefix is stripped and blank names are filtered.
        assertEquals(
            listOf("gemini-2.0-flash", "gemini-2.5-pro"),
            (result as Result.Success).data.map { it.id },
        )
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1beta/models", recorded.target)
        assertEquals("test-key-123", recorded.headers["x-goog-api-key"])
    }

    @Test
    fun `listModels error on 401`() {
        server.enqueue(
            MockResponse.Builder().code(401).body(errorJson("API key not valid")).build(),
        )

        val result = runBlocking { adapter.listModels(config(), "test-key-123") }

        assertTrue(result is Result.Error)
        val error = (result as Result.Error).cause as ProviderException
        assertTrue(error.error is ProviderError.Auth)
        assertTrue(error.error.let { (it as ProviderError.Auth).message.contains("API key not valid") })
    }

    @Test
    fun `testConnection succeeds on 200`() {
        server.enqueue(MockResponse.Builder().code(200).body("""{"models":[]}""").build())

        val result = runBlocking { adapter.testConnection(config(), "test-key-123") }

        assertTrue(result is Result.Success)
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1beta/models", recorded.target)
    }

    @Test
    fun `testConnection errors on 401`() {
        server.enqueue(
            MockResponse.Builder().code(401).body(errorJson("API key not valid")).build(),
        )

        val result = runBlocking { adapter.testConnection(config(), "test-key-123") }

        assertTrue(result is Result.Error)
        val error = (result as Result.Error).cause as ProviderException
        assertTrue(error.error is ProviderError.Auth)
    }

    // ---- adapter identity -------------------------------------------------

    @Test
    fun `adapter advertises Gemini identity and capabilities`() {
        assertEquals(ProviderId.GEMINI, adapter.providerId)
        assertEquals("Google Gemini", adapter.displayName)
        assertEquals("https://generativelanguage.googleapis.com", adapter.defaultBaseUrl)
        assertTrue(adapter.capabilities.streaming)
        assertTrue(adapter.capabilities.systemPrompt)
        assertTrue(adapter.capabilities.modelListing)
    }
}
