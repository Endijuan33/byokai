package com.byokai.data.provider.anthropic

import com.byokai.core.network.HttpClientFactory
import com.byokai.core.network.ProviderJson
import com.byokai.domain.chat.ChatEvent
import com.byokai.domain.chat.ChatRequest
import com.byokai.domain.model.ChatMessage
import com.byokai.domain.model.ChatRole
import com.byokai.domain.model.ImageAttachment
import com.byokai.domain.model.ProviderConfig
import com.byokai.domain.model.ProviderId
import com.byokai.domain.provider.ProviderError
import com.byokai.domain.repository.FakeAttachmentStorage
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wire tests for [AnthropicAdapter] image attachments (Phase 9B vision).
 *
 * Asserts the exact `content` shapes the adapter puts on the wire:
 * content-block arrays (`text` + base64 `image` blocks) for turns with
 * attachments, and plain JSON strings — wire-identical to before — for
 * text-only turns. Also covers the defensive post-processing size cap.
 * Uses the fake key "test-key-123" — never a real credential.
 */
class AnthropicVisionWireTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: FakeAttachmentStorage
    private lateinit var adapter: AnthropicAdapter

    private val attachment = ImageAttachment(
        id = "att-1",
        fileName = "photo.jpeg",
        mimeType = "image/jpeg",
        width = 64,
        height = 64,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = FakeAttachmentStorage()
        adapter = AnthropicAdapter(HttpClientFactory.create(), storage)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config() = ProviderConfig(
        id = "cfg-test",
        providerId = ProviderId.ANTHROPIC,
        displayName = "Test Anthropic",
        baseUrl = server.url("/").toString().trimEnd('/'),
        modelIds = listOf("claude-test"),
        defaultModelId = "claude-test",
        createdAt = 0L,
        updatedAt = 0L,
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

    private fun doneStream(text: String) = sseResponse(
        streamBody(
            event("message_start", """{"type":"message_start","message":{"id":"m1"}}"""),
            event("content_block_delta", deltaJson(text)),
            event("message_stop", """{"type":"message_stop"}"""),
        ),
    )

    @Test
    fun `vision capability is true`() {
        assertTrue(adapter.capabilities.vision)
    }

    @Test
    fun `message with attachment sends text plus base64 image blocks`() {
        server.enqueue(doneStream("seen"))
        val request = ChatRequest(
            providerConfigId = "cfg-test",
            modelId = "claude-test",
            messages = listOf(
                ChatMessage(ChatRole.USER, "Describe this", listOf(attachment)),
            ),
        )

        val events = runBlocking {
            adapter.streamChat(config(), "test-key-123", request).toList()
        }

        assertEquals(listOf(ChatEvent.Delta("seen"), ChatEvent.Done("seen")), events)
        assertEquals(listOf(attachment), storage.readCalls)
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        val body = ProviderJson.parseToJsonElement(recorded.body?.utf8().orEmpty()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        val content = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "Describe this",
            content[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertEquals("image", content[1].jsonObject["type"]!!.jsonPrimitive.content)
        val source = content[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals(
            Base64.getEncoder().encodeToString(storage.bytes),
            source["data"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `text-only message keeps plain-string content`() {
        server.enqueue(doneStream("ok"))
        val request = ChatRequest(
            providerConfigId = "cfg-test",
            modelId = "claude-test",
            messages = listOf(ChatMessage(ChatRole.USER, "Hello")),
        )

        runBlocking {
            adapter.streamChat(config(), "test-key-123", request).toList()
        }

        assertTrue("no attachment must be read", storage.readCalls.isEmpty())
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        val body = ProviderJson.parseToJsonElement(recorded.body?.utf8().orEmpty()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals(
            "Hello",
            messages[0].jsonObject["content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `oversize image emits Error with Config image too large and sends nothing`() {
        storage.bytes = ByteArray((15 * 1024 * 1024) + 1)
        val request = ChatRequest(
            providerConfigId = "cfg-test",
            modelId = "claude-test",
            messages = listOf(
                ChatMessage(ChatRole.USER, "Too big", listOf(attachment)),
            ),
        )

        val events = runBlocking {
            adapter.streamChat(config(), "test-key-123", request).toList()
        }

        assertEquals(1, events.size)
        val error = events.single() as ChatEvent.Error
        assertTrue(error.error is ProviderError.Config)
        assertEquals("image too large", (error.error as ProviderError.Config).message)
        assertEquals("oversize image must never reach the wire", 0, server.requestCount)
    }
}
