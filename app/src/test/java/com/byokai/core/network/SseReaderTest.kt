package com.byokai.core.network

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [SseReader] against in-memory [okio.Buffer] sources.
 * Pure JVM — no network, no Android.
 */
class SseReaderTest {

    private fun readAll(
        text: String,
        maxBytes: Long = SseReader.MAX_BYTES,
    ): List<SseMessage> = runBlocking {
        val messages = mutableListOf<SseMessage>()
        SseReader.readAll(Buffer().writeUtf8(text), maxBytes) { messages += it }
        messages
    }

    @Test
    fun `data lines are dispatched as messages`() {
        val messages = readAll("data: hello\n\ndata: world\n\n")
        assertEquals(
            listOf(SseMessage(null, "hello"), SseMessage(null, "world")),
            messages,
        )
    }

    @Test
    fun `DONE sentinel passes through as data`() {
        val messages = readAll("data: [DONE]\n\n")
        assertEquals(listOf(SseMessage(null, "[DONE]")), messages)
    }

    @Test
    fun `comment lines are ignored`() {
        val messages = readAll(": keep-alive\n\ndata: hi\n\n: another comment\n\n")
        assertEquals(listOf(SseMessage(null, "hi")), messages)
    }

    @Test
    fun `event field is captured`() {
        val messages = readAll("event: message\ndata: hi\n\n")
        assertEquals(listOf(SseMessage("message", "hi")), messages)
    }

    @Test
    fun `multi-line data is joined with newline`() {
        val messages = readAll("data: line one\ndata: line two\n\n")
        assertEquals(listOf(SseMessage(null, "line one\nline two")), messages)
    }

    @Test
    fun `single leading space after colon is stripped, not more`() {
        val messages = readAll("data:  padded\n\n")
        assertEquals(listOf(SseMessage(null, " padded")), messages)
    }

    @Test
    fun `trailing event without final blank line is dispatched at EOF`() {
        val messages = readAll("data: last")
        assertEquals(listOf(SseMessage(null, "last")), messages)
    }

    @Test
    fun `empty dispatch is skipped`() {
        val messages = readAll("\n\n\n")
        assertEquals(emptyList<SseMessage>(), messages)
    }

    @Test
    fun `byte limit exceeded throws IOException`() {
        try {
            readAll("data: hello\n\ndata: world\n\n", maxBytes = 10L)
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `data within byte limit passes`() {
        val messages = readAll("data: hi\n\n", maxBytes = 100L)
        assertEquals(listOf(SseMessage(null, "hi")), messages)
    }

    @Test
    fun `event-only dispatch is emitted with empty data`() {
        val messages = readAll("event: ping\n\ndata: x\n\n")
        assertEquals(
            listOf(SseMessage("ping", ""), SseMessage(null, "x")),
            messages,
        )
    }
}
