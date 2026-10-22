package com.byokai.data.provider.anthropic

import com.byokai.core.network.HttpClientFactory
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [AnthropicCompatibleAdapter] against a local
 * [MockWebServer]. The wire protocol is inherited from
 * [AnthropicAdapter]; these tests pin the generic identity and prove a
 * custom base URL streams correctly. Uses the fake key "test-key-123" —
 * never a real credential.
 */
class AnthropicCompatibleAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: AnthropicCompatibleAdapter

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        adapter = AnthropicCompatibleAdapter(HttpClientFactory.create())
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config(baseUrl: String = server.url("/v1").toString().trimEnd('/')) =
        ProviderConfig(
            id = "cfg-test",
            providerId = ProviderId.ANTHROPIC_COMPATIBLE,
            displayName = "Test Anthropic-Compatible",
            baseUrl = baseUrl,
            modelIds = listOf("custom-claude"),
            defaultModelId = "custom-claude",
            createdAt = 0L,
            updatedAt = 0L,
        )

    private fun request() = ChatRequest(
        providerConfigId = "cfg-test",
        modelId = "custom-claude",
        messages = listOf(ChatMessage(ChatRole.USER, "Hello")),
        systemPrompt = null,
    )

    private fun event(eventName: String, data: String) = "event: $eventName\ndata: $data\n\n"

    private fun deltaJson(text: String) =
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

    @Test
    fun `adapter identity is generic`() {
        assertEquals(ProviderId.ANTHROPIC_COMPATIBLE, adapter.providerId)
        assertEquals("Anthropic-compatible", adapter.displayName)
        assertEquals("", adapter.defaultBaseUrl)
        assertTrue(adapter.capabilities.streaming)
        assertTrue(adapter.capabilities.systemPrompt)
        assertFalse(adapter.capabilities.modelListing)
    }

    @Test
    fun `streams chat against a custom base URL`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(
                    event("message_start", """{"type":"message_start"}""") +
                        event("content_block_delta", deltaJson("Hello")) +
                        event("content_block_delta", deltaJson(" world")) +
                        event("message_stop", """{"type":"message_stop"}"""),
                )
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
        assertEquals("/v1/messages", recorded.target)
        assertEquals("test-key-123", recorded.headers["x-api-key"])
    }
}
