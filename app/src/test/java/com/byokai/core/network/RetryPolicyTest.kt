package com.byokai.core.network

import com.byokai.domain.provider.ProviderError
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RetryPolicy]: which failures may be retried, when retries
 * stop, and how the backoff delay is computed.
 */
class RetryPolicyTest {

    private val network = ProviderError.Network(IOException("boom"))
    private val server500 = ProviderError.Server(500, "boom")
    private val server503 = ProviderError.Server(503, "boom")
    private val server400 = ProviderError.Server(400, "bad request")
    private val rateLimited = ProviderError.RateLimited("slow down", 5L)
    private val auth = ProviderError.Auth("bad key")
    private val parse = ProviderError.Parse("malformed")

    @Test
    fun `retries Network pre-stream up to MAX_RETRIES then stops`() {
        assertTrue(RetryPolicy.shouldRetry(0, network, streamStarted = false))
        assertTrue(RetryPolicy.shouldRetry(1, network, streamStarted = false))
        assertFalse(RetryPolicy.shouldRetry(2, network, streamStarted = false))
        assertFalse(RetryPolicy.shouldRetry(3, network, streamStarted = false))
    }

    @Test
    fun `retries Timeout and 5xx pre-stream`() {
        assertTrue(RetryPolicy.shouldRetry(0, ProviderError.Timeout, streamStarted = false))
        assertTrue(RetryPolicy.shouldRetry(0, server500, streamStarted = false))
        assertTrue(RetryPolicy.shouldRetry(1, server503, streamStarted = false))
    }

    @Test
    fun `retries RateLimited pre-stream`() {
        assertTrue(RetryPolicy.shouldRetry(0, rateLimited, streamStarted = false))
        assertTrue(RetryPolicy.shouldRetry(1, rateLimited, streamStarted = false))
        assertFalse(RetryPolicy.shouldRetry(2, rateLimited, streamStarted = false))
    }

    @Test
    fun `never retries Auth, Parse, or non-5xx`() {
        assertFalse(RetryPolicy.shouldRetry(0, auth, streamStarted = false))
        assertFalse(RetryPolicy.shouldRetry(0, parse, streamStarted = false))
        assertFalse(RetryPolicy.shouldRetry(0, server400, streamStarted = false))
        assertFalse(
            RetryPolicy.shouldRetry(0, ProviderError.InvalidModel("nope"), streamStarted = false),
        )
    }

    @Test
    fun `never retries after the stream started`() {
        assertFalse(RetryPolicy.shouldRetry(0, network, streamStarted = true))
        assertFalse(RetryPolicy.shouldRetry(0, ProviderError.Timeout, streamStarted = true))
        assertFalse(RetryPolicy.shouldRetry(0, server500, streamStarted = true))
        assertFalse(RetryPolicy.shouldRetry(0, rateLimited, streamStarted = true))
    }

    @Test
    fun `delayMillis is exponential without Retry-After`() {
        assertEquals(1000L, RetryPolicy.delayMillis(0, null))
        assertEquals(2000L, RetryPolicy.delayMillis(1, null))
        assertEquals(4000L, RetryPolicy.delayMillis(2, null))
    }

    @Test
    fun `delayMillis adds Retry-After seconds`() {
        assertEquals(6000L, RetryPolicy.delayMillis(0, 5L))
        assertEquals(7000L, RetryPolicy.delayMillis(1, 5L))
    }

    @Test
    fun `delayMillis caps Retry-After at 30 seconds`() {
        assertEquals(31_000L, RetryPolicy.delayMillis(0, 120L))
        assertEquals(31_000L, RetryPolicy.delayMillis(0, 30L))
        assertEquals(32_000L, RetryPolicy.delayMillis(1, 120L))
    }

    @Test
    fun `decide bundles shouldRetry and delayMillis`() {
        val decision = RetryPolicy.decide(0, rateLimited, streamStarted = false)
        assertEquals(RetryDecision(shouldRetry = true, delayMillis = 6000L), decision)

        val noRetry = RetryPolicy.decide(0, auth, streamStarted = false)
        assertEquals(RetryDecision(shouldRetry = false, delayMillis = 1000L), noRetry)
    }
}
