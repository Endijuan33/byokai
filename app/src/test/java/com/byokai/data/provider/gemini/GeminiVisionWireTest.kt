package com.byokai.data.provider.gemini

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Wire tests for [GeminiAdapter] image attachments (Phase 9B vision).
 *
 * Asserts the exact `parts` shapes the adapter puts on the wire:
 * `inlineData` parts for attachments, and — critically — no `inlineData`
 * key at all for text-only turns (the API must see wire-identical
 * bodies to before). Also covers the defensive post-processing size
 * cap. Uses the fake key "test-key-123" — never a real credential.
 */
class GeminiVisionWireTest {

    private lateinit var server: MockWebServer
    private lateinit var storage: FakeAttachmentStorage
    private lateinit var adapter: GeminiAdapter

    private val attachment = ImageAttachment(
        id = "att-1",
        fileName = "photo.webp",
        mimeType = "image/webp",
        width = 64,
        height = 64,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        storage = FakeAttachmentStorage()
        adapter = GeminiAdapter(HttpClientFactory.create(), storage)
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun config() = ProviderConfig(
        id = "cfg-test",
        providerId = ProviderId.GEMINI,
        displayName = "Test Gemini",
        baseUrl = server.url("/").toString().trimEnd('/'),
        modelIds = listOf("gemini-2.0-flash"),
        defaultModelId = "gemini-2.0-flash",
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun chunkJson(text: String) =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}}]}"""

    private fun sse(vararg chunks: String): String = buildString {
        for (chunk in chunks) append("data: ").append(chunk).append("\n\n")
    }

    private fun sseResponse(body: String) = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()

    @Test
    fun `vision capability is true`() {
        assertTrue(adapter.capabilities.vision)
    }

    @Test
    fun `user message with attachment sends text part plus inlineData part`() {
        server.enqueue(sseResponse(sse(chunkJson("seen"))))
        val request = ChatRequest(
            providerConfigId = "cfg-test",
            modelId = "gemini-2.0-flash",
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
        val contents = body["contents"]!!.jsonArray
        assertEquals(1, contents.size)
        assertEquals("user", contents[0].jsonObject["role"]!!.jsonPrimitive.content)
        val parts = contents[0].jsonObject["parts"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals(
            "Describe this",
            parts[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        val inlineData = parts[1].jsonObject["inlineData"]!!.jsonObject
        assertEquals("image/webp", inlineData["mimeType"]!!.jsonPrimitive.content)
        assertEquals(
            Base64.getEncoder().encodeToString(storage.bytes),
            inlineData["data"]!!.jsonPrimitive.content,
        )
        // The API models a part as a oneof: a "text" key — even "" —
        // alongside "inlineData" is rejected with HTTP 400.
        assertNull(
            "image part must not carry a text key; body was: ${recorded.body?.utf8()}",
            parts[1].jsonObject["text"],
        )
    }

    @Test
    fun `text-only user message has no inlineData key`() {
        server.enqueue(sseResponse(sse(chunkJson("ok"))))
        val request = ChatRequest(
            providerConfigId = "cfg-test",
            modelId = "gemini-2.0-flash",
            messages = listOf(ChatMessage(ChatRole.USER, "Hello")),
        )

        runBlocking {
            adapter.streamChat(config(), "test-key-123", request).toList()
        }

        assertTrue("no attachment must be read", storage.readCalls.isEmpty())
        val recorded = server.takeRequest(10, TimeUnit.SECONDS)!!
        val rawBody = recorded.body?.utf8().orEmpty()
        assertFalse(
            "text-only request must not mention inlineData; body was: $rawBody",
            rawBody.contains("inlineData"),
        )
        val body = ProviderJson.parseToJsonElement(rawBody).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals("Hello", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `oversize image emits Error with Config image too large and sends nothing`() {
        storage.bytes = ByteArray((15 * 1024 * 1024) + 1)
        val request = ChatRequest(
            providerConfigId = "cfg-test",
            modelId = "gemini-2.0-flash",
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
