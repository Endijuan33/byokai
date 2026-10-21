package com.byokai.core.network

import java.io.IOException
import okio.BufferedSource

/**
 * One Server-Sent Events message.
 *
 * @property event the optional `event:` field, or null when the message
 * carried no event name.
 * @property data the message payload; multi-line `data:` fields are joined
 * with "\n".
 */
data class SseMessage(val event: String?, val data: String)

/**
 * Minimal Server-Sent Events reader over an [okio.BufferedSource].
 *
 * Understands the framing the OpenAI-compatible streaming APIs use:
 * - `:comment` / keep-alive lines are skipped.
 * - Multi-line `data:` fields are joined with "\n".
 * - A message is dispatched on each blank line; a pending message is also
 *   dispatched when EOF is reached (some servers omit the final blank
 *   line — the incomplete trailing event is still delivered rather than
 *   silently dropped).
 * - `id:` / `retry:` fields are ignored.
 *
 * Pure JVM: okio works on the JVM, no Android APIs are involved.
 */
object SseReader {

    /** Hard cap on bytes read from one stream; guards against unbounded responses. */
    const val MAX_BYTES: Long = 8L * 1024 * 1024

    /**
     * Reads SSE messages from [source] until EOF, invoking [onMessage] once
     * per dispatched message, in stream order.
     *
     * Skips `:comment` lines; joins multi-line `data:` fields with "\n".
     *
     * @throws IOException if more than [maxBytes] are read from [source].
     */
    suspend fun readAll(
        source: BufferedSource,
        maxBytes: Long = MAX_BYTES,
        onMessage: suspend (SseMessage) -> Unit,
    ) {
        var event: String? = null
        val data = StringBuilder()
        var bytesRead = 0L
        while (true) {
            val line = source.readUtf8Line() ?: break
            bytesRead += line.length + 1 // +1 for the stripped line terminator
            if (bytesRead > maxBytes) {
                throw IOException("SSE stream exceeded the $maxBytes byte limit")
            }
            when {
                line.isEmpty() -> {
                    if (event != null || data.isNotEmpty()) {
                        onMessage(SseMessage(event, data.toString()))
                        event = null
                        data.clear()
                    }
                }
                line.startsWith(':') -> Unit // comment / keep-alive: ignored
                else -> {
                    fieldValue(line, "event")?.let { event = it }
                    fieldValue(line, "data")?.let { value ->
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(value)
                    }
                }
            }
        }
        // Dispatch a trailing event when the server omitted the final blank line.
        if (event != null || data.isNotEmpty()) {
            onMessage(SseMessage(event, data.toString()))
        }
    }

    /**
     * Extracts the value of an SSE field, or null when [line] is not that
     * field. A single leading space after the colon is stripped, per the
     * SSE spec; a bare field name with no colon yields an empty value.
     */
    private fun fieldValue(line: String, field: String): String? {
        if (line == field) return ""
        val prefix = "$field:"
        if (!line.startsWith(prefix)) return null
        val rest = line.removePrefix(prefix)
        return if (rest.startsWith(' ')) rest.substring(1) else rest
    }
}
