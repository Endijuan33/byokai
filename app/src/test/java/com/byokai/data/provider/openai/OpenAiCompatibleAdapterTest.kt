package com.byokai.data.provider.openai

import com.byokai.core.common.Result
import com.byokai.core.network.HttpClientFactory
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.provider.ProviderException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [OpenAiCompatibleAdapter] against a local [MockWebServer].
 *
 * The wire protocol is inherited from [OpenAiAdapter]; these tests pin
 * the generic identity (provider id, no fixed base URL) and the
 * best-effort `/v1/models` fallback contract. Uses the fake key
 * "test-key-123" — never a real credential.
 */
class OpenAiCompatibleAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: OpenAiCompatibleAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = OpenAiCompatibleAdapter(HttpClientFactory.create())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config(baseUrl: String = server.url("/v1").toString().trimEnd('/')) =
        ProviderConfig(
            id = "cfg-test",
            providerId = ProviderId.OPENAI_COMPATIBLE,
            displayName = "Test Compatible",
            baseUrl = baseUrl,
            modelIds = listOf("custom-model-a", "custom-model-b"),
            defaultModelId = "custom-model-a",
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun request() = ChatRequest(
        providerConfigId = "cfg-test",
        modelId = "custom-model-a",
        messages = listOf(ChatMessage(ChatRole.USER, "Hello")),
        systemPrompt = null,
    )

    private fun chunkJson(content: String) =
        """{"choices":[{"delta":{"content":"$content"}}]}"""

    private fun sse(vararg chunks: String, done: Boolean = true): String = buildString {
        for (chunk in chunks) append("data: ").append(chunk).append("\n\n")
        if (done) append("data: [DONE]\n\n")
    }

    @Test
    fun `adapter identity is generic`() {
        assertEquals(ProviderId.OPENAI_COMPATIBLE, adapter.providerId)
        assertEquals("OpenAI-compatible", adapter.displayName)
        assertEquals("", adapter.defaultBaseUrl)
        assertTrue(adapter.capabilities.streaming)
        assertTrue(adapter.capabilities.systemPrompt)
        assertTrue(adapter.capabilities.modelListing)
    }

    @Test
    fun `streams chat against a custom base URL`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sse(chunkJson("Hello"), chunkJson(" world")))
                .build(),
        )

        val events = adapter.streamChat(config(), "test-key-123", request()).toList()

        assertEquals(
            listOf(
                ChatEvent.Delta("Hello"),
                ChatEvent.Delta(" world"),
                ChatEvent.Done("Hello world"),
            ),
            events,
        )
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer test-key-123", recorded.headers["Authorization"])
    }

    @Test
    fun `streams against a path-prefixed base URL without doubling the prefix`() = runBlocking {
        // Regression test for the Groq incident: base URL
        // "https://api.groq.com/openai/v1" must resolve to
        // "/openai/v1/chat/completions", never "/openai/v1/v1/chat/completions".
        val groqStyle = config(baseUrl = server.url("/openai/v1").toString().trimEnd('/'))
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(sse(chunkJson("OK")))
                .build(),
        )

        val events = adapter.streamChat(groqStyle, "test-key-123", request()).toList()

        assertEquals(
            listOf(ChatEvent.Delta("OK"), ChatEvent.Done("OK")),
            events,
        )
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        assertEquals("/openai/v1/chat/completions", recorded.target)
    }

    @Test
    fun `streaming 404 carries the requested model id`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(404)
                .body("""{"error":{"message":"model not found","type":"invalid_request_error"}}""")
                .build(),
        )

        val events = adapter.streamChat(config(), "test-key-123", request()).toList()

        val error = (events.single() as ChatEvent.Error).error
        assertTrue(error is ProviderError.InvalidModel)
        assertEquals("custom-model-a", (error as ProviderError.InvalidModel).modelId)
    }
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"data":[{"id":"srv-1"},{"id":"srv-2"}]}""")
                .build(),
        )

        val result = adapter.listModels(config(), "test-key-123")

        assertTrue(result is Result.Success)
        assertEquals(
            listOf("srv-1", "srv-2"),
            (result as Result.Success).data.map { it.id },
        )
    }

    @Test
    fun `listModels falls back to manual ids when endpoint has no models route`() =
        runBlocking {
            server.enqueue(MockResponse.Builder().code(404).body("not found").build())

            val result = adapter.listModels(config(), "test-key-123")

            assertTrue(result is Result.Success)
            assertEquals(
                listOf("custom-model-a", "custom-model-b"),
                (result as Result.Success).data.map { it.id },
            )
        }

    @Test
    fun `listModels falls back to manual ids on malformed body`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body("this is not json").build(),
        )

        val result = adapter.listModels(config(), "test-key-123")

        assertTrue(result is Result.Success)
        assertEquals(
            listOf("custom-model-a", "custom-model-b"),
            (result as Result.Success).data.map { it.id },
        )
    }

    @Test
    fun `listModels propagates auth failures instead of masking them`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .body("""{"error":{"message":"bad key"}}""")
                .build(),
        )

        val result = adapter.listModels(config(), "test-key-123")

        assertTrue(result is Result.Error)
        val error = ((result as Result.Error).cause as ProviderException).error
        assertTrue(error is ProviderError.Auth)
    }

    @Test
    fun `listModels propagates server failures instead of masking them`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())

        val result = adapter.listModels(config(), "test-key-123")

        assertTrue(result is Result.Error)
        val error = ((result as Result.Error).cause as ProviderException).error
        assertTrue(error is ProviderError.Server)
    }
}
